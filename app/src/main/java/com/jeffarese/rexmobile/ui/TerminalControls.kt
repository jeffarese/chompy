package com.jeffarese.rexmobile.ui

import android.text.TextUtils
import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import com.jeffarese.rexmobile.terminal.PromptOption
import com.jeffarese.rexmobile.terminal.ScreenPrompt

/** Where the terminal screen's controls send input: the terminal on screen, or a stand-in in tests. */
interface TerminalSink {
    /** A special key, encoded for the modes the program has turned on. */
    fun key(code: Int, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false)

    /** Bytes as if typed. */
    fun bytes(bytes: ByteArray)

    /** [text] typed with ctrl and/or alt held, encoded for the modes the program has turned on. */
    fun typeHeld(text: String, ctrl: Boolean, alt: Boolean, shift: Boolean) = bytes(typedBytes(text, ctrl = ctrl, alt = alt))

    /** Text the way the program expects pastes. */
    fun paste(text: String)

    /** Answer the prompt on screen. */
    fun choose(prompt: ScreenPrompt, option: PromptOption)

    /** Go to another of the tabs over one of Claude's questions. */
    fun goToTab(prompt: ScreenPrompt, index: Int) {}

    /** Answer one of Claude's questions in the user's own words. */
    fun answerOwn(prompt: ScreenPrompt, text: String) {}
}

/**
 * The key bar's sticky shift, ctrl and alt: a tap arms one, and the next key or typed character uses
 * it up. Shift is for the keys a phone keyboard has no shift for, such as Shift+Left or Shift+Tab.
 */
@Stable
class StickyModifiers {
    var shift by mutableStateOf(false)
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    val armed: Boolean get() = shift || ctrl || alt

    fun clear() {
        shift = false
        ctrl = false
        alt = false
    }
}

/** Everything the screen's buttons send, with the sticky modifiers applied to keys and typed characters. */
@Stable
class TerminalControls(private val sink: TerminalSink, val modifiers: StickyModifiers) {
    fun key(code: Int, shift: Boolean = false) {
        sink.key(code, ctrl = modifiers.ctrl, alt = modifiers.alt, shift = shift || modifiers.shift)
        modifiers.clear()
    }

    /** Type [text], such as a key bar symbol or a character from the message box, as a control or meta key if one is armed. */
    fun type(text: String) {
        if (text.isEmpty()) return
        val shifted = if (modifiers.shift) text.uppercase() else text
        if (modifiers.ctrl || modifiers.alt) sink.typeHeld(shifted, modifiers.ctrl, modifiers.alt, modifiers.shift)
        else sink.bytes(typedBytes(shifted))
        modifiers.clear()
    }

    /** Send a message or command: the text as a paste, then Enter. Empty text is just Enter. */
    fun submit(text: String) {
        if (text.isNotEmpty()) sink.paste(text)
        sink.key(KeyEvent.KEYCODE_ENTER)
    }

    fun paste(text: String) = sink.paste(text)

    /** Esc, which stops Claude Code's turn. */
    fun interrupt() = sink.key(KeyEvent.KEYCODE_ESCAPE)

    /** Shift+Tab, which moves Claude Code to its next permission mode. */
    fun cycleMode() = sink.key(KeyEvent.KEYCODE_TAB, shift = true)

    fun choose(prompt: ScreenPrompt, option: PromptOption) = sink.choose(prompt, option)

    fun goToTab(prompt: ScreenPrompt, index: Int) = sink.goToTab(prompt, index)

    fun answerOwn(prompt: ScreenPrompt, text: String) = sink.answerOwn(prompt, text)
}

/** The bytes a terminal gets for [text] typed with ctrl and/or alt held. Enter from a soft keyboard is a newline, sent as CR. */
fun typedBytes(text: String, ctrl: Boolean = false, alt: Boolean = false): ByteArray {
    val s = text.replace('\n', '\r')
    val single = s.isNotEmpty() && Character.charCount(s.codePointAt(0)) == s.length
    val body = if (ctrl && single) controlCode(s.codePointAt(0))?.let { String(Character.toChars(it)) } ?: s else s
    return (if (alt) "\u001b$body" else body).toByteArray()
}

/** The character ctrl+[cp] sends, as Termux and xterm map it, or null if ctrl doesn't change it. */
fun controlCode(cp: Int): Int? = when (cp) {
    in 'a'.code..'z'.code -> cp - 'a'.code + 1
    in '@'.code..'_'.code -> cp and 0x1f
    ' '.code, '2'.code -> 0
    '3'.code -> 27
    '4'.code -> 28
    '5'.code -> 29
    '6'.code -> 30
    '7'.code, '/'.code -> 31
    '8'.code, '?'.code -> 127
    in 0..31, 127 -> cp
    else -> null
}

/**
 * [typed], a character typed into [box] with ctrl or alt armed, lowercased if it is a capital the
 * keyboard most likely added on its own: the box stays empty while a modifier is armed, so the
 * keyboard's sentence capitalisation would turn alt+b into alt+shift+b every time. A capital typed
 * mid-sentence is kept, as the user chose it.
 */
fun uncapitalised(typed: String, box: TextFieldValue): String {
    val autoCaps = TextUtils.getCapsMode(box.text, box.selection.min, TextUtils.CAP_MODE_SENTENCES) != 0
    return if (autoCaps && typed.length == 1 && typed[0].isUpperCase()) typed.lowercase() else typed
}

/** The single character typed at the caret between [old] and [new], or null if the edit was anything else. */
fun insertedChar(old: TextFieldValue, new: TextFieldValue): String? {
    val added = new.text.length - old.text.length
    if (added !in 1..2 || !new.selection.collapsed) return null
    val end = new.selection.start
    val start = end - added
    if (start < 0) return null
    val inserted = new.text.substring(start, end)
    if (inserted.codePointCount(0, inserted.length) != 1) return null
    return if (new.text.removeRange(start, end) == old.text) inserted else null
}
