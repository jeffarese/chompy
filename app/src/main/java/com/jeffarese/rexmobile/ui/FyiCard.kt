package com.jeffarese.rexmobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeffarese.rexmobile.terminal.ClaudeSurvey
import com.jeffarese.rexmobile.terminal.FyiAction
import com.jeffarese.rexmobile.terminal.FyiLine
import com.jeffarese.rexmobile.terminal.FyiNote
import com.jeffarese.rexmobile.terminal.SurveyAnswer

/**
 * Claude Code's colour for the ✦ of its notes and their buttons' digits: its dark theme's, or its light theme's on a
 * light palette, where the pale one reads at under 2:1. Its tag is small text, so either is made readable there.
 */
internal val FyiAccent: Color get() = Rex.palette.let { it.readable(if (it.dark) Color(0xFFB1B9F9) else Color(0xFF5769F7)) }
/** For text on [FyiAccent]: dark ink on the pale one, white on the deep one. */
private val FyiInk: Color get() = if (Rex.palette.dark) Color(0xFF16162A) else Color.White
private val FyiShape = RoundedCornerShape(18.dp)

/** The height the card may take even past its share of a short terminal, as with the keyboard up: room for two rows of buttons. */
private val MIN_CARD_HEIGHT = 300.dp

/** What Claude Code puts in its input box for "Chat in main session", ahead of the note. */
internal const val NOTE_FOR_CLAUDE = "Here is a note offered by a side agent:"

/**
 * A note from Claude Code's "You should know" plugin over the bottom of the terminal, with its
 * buttons as real ones: the first filled, the second outlined, the rest quieter, and Dismiss as
 * the ✕ in the corner. An open explanation reads as text, its titles bold and its sketches in the
 * terminal's font, and scrolls with the headline when long, so the buttons stay in sight; rows
 * Claude hasn't shown yet are being read.
 *
 * @param pressed the button just tapped, while Claude takes it: the others wait.
 * @param blocked why the buttons can't work now, if they can't.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FyiCard(
    note: FyiNote,
    pressed: FyiAction?,
    blocked: String?,
    onPress: (FyiAction) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = 0.62f,
) {
    val context = LocalContext.current
    val dismiss = note.actions.firstOrNull { it.key == 0 && it.label.equals("Dismiss", ignoreCase = true) }
    val buttons = note.actions - setOfNotNull(dismiss)
    // Buttons to come, unless the notice says why there are none.
    val busy = note.complete && note.actions.isEmpty() && blocked == null
    val enabled = pressed == null && blocked == null
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .maxHeightFraction(maxHeightFraction, atLeast = MIN_CARD_HEIGHT)
            .shadow(14.dp, FyiShape)
            .clip(FyiShape)
            .background(Rex.surface)
            .border(0.5.dp, FyiAccent.copy(alpha = 0.35f), FyiShape)
            // Screen readers say the note's headline as the card comes up, and again once it is read.
            .semantics { paneTitle = note.headline?.let { "Note from Claude: $it" } ?: "Note from Claude" }
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✦", color = FyiAccent, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                (note.tag ?: "Note").uppercase(), color = tagColor(note.tag), fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, maxLines = 1,
            )
            if (busy) {
                Spacer(Modifier.width(10.dp))
                WorkingDots(color = FyiAccent)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCollapse) { Icon(Icons.Outlined.ExpandMore, "Hide the note", tint = Rex.textDim) }
            if (dismiss != null) {
                IconButton(onClick = { onPress(dismiss) }, enabled = enabled) {
                    if (pressed == dismiss) WorkingDots(color = Rex.textDim)
                    else Icon(Icons.Outlined.Close, "Dismiss", tint = Rex.textDim, modifier = Modifier.alpha(if (enabled) 1f else 0.4f))
                }
            }
        }
        // The headline and explanation scroll, so the notice and buttons below always have their room.
        val link = note.link
        val body = note.body.filter { line -> link == null || link !in line.text }
        // From the top again when the explanation opens.
        val scroll = remember(note.headline, body.isEmpty()) { ScrollState(0) }
        Column(Modifier.weight(1f, fill = false).fadeBottom(scroll).verticalScroll(scroll).padding(end = 12.dp)) {
            Text(
                note.headline ?: "Reading the note…",
                color = if (note.headline == null) Rex.textDim else Rex.text,
                fontSize = 15.sp, lineHeight = 21.sp,
                fontWeight = if (note.body.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
            )
            if (body.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Explanation(body)
            }
        }
        if (blocked != null) Notice(blocked)
        if (buttons.isNotEmpty() || note.link != null) {
            FlowRow(
                Modifier.padding(top = 14.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                note.link?.let { url ->
                    NoteButton("Open page", Kind.LINK, enabled = true, working = false) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
                buttons.forEach { a ->
                    val kind = when {
                        a.label.startsWith("Press again", ignoreCase = true) -> Kind.WARN
                        a == buttons.first() && note.link == null -> Kind.PRIMARY
                        a == buttons.getOrNull(1) || (a == buttons.first() && note.link != null) -> Kind.SECONDARY
                        else -> Kind.QUIET
                    }
                    NoteButton(a.label, kind, enabled, working = pressed == a) { onPress(a) }
                }
            }
        }
    }
}

/**
 * One of Claude Code's surveys over the bottom of the terminal, such as "How is Claude doing this
 * session?", with its answers as buttons and Dismiss as the ✕ in the corner.
 *
 * @param pressed the answer just tapped, while Claude takes it: the others wait.
 * @param blocked why the answers can't work now, if they can't.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SurveyCard(
    survey: ClaudeSurvey,
    pressed: SurveyAnswer?,
    blocked: String?,
    onAnswer: (SurveyAnswer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dismiss = survey.answers.firstOrNull { it.label.equals("Dismiss", ignoreCase = true) }
    val enabled = pressed == null && blocked == null
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .shadow(14.dp, FyiShape)
            .clip(FyiShape)
            .background(Rex.surface)
            .border(0.5.dp, Rex.divider, FyiShape)
            .semantics { paneTitle = "Survey from Claude: ${survey.question}" }
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 14.dp),
    ) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("●", color = Rex.unseen, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text("SURVEY", color = Rex.unseen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (dismiss != null) {
                IconButton(onClick = { onAnswer(dismiss) }, enabled = enabled) {
                    if (pressed == dismiss) WorkingDots(color = Rex.textDim)
                    else Icon(Icons.Outlined.Close, "Dismiss", tint = Rex.textDim, modifier = Modifier.alpha(if (enabled) 1f else 0.4f))
                }
            }
        }
        Text(survey.question, color = Rex.text, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(end = 12.dp))
        if (blocked != null) Notice(blocked)
        FlowRow(
            Modifier.padding(top = 14.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            survey.link?.let { url ->
                NoteButton("Open page", Kind.LINK, enabled = true, working = false) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            (survey.answers - setOfNotNull(dismiss)).forEach { a ->
                NoteButton(a.label, Kind.SECONDARY, enabled, working = pressed == a) { onAnswer(a) }
            }
        }
    }
}

/** Why a card's buttons can't work now. */
@Composable
private fun Notice(text: String) {
    Row(Modifier.padding(top = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Info, null, tint = Rex.blocked, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Rex.blocked, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/** The note folded away, so the terminal shows; tap to see it again. */
@Composable
fun CollapsedFyi(note: FyiNote, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.keepTapsOffTerminal().padding(10.dp).widthIn(max = 320.dp).clip(RoundedCornerShape(50)).background(FyiAccent)
            .clickable(role = Role.Button, onClick = onExpand)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "✦ " + listOfNotNull(note.tag, note.headline).joinToString(" · ").ifEmpty { "Note from Claude" },
            color = FyiInk, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.ExpandLess, "Show the note", tint = FyiInk, modifier = Modifier.size(18.dp))
    }
}

private fun tagColor(tag: String?) = if (tag.equals("Heads up", ignoreCase = true)) Rex.blocked else FyiAccent

private enum class Kind { PRIMARY, SECONDARY, QUIET, WARN, LINK }

@Composable
private fun NoteButton(label: String, kind: Kind, enabled: Boolean, working: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val (bg, fg) = when (kind) {
        Kind.PRIMARY -> FyiAccent to FyiInk
        Kind.SECONDARY, Kind.LINK -> Rex.keyBg to Rex.text
        Kind.QUIET -> Color.Transparent to Rex.textDim
        Kind.WARN -> Rex.error.copy(alpha = 0.14f) to Rex.error
    }
    val border = when (kind) {
        Kind.PRIMARY -> Color.Transparent
        Kind.WARN -> Rex.error.copy(alpha = 0.6f)
        else -> Rex.divider
    }
    Row(
        Modifier.heightIn(min = if (kind == Kind.QUIET) 38.dp else 44.dp).clip(shape).background(bg).border(0.5.dp, border, shape)
            .alpha(if (enabled || working) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (kind == Kind.QUIET) 12.dp else 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (kind == Kind.LINK) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = Rex.unseen, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label, color = fg, fontSize = if (kind == Kind.QUIET) 13.sp else 14.sp,
            fontWeight = if (kind == Kind.PRIMARY) FontWeight.SemiBold else FontWeight.Medium, maxLines = 2,
        )
        if (working) {
            Spacer(Modifier.width(8.dp))
            WorkingDots(color = fg)
        }
    }
}

/** An open explanation: titles, paragraphs, sketches in the terminal's font that scroll sideways if wide. */
@Composable
private fun Explanation(lines: List<FyiLine>) {
    val blocks = remember(lines) { group(lines) }
    Column {
        blocks.forEachIndexed { i, block ->
            when (block.kind) {
                FyiLine.Kind.TITLE -> Text(
                    block.text, color = Rex.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = if (i == 0) 0.dp else 4.dp, bottom = 4.dp),
                )
                FyiLine.Kind.TEXT -> Text(
                    block.text, color = Rex.text.copy(alpha = 0.86f), fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                FyiLine.Kind.SKETCH -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(Rex.bg)
                        .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(block.text, color = Rex.text, fontFamily = Rex.mono, fontSize = 12.sp, lineHeight = 16.sp, softWrap = false)
                }
                FyiLine.Kind.GAP -> Spacer(Modifier.height(10.dp))
                FyiLine.Kind.MISSING -> Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    WorkingDots(color = Rex.textFaint)
                    Spacer(Modifier.width(10.dp))
                    Text("Reading the rest from Claude…", color = Rex.textDim, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Fades the bottom of a scrolling area into the card while there is more below. */
private fun Modifier.fadeBottom(scroll: ScrollState) = drawWithContent {
    drawContent()
    if (scroll.canScrollForward) {
        val fade = 28.dp.toPx()
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Rex.surface), startY = size.height - fade, endY = size.height),
            topLeft = Offset(0f, size.height - fade),
        )
    }
}

/** Sketch lines in a row, as one block. */
private fun group(lines: List<FyiLine>): List<FyiLine> {
    val out = mutableListOf<FyiLine>()
    for (line in lines) {
        val last = out.lastOrNull()
        out += if (line.kind == FyiLine.Kind.SKETCH && last?.kind == FyiLine.Kind.SKETCH) {
            out.removeAt(out.lastIndex)
            FyiLine(FyiLine.Kind.SKETCH, last.text + "\n" + line.text)
        } else line
    }
    return out
}
