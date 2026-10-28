package com.jeffarese.rexmobile.bench

import com.jeffarese.rexmobile.terminal.RemoteTerminal
import com.jeffarese.rexmobile.terminal.ScreenReader
import com.jeffarese.rexmobile.terminal.emulator.TerminalSession
import com.jeffarese.rexmobile.terminal.emulator.TextStyle

/**
 * Everything the live benchmarks ask of the terminal engine, in one place: when the engine changes,
 * only this file does. This one is for libghostty-vt, behind the Termux view's API.
 *
 * Everything else in this package goes through [RemoteTerminal][com.jeffarese.rexmobile.terminal.RemoteTerminal]
 * and its [TerminalSession], which stay the same across engines.
 */
object BenchEngine {
    const val NAME = "libghostty"

    /** The visible screen, top to bottom, each row without trailing spaces; empty before any output. */
    fun visibleLines(session: TerminalSession): List<String> =
        session.emulator?.let { ScreenReader.visibleLines(it) }.orEmpty()

    /** The screen's size as columns to rows, or null before the emulator exists. */
    fun size(session: TerminalSession): Pair<Int, Int>? = session.emulator?.let { it.mColumns to it.mRows }

    /** Rows held above the screen, in the history. */
    fun historyRows(session: TerminalSession): Int = session.emulator?.screen?.activeTranscriptRows ?: 0

    /** Every row the phone holds, history then screen, each without trailing spaces. */
    fun allRows(session: TerminalSession): List<String> {
        val e = session.emulator ?: return emptyList()
        val screen = e.screen
        return (-screen.activeTranscriptRows until e.mRows).map { row -> screen.getSelectedText(0, row, e.mColumns - 1, row).trimEnd() }
    }

    /**
     * Whether each DEC private mode in [numbers] is on, as the engine believes; null for a mode the
     * engine doesn't track. Alternate screen (1049) and synchronized output (2026) come from their getters.
     */
    fun decModes(session: TerminalSession, numbers: List<Int>): Map<Int, Boolean?> {
        val e = session.emulator ?: return numbers.associateWith { null }
        return numbers.associateWith { n ->
            when (n) {
                1049, 1047, 47 -> e.isAlternateBufferActive
                2026 -> e.isSynchronizedUpdateActive
                else -> e.isDecModeSet(n)
            }
        }
    }

    /**
     * The style of the cell at screen [row], [column] (from 0), as the phone draws it. The engine
     * keeps underline colors and hyperlinks too, but the view doesn't show them, so they come back null.
     */
    fun cellStyle(session: TerminalSession, row: Int, column: Int): CellStyle {
        val e = session.emulator ?: return CellStyle()
        val style = e.screen.getStyleAt(row, column)
        val effect = TextStyle.decodeEffect(style)
        fun color(c: Int, default: Int): String? = when {
            c == default -> null
            c and 0xff000000.toInt() == 0xff000000.toInt() -> hex(c)
            c in 0..255 -> "palette:$c"
            else -> null
        }
        return CellStyle(
            fg = color(TextStyle.decodeForeColor(style), TextStyle.COLOR_INDEX_FOREGROUND),
            bg = color(TextStyle.decodeBackColor(style), TextStyle.COLOR_INDEX_BACKGROUND),
            bold = effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0,
            italic = effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0,
            strike = effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0,
            underline = UNDERLINES[TextStyle.decodeUnderlineStyle(effect)],
        )
    }

    private fun hex(c: Int) = "#%06x".format(c and 0xffffff)

    /** Whether the history is still being added after the screen went live: a snapshot's, restored a few pages a frame. */
    fun historyLoading(terminal: RemoteTerminal): Boolean = false

    /** The Kitty keyboard protocol flags in effect, or null if the engine doesn't track them. */
    fun kittyKeyboardFlags(session: TerminalSession): Int? = session.emulator?.kittyKeyboardFlags

    private val UNDERLINES = mapOf(
        TextStyle.UNDERLINE_SINGLE to "single",
        TextStyle.UNDERLINE_DOUBLE to "double",
        TextStyle.UNDERLINE_CURLY to "curly",
        TextStyle.UNDERLINE_DOTTED to "dotted",
        TextStyle.UNDERLINE_DASHED to "dashed",
    )
}
