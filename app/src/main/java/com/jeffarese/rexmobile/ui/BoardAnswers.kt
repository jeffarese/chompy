package com.jeffarese.rexmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.jeffarese.rexmobile.model.AppModel
import com.jeffarese.rexmobile.model.Notifier
import com.jeffarese.rexmobile.model.PanePrompt
import com.jeffarese.rexmobile.model.PanePrompts
import com.jeffarese.rexmobile.rex.ProgramStatus
import com.jeffarese.rexmobile.terminal.PromptOption

/**
 * Claude's prompts on the board's Needs you cards, so a card answers as its notification does: a card
 * whose program is Claude Code has its pane's screen read (see [PanePrompts]), and when that shows a
 * prompt one press answers, the card offers the answers the notification would, [Notifier.offered].
 *
 * What was read holds for the status it was read under: a newer status hides it until it is read
 * again. After an answer the card shows no answers until it is read again, so it never offers a
 * prompt that may have gone.
 */
@Stable
internal class BoardPrompts {
    private class Read(val status: ProgramStatus?, val prompt: PanePrompt?)

    /** What each card's screen showed, by [BoardEntry.key]. */
    private val reads = mutableStateMapOf<String, Read>()
    /** How many times each card has been asked to read its screen again, which reads it once more. */
    private val rereads = mutableStateMapOf<String, Int>()
    /** The option each card is answering with, while its answer is under way. */
    val answering = mutableStateMapOf<String, PromptOption>()

    /** The prompt [e]'s pane shows, as read under its current status; null if none, or not read yet. */
    fun promptOf(e: BoardEntry): PanePrompt? = reads[e.key]?.takeIf { it.status == e.meta.status }?.prompt

    /**
     * After [e]'s answer went as [result]: its answers go. One taken leaves them gone until the card's
     * status changes, as it does once Claude goes on; otherwise the screen is read again for what it
     * shows now.
     */
    fun answered(e: BoardEntry, result: PanePrompts.Answer) {
        // A card gone from the board meanwhile has nothing to read again.
        val read = reads[e.key]
        if (result == PanePrompts.Answer.BUSY || read == null) return
        reads[e.key] = Read(read.status, null)
        if (result != PanePrompts.Answer.ANSWERED) rereads[e.key] = (rereads[e.key] ?: 0) + 1
    }

    /**
     * Read the screens of [board]'s Claude Code cards that need the user: once when a card appears and
     * again when its status changes or [answered] asks, not on every update of its host. Only while
     * Home is on screen and the card's host is among [online]; a read cut short by either is made again
     * once both hold. Several cards read at once, each on its own.
     */
    @Composable
    fun ReadScreens(model: AppModel, board: List<BoardEntry>, online: Set<String>) {
        val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        val onScreen = lifecycle.isAtLeast(Lifecycle.State.STARTED)
        board.filter { it.group == BoardGroup.NEEDS_YOU && programOf(it.meta).startsWith("claude") }.forEach { e ->
            key(e.key) {
                val status = e.meta.status
                val reading = onScreen && e.hostId in online
                LaunchedEffect(status, rereads[e.key], reading) {
                    val host = model.connection(e.hostId)
                    if (!reading || host == null) return@LaunchedEffect
                    reads[e.key] = Read(status, model.prompts.read(host, e.sessionId, e.blockId))
                }
                // A card that leaves the board takes what was read with it, so one back later reads afresh.
                DisposableEffect(Unit) {
                    onDispose {
                        reads.remove(e.key)
                        rereads.remove(e.key)
                    }
                }
            }
        }
    }
}

/**
 * Under a card's lines, what Claude asks and the answers its notification offers, a button each. What
 * it asks is the question, or for a permission what it is about, such as the command; left out when
 * the card's [detail] already says it. While [answering], the buttons take no taps, and the one tapped
 * says it is under way.
 */
@Composable
internal fun BoardAnswers(prompt: PanePrompt, detail: String?, answering: PromptOption?, onAnswer: (PromptOption) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val asks = asksOf(prompt, detail)
        if (asks != null && prompt.permission) {
            Text(
                asks, color = Rex.textDim, fontFamily = Rex.mono, fontSize = 12.sp, lineHeight = 16.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Rex.bg).padding(horizontal = 9.dp, vertical = 6.dp),
            )
        } else if (asks != null) {
            Text(asks, color = Rex.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Notifier.offered(prompt).forEach { o ->
            AnswerButton(o, enabled = answering == null, sending = answering == o) { onAnswer(o) }
        }
    }
}

/**
 * What [prompt] asks, as the card shows it over its answers: the question, or for a permission its
 * context, such as "Bash command" and the command. Null when [detail], the line the card already
 * shows, says it: the question, or the command or path that follows the context's header.
 */
internal fun asksOf(prompt: PanePrompt, detail: String?): String? {
    val p = prompt.prompt
    val asks = if (prompt.permission) p.context.joinToString("\n") else p.question
    val gist = if (prompt.permission) p.context.getOrNull(1) ?: p.context.firstOrNull() else p.question
    if (asks.isNullOrBlank()) return null
    return asks.takeUnless { gist != null && detail?.contains(gist.trim(), ignoreCase = true) == true }
}

/** An answer, as the terminal's card shows one but its label only, and 48dp tall at least. */
@Composable
private fun AnswerButton(option: PromptOption, enabled: Boolean, sending: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            // The others fade while an answer is under way; the one being sent stays as it was.
            .alpha(if (enabled || sending) 1f else 0.5f)
            .clip(shape).background(Rex.keyBg).border(0.5.dp, Rex.divider, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (sending) stateDescription = "Answering" }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The number Claude's menu shows it by, which a screen reader needn't say before the label.
        Box(Modifier.size(24.dp).clip(CircleShape).background(Rex.surfaceHigh).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            if (sending) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Rex.accent)
            else option.number?.let { Text("$it", color = Rex.textDim, fontFamily = Rex.mono, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
        Spacer(Modifier.width(11.dp))
        Text(option.label, color = Rex.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
