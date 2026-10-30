package com.jeffarese.rexmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeffarese.rexmobile.terminal.PromptOption
import com.jeffarese.rexmobile.terminal.QuestionTab
import com.jeffarese.rexmobile.terminal.ScreenPrompt

/**
 * A question Claude Code asks with its AskUserQuestion tool, laid out for a phone rather than as
 * Claude's menu: its questions as tabs to move between, the answers, a field for an answer of one's
 * own, and, for a multi-select question, the ticks first and then one button to go on. Chat about
 * this is set apart, as Claude sets it apart. On the Submit tab, the answers given, each a way back
 * to its question, over Claude's own Submit and Cancel.
 *
 * @param onTab moves to another tab, by index in the prompt's tabs.
 * @param onOwn answers in the user's own words.
 */
@Composable
fun QuestionCard(
    prompt: ScreenPrompt,
    onChoose: (PromptOption) -> Unit,
    onTab: (Int) -> Unit,
    onOwn: (String) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = 0.7f,
) {
    val review = prompt.review.isNotEmpty() || prompt.currentTab?.let { prompt.tabs[it].state == QuestionTab.State.SUBMIT } == true
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .maxHeightFraction(maxHeightFraction)
            .shadow(14.dp, CardShape)
            .clip(CardShape)
            .background(Rex.surface)
            .border(0.5.dp, Rex.divider, CardShape)
            // Screen readers say the question as the card comes up, as a dialog's title.
            .semantics { paneTitle = prompt.question ?: if (review) "Ready to submit your answers?" else "Claude has a question" }
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                // One question without a Submit tab has nothing to move between.
                if (prompt.tabs.size > 1) QuestionTabs(prompt, onTab)
            }
            IconButton(onClick = onCollapse) { Icon(Icons.Outlined.ExpandMore, "Hide the question", tint = Rex.textDim) }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(end = 10.dp)) {
            if (review) ReviewBody(prompt, onChoose, onTab) else QuestionBody(prompt, onChoose, onOwn)
        }
    }
}

/** The tabs, Claude's headers for its questions: a tick on each one answered, the one on screen filled in. */
@Composable
private fun QuestionTabs(prompt: ScreenPrompt, onTab: (Int) -> Unit) {
    val current = prompt.currentTab
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        prompt.tabs.forEachIndexed { i, tab ->
            val on = i == current
            val shape = RoundedCornerShape(50)
            Row(
                Modifier.clip(shape)
                    .background(if (on) Rex.accent else Rex.keyBg)
                    .border(0.5.dp, if (on) Rex.accent else Rex.divider, shape)
                    // Moving needs to know where Claude is now.
                    .then(if (current != null && !on) Modifier.clickable(role = Role.Tab) { onTab(i) } else Modifier)
                    .semantics { selected = on }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tab.state == QuestionTab.State.ANSWERED) {
                    Icon(Icons.Outlined.Check, "Answered", tint = if (on) Rex.onAccent else Rex.done, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    tab.label,
                    color = if (on) Rex.onAccent else Rex.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun QuestionBody(prompt: ScreenPrompt, onChoose: (PromptOption) -> Unit, onOwn: (String) -> Unit) {
    val multi = prompt.multiSelect
    val answers = prompt.options.filter { it.role == PromptOption.Role.ANSWER }
    val own = prompt.options.firstOrNull { it.role == PromptOption.Role.OWN }
    val submit = prompt.options.firstOrNull { it.role == PromptOption.Role.SUBMIT }
    val chat = prompt.options.firstOrNull { it.role == PromptOption.Role.CHAT }
    Text(
        prompt.question ?: "Claude has a question", color = Rex.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 2.dp, bottom = if (multi) 2.dp else 10.dp),
    )
    if (multi) Text("Pick any", color = Rex.textDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Claude's highlight only says where its cursor is: here a tap answers, so it isn't shown.
        answers.forEach { o -> OptionButton(o.copy(selected = false)) { onChoose(o) } }
        if (own != null) OwnAnswer(own, multi, onOwn)
    }
    if (submit != null || chat != null) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (chat != null) {
                Text(
                    chat.label, color = Rex.textDim, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onChoose(chat) }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (submit != null) {
                PrimaryButton(if (nextIsQuestion(prompt)) "Next" else "Done") { onChoose(submit) }
            }
        }
    }
}

/** True if Submit under this question leads to another question, rather than to the review of the answers on the Submit tab. */
private fun nextIsQuestion(prompt: ScreenPrompt): Boolean {
    val at = prompt.currentTab ?: return false
    return prompt.tabs.getOrNull(at + 1)?.let { it.state != QuestionTab.State.SUBMIT } == true
}

/**
 * The field for an answer of one's own, holding what is already typed into Claude's option. In a
 * multi-select question the answer is ticked like the others; a single choice is sent with it.
 */
@Composable
private fun OwnAnswer(option: PromptOption, multi: Boolean, onOwn: (String) -> Unit) {
    var text by remember(option.typed) { mutableStateOf(option.typed.orEmpty()) }
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(12.dp)
    val ticked = option.checked == true
    val send = {
        if (text.isNotBlank() && text.trim() != option.typed) {
            focus.clearFocus()
            onOwn(text)
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape)
            .background(Rex.keyBg)
            .border(if (ticked) 1.dp else 0.5.dp, if (ticked) Rex.accent else Rex.divider, shape)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (text.isEmpty()) Text("Something else…", color = Rex.textDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                text, { text = it },
                textStyle = TextStyle(color = Rex.text, fontSize = 14.sp),
                cursorBrush = SolidColor(Rex.accent),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = if (multi) ImeAction.Done else ImeAction.Send),
                keyboardActions = KeyboardActions(onDone = { send() }, onSend = { send() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Your own answer" },
            )
        }
        IconButton(onClick = send, enabled = text.isNotBlank() && text.trim() != option.typed) {
            Icon(
                if (multi) Icons.Outlined.Check else Icons.AutoMirrored.Outlined.Send,
                if (multi) "Add your answer" else "Send your answer",
                tint = if (text.isNotBlank() && text.trim() != option.typed) Rex.accent else Rex.textDim,
            )
        }
    }
}

/** Claude's review before the answers go: each answer, a tap away from its question, then Claude's own buttons. */
@Composable
private fun ReviewBody(prompt: ScreenPrompt, onChoose: (PromptOption) -> Unit, onTab: (Int) -> Unit) {
    val canMove = prompt.currentTab != null
    Text(
        prompt.question ?: "Ready to submit your answers?", color = Rex.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        prompt.review.forEachIndexed { i, a ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Rex.bg)
                    .then(if (canMove) Modifier.clickable(role = Role.Button) { onTab(i) } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(a.question, color = Rex.textDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(a.answer, color = Rex.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Claude's own buttons, its first the one that sends.
        prompt.options.drop(1).forEach { o ->
            Text(
                o.label, color = Rex.text, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onChoose(o) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        prompt.options.firstOrNull()?.let { o -> PrimaryButton(o.label) { onChoose(o) } }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.heightIn(min = 42.dp).clip(shape).background(Rex.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Rex.onAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
