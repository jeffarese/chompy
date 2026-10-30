package com.jeffarese.rexmobile.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a key does besides send itself: hold a modifier for the next key, or paste. */
private enum class Special { SHIFT, CTRL, ALT, PASTE }

private data class BarKey(
    val label: String,
    val code: Int? = null,
    val text: String? = null,
    val repeat: Boolean = false,
    val shift: Boolean = false,
    val description: String? = null,
    val special: Special? = null,
    /** What Settings saves it as: the label, which hidden keys were always saved under. */
    val id: String = label,
)

private val SHIFT_TAB = BarKey("⇧⇥", KeyEvent.KEYCODE_TAB, shift = true, description = "Shift Tab")

/**
 * Every key, in the bar's own order. What stops a program comes first, as the first two stay put;
 * then the keys that move around menus and lists, so a phone-width bar shows them without scrolling.
 */
private val ALL_KEYS = listOf(
    BarKey("esc", KeyEvent.KEYCODE_ESCAPE, description = "Escape"),
    BarKey("^C", text = "\u0003", description = "Control C"),
    BarKey("↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true, description = "Up"),
    BarKey("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true, description = "Down"),
    BarKey("←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true, description = "Left"),
    BarKey("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true, description = "Right"),
    BarKey("⏎", KeyEvent.KEYCODE_ENTER, description = "Enter"),
    BarKey("tab", KeyEvent.KEYCODE_TAB),
    SHIFT_TAB,
    BarKey("⌫", KeyEvent.KEYCODE_DEL, repeat = true, description = "Backspace"),
    BarKey("⇧", description = "Shift", special = Special.SHIFT),
    BarKey("ctrl", special = Special.CTRL),
    BarKey("alt", special = Special.ALT),
    BarKey("/", text = "/"),
    BarKey("|", text = "|"),
    BarKey("~", text = "~"),
    BarKey("-", text = "-"),
    BarKey("^D", text = "\u0004", description = "Control D"),
    BarKey("^R", text = "\u0012", description = "Control R"),
    BarKey("^Z", text = "\u001a", description = "Control Z"),
    BarKey("^L", text = "\u000c", description = "Control L"),
    BarKey("pgup", KeyEvent.KEYCODE_PAGE_UP, repeat = true),
    BarKey("pgdn", KeyEvent.KEYCODE_PAGE_DOWN, repeat = true),
    BarKey("home", KeyEvent.KEYCODE_MOVE_HOME),
    BarKey("end", KeyEvent.KEYCODE_MOVE_END),
    BarKey("paste", description = "Paste", special = Special.PASTE),
)

private val KEYS_BY_ID = ALL_KEYS.associateBy { it.id }

/** How many of the bar's first keys stay put while the rest scroll. */
const val PINNED_KEYS = 2

/** Every key the bar can show, in its own order, for Settings to arrange. */
val KEY_BAR_IDS: List<String> = ALL_KEYS.map { it.id }

/** What Settings shows for a key: its label, and what it is when that isn't plain. */
fun keyBarLabel(id: String): String = KEYS_BY_ID[id]?.let { k -> k.description?.takeIf { it != k.label }?.let { "${k.label}  ·  $it" } ?: k.label } ?: id

/**
 * Keys a phone keyboard lacks: [keys], in order, of [KEY_BAR_IDS]. The first [PINNED_KEYS] stay
 * put at the start; the rest scroll.
 * Without [shiftTab] the bar leaves out Shift+Tab, as when the Claude strip's mode chip sends it.
 * With [onToggleStrip], a button beside the keyboard's shows or hides Claude's strip.
 * Unless [enabled], as while the screen isn't known to be current, the keys are dimmed and send
 * nothing; the keyboard and strip buttons still work.
 */
@Composable
fun KeyBar(
    controls: TerminalControls,
    directTyping: Boolean,
    onPaste: () -> Unit,
    onToggleTyping: () -> Unit,
    shiftTab: Boolean = true,
    keys: List<String> = KEY_BAR_IDS,
    stripShown: Boolean = false,
    onToggleStrip: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberViewHaptics()
    val mods = controls.modifiers
    fun press(k: BarKey) {
        haptics.keyTap()
        when (k.special) {
            Special.SHIFT -> mods.shift = !mods.shift
            Special.CTRL -> mods.ctrl = !mods.ctrl
            Special.ALT -> mods.alt = !mods.alt
            Special.PASTE -> onPaste()
            null -> if (k.code != null) controls.key(k.code, shift = k.shift) else controls.type(k.text.orEmpty())
        }
    }
    @Composable
    fun cap(k: BarKey) {
        val active = when (k.special) {
            Special.SHIFT -> mods.shift
            Special.CTRL -> mods.ctrl
            Special.ALT -> mods.alt
            else -> false
        }
        if (k.special == Special.PASTE) {
            KeyCap(description = k.description, enabled = enabled, onPress = { press(k) }, onRefused = haptics::reject) {
                Icon(Icons.Outlined.ContentPaste, null, tint = Rex.text, modifier = Modifier.size(18.dp))
            }
        } else {
            KeyCap(
                k.label, active = active, sticky = k.special in STICKY, repeat = k.repeat, description = k.description, enabled = enabled,
                onRefused = haptics::reject,
            ) { press(k) }
        }
    }
    val shown = keys.mapNotNull { KEYS_BY_ID[it] }.filter { shiftTab || it != SHIFT_TAB }
    val pinned = shown.take(PINNED_KEYS)
    val rest = shown.drop(PINNED_KEYS)
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (pinned.isNotEmpty()) {
            Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pinned.forEach { cap(it) }
            }
            if (rest.isNotEmpty()) Box(Modifier.padding(horizontal = 7.dp).width(1.dp).height(22.dp).background(Rex.divider))
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = if (pinned.isEmpty()) 8.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rest.forEach { cap(it) }
            Spacer(Modifier.width(4.dp))
        }
        if (onToggleStrip != null) {
            IconButton(onClick = onToggleStrip) {
                Icon(
                    Icons.Outlined.Tune,
                    if (stripShown) "Hide Claude's controls" else "Show Claude's controls",
                    tint = if (stripShown) Rex.accent else Rex.text,
                )
            }
        }
        IconButton(onClick = onToggleTyping) {
            Icon(
                if (directTyping) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard,
                if (directTyping) "Use the message box" else "Type straight into the terminal",
                tint = if (directTyping) Rex.accent else Rex.text,
            )
        }
    }
}

/** The keys held for the next key, which say whether they are on. */
private val STICKY = setOf(Special.SHIFT, Special.CTRL, Special.ALT)

@Composable
private fun KeyCap(
    label: String,
    active: Boolean = false,
    sticky: Boolean = false,
    repeat: Boolean = false,
    description: String? = null,
    enabled: Boolean = true,
    onRefused: () -> Unit = {},
    onPress: () -> Unit,
) = KeyCap(active, sticky, repeat, description, enabled, onPress, onRefused) {
    Text(label, color = if (active) Rex.accent else Rex.text, fontFamily = Rex.mono, fontSize = 13.sp)
}

@Composable
private fun KeyCap(
    active: Boolean = false,
    sticky: Boolean = false,
    repeat: Boolean = false,
    description: String? = null,
    enabled: Boolean = true,
    onPress: () -> Unit,
    onRefused: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val press by rememberUpdatedState(onPress)
    val refused by rememberUpdatedState(onRefused)
    val touch = Modifier
        // One key to a screen reader, its label and state together.
        .semantics(mergeDescendants = true) {
            description?.let { contentDescription = it }
            // A held modifier is a switch, on until the next key uses it.
            if (sticky) {
                role = Role.Switch
                toggleableState = ToggleableState(active)
            } else role = Role.Button
            if (!enabled) disabled()
            onClick { if (enabled) press(); enabled }
        }
        .pointerInput(repeat, enabled) {
            // A dimmed key sends nothing, and says so.
            if (!enabled) return@pointerInput detectTapGestures(onTap = { refused() })
            // Whether this touch's hold has started repeating, so its release doesn't press once more.
            var repeated = false
            // A key fires on tap, not on touch, so scrolling the bar types nothing.
            // Holding a repeating key still keeps it going until release.
            detectTapGestures(
                onPress = {
                    pressed = true
                    repeated = false
                    val job = if (repeat) scope.launch {
                        delay(400)
                        repeated = true
                        while (true) {
                            press()
                            delay(55)
                        }
                    } else null
                    // Also when the key is disabled mid-hold, which cancels this gesture.
                    try {
                        tryAwaitRelease()
                    } finally {
                        job?.cancel()
                        pressed = false
                    }
                },
                onTap = { if (!repeated) press() },
            )
        }
    // Drawn to fit the bar, and taking touches over the bar's height and the gaps beside it.
    Box(
        Modifier.reaching(touch)
            .height(38.dp).widthIn(min = 42.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(9.dp))
            .background(if (active || pressed) Rex.keyActive else Rex.keyBg)
            .border(0.5.dp, if (active) Rex.accent else Color.Transparent, RoundedCornerShape(9.dp))
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
