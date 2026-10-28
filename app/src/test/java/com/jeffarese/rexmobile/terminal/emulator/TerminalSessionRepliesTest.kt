package com.jeffarese.rexmobile.terminal.emulator

import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The server's own terminal answers queries in the output. Answers from here would reach the program
 * a second time, later, as if typed, so the terminal here gives none.
 */
class TerminalSessionRepliesTest {
    private val term = TestSession(40, 5)

    private val queries = listOf(
        "\u001b[c", // DA1
        "\u001b[>c", // DA2
        "\u001b[6n", // Cursor position.
        "\u001b[?6n", // Extended cursor position.
        "\u001b]11;?\u001b\\", // Background color.
        "\u001b]10;?\u0007", // Foreground color.
        "\u001b[?2026\$p", // DECRQM.
        "\u001b[18t", // Window size.
        "\u001bP+q544e\u001b\\", // XTGETTCAP.
        "\u001b[?u", // Kitty keyboard flags.
    )

    @Test
    fun answersToQueriesInTheOutputAreNotSent() {
        queries.forEach { term.print(it) }
        assertEquals("", term.typed.toString())
    }

    @Test
    fun inputAfterOutputStillGoesThrough() {
        term.print("\u001b[c\u001b[6n\u001b]11;?\u001b\\")
        term.session.writeCodePoint(false, 'a'.code)
        assertEquals("a", term.typed.toString())

        term.print("\u001b[?1000h\u001b[?1006h\u001b[?2004h")
        term.typed.reset()
        term.emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 3, 2, true)
        assertEquals("\u001b[<0;3;2M", term.typed.toString())

        term.typed.reset()
        term.emulator.paste("hi")
        assertEquals("\u001b[200~hi\u001b[201~", term.typed.toString())
    }
}
