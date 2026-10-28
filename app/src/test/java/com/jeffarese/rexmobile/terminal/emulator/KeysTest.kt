package com.jeffarese.rexmobile.terminal.emulator

import android.view.KeyEvent
import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Keys are encoded by libghostty-vt for the program's keyboard mode, as the server's own terminal would send them.
 * The tables also check every key code in [Keys], which a Ghostty that numbers its keys differently would fail.
 */
class KeysTest {
    private val term = TestSession(80, 24)
    private val emulator get() = term.emulator

    private fun key(code: Int, mods: Int = 0) = emulator.encodeKey(code, mods)?.toString(Charsets.UTF_8)
    private fun typed(c: Char, mods: Int) = emulator.encodeCodePoint(c.code, mods)?.toString(Charsets.UTF_8)

    @Test
    fun keysSendXtermSequences() {
        val expected = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to "\u001b[A", KeyEvent.KEYCODE_DPAD_DOWN to "\u001b[B",
            KeyEvent.KEYCODE_DPAD_RIGHT to "\u001b[C", KeyEvent.KEYCODE_DPAD_LEFT to "\u001b[D",
            KeyEvent.KEYCODE_MOVE_HOME to "\u001b[H", KeyEvent.KEYCODE_MOVE_END to "\u001b[F",
            KeyEvent.KEYCODE_PAGE_UP to "\u001b[5~", KeyEvent.KEYCODE_PAGE_DOWN to "\u001b[6~",
            KeyEvent.KEYCODE_INSERT to "\u001b[2~", KeyEvent.KEYCODE_FORWARD_DEL to "\u001b[3~",
            KeyEvent.KEYCODE_DEL to "\u007f", KeyEvent.KEYCODE_ENTER to "\r", KeyEvent.KEYCODE_DPAD_CENTER to "\r",
            KeyEvent.KEYCODE_TAB to "\t", KeyEvent.KEYCODE_ESCAPE to "\u001b",
            KeyEvent.KEYCODE_F1 to "\u001bOP", KeyEvent.KEYCODE_F2 to "\u001bOQ", KeyEvent.KEYCODE_F3 to "\u001bOR",
            KeyEvent.KEYCODE_F4 to "\u001bOS", KeyEvent.KEYCODE_F5 to "\u001b[15~", KeyEvent.KEYCODE_F6 to "\u001b[17~",
            KeyEvent.KEYCODE_F7 to "\u001b[18~", KeyEvent.KEYCODE_F8 to "\u001b[19~", KeyEvent.KEYCODE_F9 to "\u001b[20~",
            KeyEvent.KEYCODE_F10 to "\u001b[21~", KeyEvent.KEYCODE_F11 to "\u001b[23~", KeyEvent.KEYCODE_F12 to "\u001b[24~",
        )
        for ((code, bytes) in expected) assertEquals(KeyEvent.keyCodeToString(code), bytes, key(code))
    }

    @Test
    fun theKeypadTypesWithNumLockAndMovesWithout() {
        val typing = (0..9).associate { KeyEvent.KEYCODE_NUMPAD_0 + it to "$it" } + mapOf(
            KeyEvent.KEYCODE_NUMPAD_ADD to "+", KeyEvent.KEYCODE_NUMPAD_SUBTRACT to "-",
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY to "*", KeyEvent.KEYCODE_NUMPAD_DIVIDE to "/",
            KeyEvent.KEYCODE_NUMPAD_EQUALS to "=", KeyEvent.KEYCODE_NUMPAD_COMMA to ",", KeyEvent.KEYCODE_NUMPAD_DOT to ".",
            KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN to "(", KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN to ")",
            KeyEvent.KEYCODE_NUMPAD_ENTER to "\r",
        )
        for ((code, bytes) in typing) assertEquals(KeyEvent.keyCodeToString(code), bytes, key(code, Keys.NUM_LOCK))
        val moving = listOf("\u001b[2~", "\u001b[F", "\u001b[B", "\u001b[6~", "\u001b[D", "\u001b[E", "\u001b[C", "\u001b[H", "\u001b[A", "\u001b[5~")
        for ((digit, bytes) in moving.withIndex()) assertEquals("numpad $digit", bytes, key(KeyEvent.KEYCODE_NUMPAD_0 + digit))
        assertEquals("\u001b[3~", key(KeyEvent.KEYCODE_NUMPAD_DOT))
    }

    @Test
    fun keysThatTypeTextAreLeftToTheCharacter() {
        assertNull(key(KeyEvent.KEYCODE_A))
        assertNull(key(KeyEvent.KEYCODE_SPACE, Keys.CTRL))
        assertNull(key(KeyEvent.KEYCODE_SHIFT_LEFT, Keys.SHIFT))
    }

    @Test
    fun modifiersGoWithTheKey() {
        assertEquals("\u001b[1;5A", key(KeyEvent.KEYCODE_DPAD_UP, Keys.CTRL))
        assertEquals("\u001b[1;2D", key(KeyEvent.KEYCODE_DPAD_LEFT, Keys.SHIFT))
        assertEquals("\u001b[5;5~", key(KeyEvent.KEYCODE_PAGE_UP, Keys.CTRL))
        assertEquals("\u001b[13;2~", key(KeyEvent.KEYCODE_F3, Keys.SHIFT))
        assertEquals("\u001b[Z", key(KeyEvent.KEYCODE_TAB, Keys.SHIFT))
        assertEquals("\u0008", key(KeyEvent.KEYCODE_DEL, Keys.CTRL))
        assertEquals("\u001b\r", key(KeyEvent.KEYCODE_ENTER, Keys.ALT))
        assertEquals("Shift+Enter, as Ghostty sends it", "\u001b[27;2;13~", key(KeyEvent.KEYCODE_ENTER, Keys.SHIFT))
        assertEquals("num lock alone changes nothing", "\u001b[1;5A", key(KeyEvent.KEYCODE_DPAD_UP, Keys.CTRL or Keys.NUM_LOCK))
    }

    @Test
    fun cursorKeysFollowTheProgramsMode() {
        term.print("\u001b[?1h")
        assertEquals("\u001bOA", key(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("\u001bOH", key(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals("\u001b[1;5A", key(KeyEvent.KEYCODE_DPAD_UP, Keys.CTRL))
        term.print("\u001b[?1l")
        assertEquals("\u001b[A", key(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun ctrlMakesControlCharacters() {
        for (c in ('a'..'z') - setOf('i', 'm')) assertEquals("ctrl+$c", "${(c - 'a' + 1).toChar()}", typed(c, Keys.CTRL))
        assertEquals("caps lock, not shift", "\u0003", typed('C', Keys.CTRL))
        val controls = mapOf(' ' to 0, '2' to 0, '@' to 0, '3' to 27, '4' to 28, '\\' to 28, '5' to 29,
            ']' to 29, '6' to 30, '^' to 30, '7' to 31, '/' to 31, '_' to 31, '8' to 127, '?' to 127)
        for ((c, code) in controls) assertEquals("ctrl+$c", "${code.toChar()}", typed(c, Keys.CTRL))
        // As in Ghostty, after fixterms: these would be Tab, Enter and Esc, which have keys of their own.
        assertEquals("\u001b[105;5u", typed('i', Keys.CTRL))
        assertEquals("\u001b[109;5u", typed('m', Keys.CTRL))
        assertEquals("\u001b[91;5u", typed('[', Keys.CTRL))
        assertNull("a control character is already typed", emulator.encodeCodePoint(3, Keys.CTRL))
    }

    @Test
    fun altPrefixesEscape() {
        assertEquals("\u001bb", typed('b', Keys.ALT))
        assertEquals("\u001bB", typed('B', Keys.ALT or Keys.SHIFT))
        assertEquals("\u001b\u0002", typed('b', Keys.ALT or Keys.CTRL))
        assertEquals("\u001bé", typed('é', Keys.ALT))
    }

    @Test
    fun theKittyProtocolGetsKeysInItsForm() {
        // Claude Code pushes flag 1, disambiguate escape codes.
        term.print("\u001b[>1u")
        assertEquals("\u001b[13;2u", key(KeyEvent.KEYCODE_ENTER, Keys.SHIFT))
        assertEquals("\u001b[13;5u", key(KeyEvent.KEYCODE_ENTER, Keys.CTRL))
        assertEquals("\u001b[27u", key(KeyEvent.KEYCODE_ESCAPE))
        assertEquals("\u001b[99;5u", typed('c', Keys.CTRL))
        assertEquals("\u001b[98;3u", typed('b', Keys.ALT))
        assertEquals("ctrl+shift+a is told apart from ctrl+a", "\u001b[97;6u", typed('A', Keys.CTRL or Keys.SHIFT))
        assertEquals("\u001b[47;6u", typed('?', Keys.CTRL or Keys.SHIFT))
        assertEquals("\u001b[1;5A", key(KeyEvent.KEYCODE_DPAD_UP, Keys.CTRL))
        // Enter, Tab and Backspace stay as they were, and the keypad types.
        assertEquals("\r", key(KeyEvent.KEYCODE_ENTER))
        assertEquals("\t", key(KeyEvent.KEYCODE_TAB))
        assertEquals("\u007f", key(KeyEvent.KEYCODE_DEL))
        assertEquals("7", key(KeyEvent.KEYCODE_NUMPAD_7, Keys.NUM_LOCK))
        // The program leaving pops its flags, and keys are legacy again.
        term.print("\u001b[<u")
        assertEquals("\u001b[27;2;13~", key(KeyEvent.KEYCODE_ENTER, Keys.SHIFT))
        assertEquals("\u0003", typed('c', Keys.CTRL))
    }

    @Test
    fun everyKeyIsTheOneItNames() {
        // Reporting every key with its alternates names the physical key whenever it isn't the character typed,
        // so a key code that is off by one shows up here.
        term.print("\u001b[>13u")
        for (c in "abcdefghijklmnopqrstuvwxyz0123456789`-=[]\\;',./") assertEquals("$c", "\u001b[${c.code}u", typed(c, 0))
        assertEquals("\u001b[97:65;2u", typed('A', Keys.SHIFT))
        assertEquals("\u001b[50:64;2u", typed('@', Keys.SHIFT))
        val functional = mapOf(
            KeyEvent.KEYCODE_SYSRQ to 57361, KeyEvent.KEYCODE_SCROLL_LOCK to 57359, KeyEvent.KEYCODE_BREAK to 57362,
            KeyEvent.KEYCODE_NUM_LOCK to 57360, KeyEvent.KEYCODE_NUMPAD_ENTER to 57414,
        )
        for ((code, number) in functional) assertEquals(KeyEvent.keyCodeToString(code), "\u001b[${number}u", key(code))
    }
}
