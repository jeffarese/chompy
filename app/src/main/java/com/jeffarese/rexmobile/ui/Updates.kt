package com.jeffarese.rexmobile.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jeffarese.rexmobile.BuildConfig
import com.jeffarese.rexmobile.R
import com.jeffarese.rexmobile.model.Release
import com.jeffarese.rexmobile.model.UpdateState
import com.jeffarese.rexmobile.model.Updater
import com.jeffarese.rexmobile.model.releaseNoteLines

/** Install the downloaded update, first sending the user to let Rex install apps if Android doesn't yet. */
fun startInstall(updater: Updater, context: Context) {
    if (updater.canInstall()) updater.install()
    else runCatching { context.startActivity(updater.permissionIntent()) }
}

/** Whether Home has something to say about updates, for the dot on its Settings button. */
@Composable
fun updatePending(updater: Updater): Boolean {
    val state by updater.state.collectAsState()
    return state is UpdateState.Ready || state is UpdateState.Available
}

/**
 * Home's card for a new version on its way, ready to install or installing, or for the version
 * just installed. Tapping it opens [UpdateSheet] through [onOpen]; it takes no room when there is
 * nothing to say, or the user put the version off.
 */
@Composable
fun UpdateCard(updater: Updater, onOpen: () -> Unit) {
    val state by updater.state.collectAsState()
    val justUpdated by updater.justUpdated.collectAsState()
    val snoozed by updater.snoozed.collectAsState()
    val context = LocalContext.current
    val pending = (state as? UpdateState.WithRelease)?.takeIf { it.release.version != snoozed }
    AnimatedVisibility(
        pending != null || justUpdated != null,
        enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(),
    ) {
        // Null for the version just installed.
        val s = pending
        val release = s?.release ?: justUpdated ?: return@AnimatedVisibility
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(Rex.surface).border(1.dp, Rex.accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .clickable(onClick = onOpen).padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Rex.accentSoft), contentAlignment = Alignment.Center) {
                Icon(if (s == null) Icons.Outlined.NewReleases else Icons.Outlined.SystemUpdate, null, tint = Rex.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val firstNote = releaseNoteLines(release.notes).firstOrNull()?.text
                val (title, detail) = when (s) {
                    null -> "Updated to ${release.version}" to (firstNote ?: "See what's new")
                    is UpdateState.Available -> "Chompy ${release.version} is out" to (s.error ?: listOfNotNull(size(context, release), firstNote).joinToString(" · "))
                    // Its percentage rolls, below.
                    is UpdateState.Downloading -> "Downloading Chompy ${release.version}" to ""
                    is UpdateState.Ready -> "Chompy ${release.version} is ready" to (s.error ?: firstNote ?: "Tap to see what's new")
                    is UpdateState.Installing -> "Updating to ${release.version}" to "Chompy closes and is back in a moment"
                }
                Text(title, color = Rex.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val failed = (s as? UpdateState.Ready)?.error != null || (s as? UpdateState.Available)?.error != null
                if (s is UpdateState.Downloading) {
                    Row {
                        RollingNumber((s.progress * 100).toInt(), Rex.textDim, 13.sp)
                        Text("% of ${size(context, release) ?: "the update"}", color = Rex.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { s.progress }, color = Rex.accent, trackColor = Rex.surfaceHigh, strokeCap = StrokeCap.Round,
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp).height(3.dp),
                    )
                } else {
                    Text(detail, color = if (failed) Rex.error else Rex.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            when (s) {
                null -> IconButton(onClick = updater::dismissWhatsNew) { Icon(Icons.Outlined.Close, "Dismiss", tint = Rex.textDim) }
                is UpdateState.Available -> TextButton(onClick = { updater.download(release) }) {
                    Text(if (s.error != null) "Retry" else "Download", color = Rex.accent)
                }
                is UpdateState.Ready -> Button(
                    onClick = { startInstall(updater, context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Rex.accent, contentColor = Rex.onAccent),
                ) { Text(if (s.error != null) "Retry" else "Update") }
                is UpdateState.Installing -> CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp, color = Rex.accent)
                is UpdateState.Downloading -> Unit
            }
        }
    }
}

/**
 * A new version's notes, with what to do about it: download, install, or wait. For the version
 * just installed, its notes alone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(updater: Updater, onDismiss: () -> Unit) {
    val state by updater.state.collectAsState()
    val justUpdated by updater.justUpdated.collectAsState()
    val context = LocalContext.current
    // Read again on each return, as from Android's settings, where the user may have just allowed it.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val canInstall = remember(resumed, state) { updater.canInstall() }
    val pending = state as? UpdateState.WithRelease
    val release = pending?.release ?: justUpdated
    if (release == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Rex.surface, contentColor = Rex.text,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.chompy_head), contentDescription = null, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (pending == null) "Updated to ${release.version}" else "Chompy ${release.version}",
                        style = MaterialTheme.typography.titleLarge, color = Rex.text,
                    )
                    val sub = if (pending == null) "What this version brings"
                    else listOfNotNull("You have ${BuildConfig.VERSION_NAME}", size(context, release)).joinToString(" · ")
                    Text(sub, color = Rex.textDim, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            val lines = remember(release) { releaseNoteLines(release.notes) }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)).background(Rex.bg)
                    .verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (lines.isEmpty()) Text("No notes for this version.", color = Rex.textDim, fontSize = 14.sp)
                lines.forEach { line ->
                    Row {
                        if (line.item) Text("•", color = Rex.accent, fontSize = 14.sp, modifier = Modifier.width(16.dp))
                        Text(
                            line.text, color = if (line.item) Rex.text else Rex.textDim, fontSize = 14.sp,
                            fontWeight = if (line.item) FontWeight.Normal else FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            val accent = ButtonDefaults.buttonColors(containerColor = Rex.accent, contentColor = Rex.onAccent)
            val wide = Modifier.fillMaxWidth().height(48.dp)
            when (pending) {
                null -> Button(onClick = { updater.dismissWhatsNew(); onDismiss() }, colors = accent, modifier = wide) { Text("Got it") }
                is UpdateState.Available -> {
                    pending.error?.let { Problem(it) }
                    Button(onClick = { updater.download(release) }, colors = accent, modifier = wide) {
                        Text(listOfNotNull("Download", size(context, release)).joinToString(" · "))
                    }
                }
                is UpdateState.Downloading -> Progress("Downloading… ", pending.progress, percent = (pending.progress * 100).toInt())
                is UpdateState.Ready -> {
                    pending.error?.let { Problem(it) }
                    if (!canInstall) Hint("Android asks once to let Chompy install its own updates.")
                    Button(onClick = { startInstall(updater, context) }, colors = accent, modifier = wide) {
                        Text(if (canInstall) "Update now" else "Allow and update")
                    }
                    Spacer(Modifier.height(8.dp))
                    Hint("Chompy closes to update and is back in a few seconds. Your sessions keep running on the Mac.")
                }
                is UpdateState.Installing -> Progress("Installing… Android may ask you to confirm.", null)
            }
            if (pending != null && pending !is UpdateState.Installing) {
                TextButton(
                    onClick = { updater.snooze(release); onDismiss() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("Later", color = Rex.textDim) }
            }
        }
    }
}

@Composable
private fun Progress(label: String, progress: Float?, percent: Int? = null) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
        Row {
            Text(label, color = Rex.textDim, fontSize = 13.sp)
            if (percent != null) {
                RollingNumber(percent, Rex.textDim, 13.sp)
                Text("%", color = Rex.textDim, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        val modifier = Modifier.fillMaxWidth().height(4.dp)
        if (progress != null) LinearProgressIndicator(progress = { progress }, color = Rex.accent, trackColor = Rex.surfaceHigh, strokeCap = StrokeCap.Round, modifier = modifier)
        else LinearProgressIndicator(color = Rex.accent, trackColor = Rex.surfaceHigh, strokeCap = StrokeCap.Round, modifier = modifier)
    }
}

/**
 * A number whose digits roll up as it counts, like an odometer: a digit that changes slides out of its window above
 * as the next slides in from below, and the others stay still. A digit the number gains, as 99 becomes 100, rolls in
 * from an empty place too, rather than showing up before the others have turned. Its figures are all one width, so
 * the number keeps its place as it counts.
 */
@Composable
private fun RollingNumber(value: Int, color: Color, fontSize: TextUnit, places: Int = 3) {
    val digits = value.toString()
    Row {
        // From the left: the places the number doesn't fill yet are empty, so its next digit has somewhere to roll in.
        for (place in maxOf(places, digits.length) downTo 1) {
            AnimatedContent(
                digits.getOrNull(digits.length - place),
                transitionSpec = {
                    (slideInVertically(tween(220)) { it } + fadeIn(tween(220))) togetherWith
                        (slideOutVertically(tween(220)) { -it } + fadeOut(tween(160))) using SizeTransform(clip = true)
                },
                label = "digit",
            ) { d -> if (d != null) Text("$d", color = color, fontSize = fontSize, style = TextStyle(fontFeatureSettings = "tnum")) }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Rex.textFaint, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
}

@Composable
private fun Problem(text: String) {
    Text(text, color = Rex.error, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
}

private fun size(context: Context, release: Release): String? =
    release.size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
