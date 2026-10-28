package com.jeffarese.rexmobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Claude Code's effort levels, for `/effort <level>`. */
val EFFORT_LEVELS = listOf("low", "medium", "high", "xhigh", "max", "auto")

private val EFFORT_COMMAND = Regex("""^/effort\s+(\S+)\s*$""")

/** The level a message such as "/effort high" sets, if it is one. */
fun effortSetBy(message: String): String? = EFFORT_COMMAND.matchEntire(message.trim())?.groupValues?.get(1)?.takeIf { it in EFFORT_LEVELS }

/** A Claude Code command, and whether it is usually given arguments (so tapping it fills the box instead of sending). */
data class SlashCommand(val name: String, val takesArgs: Boolean = false)

val CLAUDE_COMMANDS = listOf(
    SlashCommand("effort", takesArgs = true),
    SlashCommand("model"),
    SlashCommand("compact"),
    SlashCommand("clear"),
    SlashCommand("context"),
    SlashCommand("resume"),
    SlashCommand("rewind"),
    SlashCommand("review"),
    SlashCommand("init"),
    SlashCommand("memory"),
    SlashCommand("usage"),
    SlashCommand("cost"),
    SlashCommand("todos"),
    SlashCommand("export"),
    SlashCommand("add-dir", takesArgs = true),
    SlashCommand("agents"),
    SlashCommand("mcp"),
    SlashCommand("hooks"),
    SlashCommand("permissions"),
    SlashCommand("config"),
    SlashCommand("status"),
    SlashCommand("doctor"),
    SlashCommand("help"),
    SlashCommand("exit"),
)

/** The strip's permission-mode chip, as Settings saves it. */
const val STRIP_MODE = "mode"

/** The strip's effort chip, as Settings saves it. */
const val STRIP_EFFORT = "effort"

/** Everything the Claude strip can show: the mode and effort chips, and each command that needs no arguments. */
val STRIP_IDS: List<String> = listOf(STRIP_MODE, STRIP_EFFORT) + CLAUDE_COMMANDS.filterNot { it.takesArgs }.map { "/${it.name}" }

/** What the strip shows until the user arranges it. */
val DEFAULT_STRIP = listOf(STRIP_MODE, STRIP_EFFORT, "/model", "/compact", "/context", "/usage", "/resume", "/rewind", "/clear")

/** What Settings shows for a strip item. */
fun stripLabel(id: String): String = when (id) {
    STRIP_MODE -> "Permission mode"
    STRIP_EFFORT -> "Effort"
    else -> id
}

/** The permission mode as the strip's chip shows it: "auto mode" is "auto", to save room. */
fun shortMode(mode: String): String = mode.removeSuffix(" mode")

/** Every command, as suggestions: the ones that take arguments go in the box to finish. */
val ALL_COMMANDS: List<Suggestion> = CLAUDE_COMMANDS.map { it.suggestion() }

private fun SlashCommand.suggestion() = Suggestion("/$name", if (takesArgs) "/$name " else "/$name", send = !takesArgs)

/** A suggestion for the message box: [send] it as is, or put it in the box to finish. */
data class Suggestion(val label: String, val text: String, val send: Boolean)

/**
 * Suggestions for what is in the message box, or null when it isn't a command being typed:
 * command names while typing "/co", and levels after "/effort ".
 */
fun slashSuggestions(text: String): List<Suggestion>? {
    if (!text.startsWith("/")) return null
    val body = text.substring(1)
    val space = body.indexOfFirst { it.isWhitespace() }
    val found = if (space < 0) {
        val q = body.lowercase()
        val starts = CLAUDE_COMMANDS.filter { it.name.startsWith(q) }
        val contains = if (q.isEmpty()) emptyList() else CLAUDE_COMMANDS.filter { q in it.name && it !in starts }
        (starts + contains).map { it.suggestion() }
    } else if (body.substring(0, space) == "effort") {
        val arg = body.substring(space).trim().lowercase()
        (EFFORT_LEVELS + listOf("ultracode on", "ultracode off"))
            .filter { it.startsWith(arg) }
            .map { Suggestion(it, "/effort $it", send = true) }
    } else emptyList()
    return found.takeIf { it.isNotEmpty() }
}

/** What a disabled chip says it is waiting for. */
private const val ANSWER_FIRST = "Answer the prompt first"

/**
 * Claude Code's controls: its permission mode (tap for the next one), its effort level (tap to pick),
 * and the commands most worth a tap; [items] of [STRIP_IDS], in the order the user put them.
 *
 * While a menu is on screen they are dimmed and do nothing ([enabled] false): Shift+Tab and the
 * Enter after a command would answer the menu (Shift+Tab is "Yes, and accept edits" in a permission
 * prompt). The strip keeps its height, so the terminal above doesn't resize. [disabledReason] says
 * what else they wait for, if not the menu.
 */
@Composable
fun ClaudeBar(
    mode: String?,
    effort: String?,
    onCycleMode: () -> Unit,
    onEffort: (String) -> Unit,
    onCommand: (String) -> Unit,
    enabled: Boolean = true,
    disabledReason: String? = null,
    expanded: Boolean = false,
    onExpand: (() -> Unit)? = null,
    items: List<String> = DEFAULT_STRIP,
) {
    val whyNot = disabledReason ?: ANSWER_FIRST
    val haptic = LocalHapticFeedback.current
    var effortMenu by remember { mutableStateOf(false) }
    // A prompt that turns up while the menu is open closes it for good, rather than until it's answered.
    LaunchedEffect(enabled) { if (!enabled) effortMenu = false }
    StripRow(expanded, onExpand) {
        items.forEach { id ->
            when (id) {
                STRIP_MODE -> ModeChip(mode, enabled, whyNot) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onCycleMode() }
                STRIP_EFFORT -> Box {
                    EffortChip(effort, enabled, whyNot) { effortMenu = true }
                    DropdownMenu(expanded = effortMenu && enabled, onDismissRequest = { effortMenu = false }) {
                        Text("Effort", color = Rex.textDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        EFFORT_LEVELS.forEach { level ->
                            DropdownMenuItem(
                                text = { Text(level) },
                                leadingIcon = { EffortMeter(level, Modifier.size(width = 18.dp, height = 12.dp)) },
                                trailingIcon = { if (level == effort) Icon(Icons.Outlined.Check, "Current", tint = Rex.accent) },
                                onClick = {
                                    effortMenu = false
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onEffort(level)
                                },
                            )
                        }
                        HorizontalDivider(color = Rex.divider)
                        listOf("on", "off").forEach { v ->
                            DropdownMenuItem(text = { Text("ultracode $v") }, onClick = {
                                effortMenu = false
                                onCommand("/effort ultracode $v")
                            })
                        }
                    }
                }
                else -> Chip(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onCommand(id) },
                    description = if (enabled) null else "$id. $whyNot",
                    enabled = enabled,
                ) {
                    Text(id, color = Rex.text, fontFamily = Rex.mono, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

/** The permission mode, in its own color as Claude Code shows it: tap for the next one. */
@Composable
private fun ModeChip(mode: String?, enabled: Boolean, whyNot: String, onClick: () -> Unit) {
    val color = modeColor(mode)
    Chip(
        onClick = onClick,
        description = "Permission mode: ${mode ?: "unknown"}. " + if (enabled) "Tap for the next mode" else whyNot,
        enabled = enabled,
        tint = color.takeIf { mode != null },
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            mode?.let(::shortMode) ?: "mode", color = if (mode != null) color else Rex.textDim,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
        Icon(Icons.Outlined.Sync, null, tint = color.copy(alpha = 0.7f), modifier = Modifier.padding(start = 5.dp).size(12.dp))
    }
}

/** The effort level, with a meter filled as far as it goes: tap to pick another. */
@Composable
private fun EffortChip(effort: String?, enabled: Boolean, whyNot: String, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        description = "Effort: ${effort ?: "unknown"}. " + if (enabled) "Tap to change" else whyNot,
        enabled = enabled,
        tint = Rex.accent.takeIf { effort != null },
    ) {
        EffortMeter(effort, Modifier.size(width = 15.dp, height = 11.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            effort ?: "effort", color = if (effort != null) Rex.text else Rex.textDim,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
        Icon(Icons.Outlined.ExpandMore, null, tint = Rex.textDim, modifier = Modifier.padding(start = 2.dp).size(14.dp))
    }
}

/** Rising bars, as many lit as [effort] is high; for "auto" or none, all of them faint. */
@Composable
private fun EffortMeter(effort: String?, modifier: Modifier) {
    val bars = EFFORT_LEVELS.size - 1
    val lit = EFFORT_LEVELS.indexOf(effort).let { if (it in 0 until bars) it + 1 else 0 }
    val auto = effort == "auto"
    Canvas(modifier) {
        val gap = size.width / (bars * 3 - 1)
        val w = gap * 2
        for (i in 0 until bars) {
            val h = size.height * (i + 2) / (bars + 1)
            val on = i < lit
            drawRoundRect(
                color = when {
                    on -> Rex.accent
                    auto -> Rex.accent.copy(alpha = 0.45f)
                    else -> Rex.textFaint.copy(alpha = 0.6f)
                },
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 3),
            )
        }
    }
}

/**
 * Matching commands while a command is typed in the message box, in the Claude strip's place.
 * Unless [sendEnabled], the ones that send at once are dimmed and do nothing, as in [ClaudeBar].
 */
@Composable
fun SlashSuggestions(
    suggestions: List<Suggestion>,
    sendEnabled: Boolean = true,
    expanded: Boolean = false,
    onExpand: (() -> Unit)? = null,
    onPick: (Suggestion) -> Unit,
) {
    StripRow(expanded, onExpand) {
        suggestions.forEach { s -> SuggestionChip(s, sendEnabled, onPick) }
    }
}

/**
 * The commands the strip has room for only one row of, wrapped to show them all at once: those
 * matching what is typed, else every command. It opens over the terminal rather than pushing it up,
 * so a terminal sized to the phone doesn't resize for it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandPanel(suggestions: List<Suggestion>, sendEnabled: Boolean, onPick: (Suggestion) -> Unit, modifier: Modifier = Modifier) {
    // A card like the prompt's, which floats over the terminal in the same place.
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().padding(8.dp).shadow(14.dp, shape).clip(shape).background(Rex.surface).border(0.5.dp, Rex.divider, shape)
            .heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(12.dp),
    ) {
        Text("Commands", color = Rex.textDim, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.forEach { s -> SuggestionChip(s, sendEnabled, onPick) }
        }
    }
}

/** A suggestion to tap. Unless [sendEnabled], one that sends at once is dimmed and does nothing. */
@Composable
private fun SuggestionChip(s: Suggestion, sendEnabled: Boolean, onPick: (Suggestion) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Chip(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPick(s) },
        highlight = s.send,
        enabled = sendEnabled || !s.send,
    ) {
        Text(
            s.label, color = Rex.text, fontSize = 12.sp, maxLines = 1,
            fontFamily = if (s.label.startsWith("/")) Rex.mono else FontFamily.Default,
        )
    }
}

/** A strip of chips that scrolls sideways, and with [onExpand] a button kept at its end to show all commands. */
@Composable
private fun StripRow(expanded: Boolean, onExpand: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(PaddingValues(horizontal = 8.dp)),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (onExpand != null) {
            Box(Modifier.width(0.5.dp).height(22.dp).background(Rex.divider))
            Box(
                Modifier.size(40.dp).clickable(role = Role.Button, onClick = onExpand)
                    .semantics { contentDescription = if (expanded) "Hide all commands" else "Show all commands" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp, null,
                    tint = if (expanded) Rex.accent else Rex.textDim, modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun Chip(
    onClick: () -> Unit,
    description: String? = null,
    highlight: Boolean = false,
    enabled: Boolean = true,
    /** A color the chip takes on, faintly, to stand apart from the commands. */
    tint: Color? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.height(30.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(50))
            .background(
                when {
                    tint != null -> tint.copy(alpha = 0.14f)
                    highlight -> Rex.accentSoft
                    else -> Rex.keyBg
                },
            )
            .border(
                if (tint != null) 1.dp else 0.5.dp,
                when {
                    tint != null -> tint.copy(alpha = 0.55f)
                    highlight -> Rex.accent.copy(alpha = 0.5f)
                    else -> Rex.divider
                },
                RoundedCornerShape(50),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The colors Claude Code gives its permission modes. */
private fun modeColor(mode: String?): Color {
    val m = mode?.lowercase() ?: return Rex.textFaint
    return when {
        "plan" in m -> Rex.planMode
        "edit" in m -> Rex.acceptEdits
        "bypass" in m || "danger" in m -> Rex.bypassMode
        "auto" in m -> Rex.accent
        else -> Rex.textDim
    }
}
