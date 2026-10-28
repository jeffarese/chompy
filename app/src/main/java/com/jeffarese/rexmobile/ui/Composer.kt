package com.jeffarese.rexmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The message box. Send pastes the text and presses Enter; with nothing typed it presses Enter,
 * or stops the program while it [working].
 *
 * While [menuOpen] (a menu is on screen), Send with text typed does nothing: the menu would ignore
 * the paste and the Enter would pick its highlighted option. The empty box's Enter still works.
 *
 * Unless [enabled], as while the screen isn't known to be current, Send does nothing and the text
 * stays in the box: what it sends could land on a menu that turned up meanwhile, or be lost.
 *
 * A character typed while the key bar's ctrl or alt is armed goes straight to the terminal as
 * that key, the way it would on a desktop, instead of landing in the box.
 *
 * For a [shell] the keyboard leaves case and spelling alone, as commands aren't sentences.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    hint: String,
    working: Boolean,
    controls: TerminalControls,
    onSend: (String) -> Unit,
    menuOpen: Boolean = false,
    enabled: Boolean = true,
    shell: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val haptic = LocalHapticFeedback.current
    // Once Back has put the keyboard away, the box lets go of focus, so its cursor stops blinking.
    // The keyboard briefly hides when another view takes it over, so only a hide that lasts counts.
    val focusManager = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    var focused by remember { mutableStateOf(false) }
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeSeen = true
        else if (imeSeen) {
            delay(IME_HIDE_SETTLE_MS)
            imeSeen = false
            if (focused) focusManager.clearFocus()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(Rex.bg)
                .border(0.5.dp, Rex.divider, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 11.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.text.isEmpty()) Text(hint, color = Rex.textFaint, fontSize = 15.sp, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = { new ->
                    val typed = if (controls.modifiers.armed && enabled) insertedChar(value, new) else null
                    if (typed != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        controls.type(uncapitalised(typed, value))
                        // Leave the box as it was; ending the composition resets the keyboard's view of it too.
                        onValueChange(value.copy(composition = null))
                    } else onValueChange(new)
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Rex.text, fontSize = 15.sp),
                cursorBrush = SolidColor(Rex.accent),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(
                    capitalization = if (shell) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                    autoCorrectEnabled = !shell,
                    imeAction = ImeAction.Default,
                ),
                modifier = Modifier.fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = "Message" },
            )
        }
        Spacer(Modifier.width(6.dp))
        val text = value.text
        val stop = text.isEmpty() && working
        val held = (text.isNotEmpty() && menuOpen) || !enabled
        Box(
            Modifier.size(44.dp).alpha(if (held) 0.4f else 1f).clip(CircleShape)
                .background(
                    when {
                        text.isNotEmpty() -> Rex.accent
                        stop -> Rex.text
                        else -> Rex.keyBg
                    },
                )
                .clickable(enabled = !held) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (stop) controls.interrupt() else onSend(text)
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                stop -> Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Rex.bg).semantics { contentDescription = "Stop" })
                text.isEmpty() -> Icon(Icons.AutoMirrored.Outlined.KeyboardReturn, "Press Enter", tint = Rex.text, modifier = Modifier.size(20.dp))
                else -> Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = Rex.onAccent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** How long the keyboard must stay hidden before the message box lets go of focus. */
private const val IME_HIDE_SETTLE_MS = 300L
