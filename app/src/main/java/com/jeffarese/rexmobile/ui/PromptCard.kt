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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeffarese.rexmobile.terminal.PromptOption
import com.jeffarese.rexmobile.terminal.ScreenPrompt

internal val CardShape = RoundedCornerShape(18.dp)

/**
 * The question on screen with a button per answer, over the bottom of the terminal.
 * It is at most [maxHeightFraction] of the space it's given, and scrolls when there are many options.
 *
 * What the question is about (the prompt's context, such as the command to approve) shows above
 * it, since the card covers the terminal where it is. In a multi-select question each option shows
 * its tick, and the highlight is only an outline: there it marks Claude's cursor, not the answer.
 */
@Composable
fun PromptCard(
    prompt: ScreenPrompt,
    onChoose: (PromptOption) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = 0.62f,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .maxHeightFraction(maxHeightFraction)
            .shadow(14.dp, CardShape)
            .clip(CardShape)
            .background(Rex.surface)
            .border(0.5.dp, Rex.divider, CardShape)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = if (prompt.context.isEmpty()) Alignment.CenterVertically else Alignment.Top) {
            Column(Modifier.weight(1f)) {
                if (prompt.context.isNotEmpty()) PromptContext(prompt.context, Modifier.padding(top = 12.dp, bottom = 2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Rex.blocked))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        prompt.question ?: "Choose an option", color = Rex.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            IconButton(onClick = onCollapse) { Icon(Icons.Outlined.ExpandMore, "Hide the answers", tint = Rex.textDim) }
        }
        val options = prompt.options.ifEmpty {
            if (prompt.kind == ScreenPrompt.Kind.YES_NO) listOf(PromptOption(null, "Yes"), PromptOption(null, "No")) else emptyList()
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(end = 10.dp)) {
            if (prompt.kind == ScreenPrompt.Kind.YES_NO) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { o -> YesNoButton(o, Modifier.weight(1f)) { onChoose(o) } }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { o -> OptionButton(o, cursorOnly = prompt.multiSelect) { onChoose(o) } }
                }
            }
        }
    }
}

/** The prompt folded away, so the terminal shows; tap to see the answers again. A near miss doesn't reach the terminal. */
@Composable
fun CollapsedPrompt(prompt: ScreenPrompt, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.keepTapsOffTerminal().padding(10.dp).widthIn(max = 320.dp).clip(RoundedCornerShape(50)).background(Rex.blocked)
            .clickable(role = Role.Button, onClick = onExpand)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            prompt.question ?: "Waiting for you", color = Rex.onBlocked, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.ExpandLess, "Show the answers", tint = Rex.onBlocked, modifier = Modifier.size(18.dp))
    }
}

/** The lines the question is about, in the terminal's font: at most four, cut with an ellipsis. */
@Composable
private fun PromptContext(lines: List<String>, modifier: Modifier = Modifier) {
    Text(
        lines.joinToString("\n"), color = Rex.textDim, fontFamily = Rex.mono, fontSize = 12.sp, lineHeight = 16.sp,
        maxLines = 4, overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Rex.bg)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
}

/** An answer. With [cursorOnly], as in a multi-select question, a highlighted option is only outlined. */
@Composable
internal fun OptionButton(option: PromptOption, cursorOnly: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val strong = option.selected && !cursorOnly
    val checked = option.checked
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape)
            .background(if (strong) Rex.accentSoft else Rex.keyBg)
            .border(if (option.selected) 1.dp else 0.5.dp, if (option.selected) Rex.accent else Rex.divider, shape)
            .then(
                // A tick box says whether it is ticked, to screen readers too.
                if (checked != null) Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = { onClick() })
                else Modifier.clickable(role = Role.Button, onClick = onClick),
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null) {
            TickBox(checked)
            Spacer(Modifier.width(11.dp))
        } else option.number?.let { n ->
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(if (strong) Rex.accent else Rex.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$n", color = if (strong) Rex.onAccent else Rex.textDim,
                    fontFamily = Rex.mono, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                )
            }
            Spacer(Modifier.width(11.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(option.label, color = Rex.text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            option.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Rex.textDim, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A ☐ or ☑ the size of the number bubbles, in the card's colors. */
@Composable
private fun TickBox(checked: Boolean) {
    val shape = RoundedCornerShape(7.dp)
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(20.dp).clip(shape)
                .background(if (checked) Rex.accent else Color.Transparent)
                .border(1.5.dp, if (checked) Rex.accent else Rex.textDim, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Outlined.Check, null, tint = Rex.onAccent, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun YesNoButton(option: PromptOption, modifier: Modifier, onClick: () -> Unit) {
    val yes = option.label.trim().startsWith("y", ignoreCase = true)
    Box(
        modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(12.dp))
            .background(if (yes) Rex.accent else Rex.keyBg)
            .border(if (option.selected) 1.dp else 0.dp, if (option.selected) Rex.text else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            option.label, color = if (yes) Rex.onAccent else Rex.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Measures with at most [fraction] of the height on offer, so a card never covers the whole
 * terminal, but [atLeast] of what there is, so a short one still fits what must show.
 */
internal fun Modifier.maxHeightFraction(fraction: Float, atLeast: Dp = 0.dp) = layout { measurable, constraints ->
    val c = if (constraints.hasBoundedHeight) {
        val max = maxOf((constraints.maxHeight * fraction).toInt(), minOf(atLeast.roundToPx(), constraints.maxHeight))
        constraints.copy(maxHeight = max.coerceAtLeast(constraints.minHeight))
    } else constraints
    val p = measurable.measure(c)
    layout(p.width, p.height) { p.place(0, 0) }
}
