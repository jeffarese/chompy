package com.jeffarese.rexmobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeffarese.rexmobile.model.Alert
import com.jeffarese.rexmobile.model.AppModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * A banner at the top for agents that finish or need you, wherever you are in the app. Swipe it up to
 * dismiss it. Screen readers say it once they are done speaking, and offer to dismiss it.
 */
@Composable
fun AlertBanner(model: AppModel, onOpen: (Target) -> Unit) {
    var current by remember { mutableStateOf<Alert?>(null) }
    var shownAt by remember { mutableStateOf(0L) }
    // How far the banner has been dragged up, in pixels: it follows the finger.
    var dragY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        model.alerts.collect { a ->
            // Nothing from the pane on screen needs a banner, a question included: it is in front of you.
            if (a.blockId == model.visibleBlockId) return@collect
            val showing = current
            if (showing == null) {
                dragY = 0f
                current = a
                shownAt = System.currentTimeMillis()
                return@collect
            }
            // A bell is too minor to replace another kind of alert or hold the banner up, and a repeat from the
            // same tab updates the banner without keeping it up longer. Anything else, such as a tab that
            // finished now needing you, gets its full time.
            when {
                a.kind == Alert.Kind.BELL -> if (showing.kind == Alert.Kind.BELL) current = a
                a.blockId == showing.blockId && a.kind == showing.kind -> current = a
                else -> {
                    current = a
                    shownAt = System.currentTimeMillis()
                }
            }
        }
    }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(shownAt) {
        if (current != null) {
            // Longer for someone who has asked Android for more time to act on what shows.
            delay(accessibility?.calculateRecommendedTimeoutMillis(BANNER_MS, containsIcons = true, containsText = true, containsControls = true) ?: BANNER_MS)
            current = null
        }
    }
    val a = current
    AnimatedVisibility(
        visible = a != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val alert = a ?: return@AnimatedVisibility
        val host = model.connection(alert.hostId)
        val program = programOf(host?.state?.value?.blocks?.get(alert.blockId))
        val dismissAt = with(LocalDensity.current) { 48.dp.toPx() }
        AlertCard(
            alert, host?.config?.label, program,
            onClick = {
                current = null
                val view = host?.state?.value?.views?.get(alert.sessionId)
                val win = view?.windows?.firstOrNull { w -> w.blocks.any { it.blockId == alert.blockId } }
                if (win != null) onOpen(Target(alert.hostId, alert.sessionId, win.windowId, alert.blockId))
            },
            modifier = Modifier.statusBarsPadding()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    dismiss("Dismiss") {
                        current = null
                        true
                    }
                }
                .offset { IntOffset(0, dragY.roundToInt()) }
                .draggable(
                    orientation = Orientation.Vertical,
                    // Up freely, and only a little down, so it reads as something to push away.
                    state = rememberDraggableState { d -> dragY = (dragY + d).coerceAtMost(dismissAt / 4) },
                    onDragStopped = { velocity ->
                        if (dragY < -dismissAt || velocity < -1000f) current = null
                        else animate(dragY, 0f) { v, _ -> dragY = v }
                    },
                ),
        )
    }
}

/** How long the banner stays up, unless the user has asked for longer. */
private const val BANNER_MS = 6000L

/**
 * What the banner shows, laid out like an agent's card on Home: the program's icon with a dot in the colour of what
 * happened, then what happened and where in small type, the tab's title, and what it said. No border: the shadow
 * lifts it off whatever is under it.
 */
@Composable
internal fun AlertCard(alert: Alert, hostLabel: String?, program: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = when (alert.kind) {
        Alert.Kind.BLOCKED -> Rex.blocked
        Alert.Kind.DONE -> Rex.done
        Alert.Kind.ERROR -> Rex.error
        else -> Rex.unseen
    }
    val what = when (alert.kind) {
        Alert.Kind.BLOCKED -> "Needs you"
        Alert.Kind.DONE -> "Finished"
        Alert.Kind.ERROR -> "Failed"
        Alert.Kind.BELL -> "Bell"
        Alert.Kind.NOTIFICATION -> "Notification"
    }
    Row(
        modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp)).background(Rex.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            // The plain tile a step up from the banner, which is already the tile's colour.
            ProgramIcon(program, size = 26.dp, tile = Rex.keyActive)
            Box(
                Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(11.dp)
                    .background(Rex.surfaceHigh, CircleShape).padding(2.dp).background(color, CircleShape),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(what, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                hostLabel?.let { Text(" · $it", color = Rex.textFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text(alert.title, color = Rex.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            alert.body?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Rex.textDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
