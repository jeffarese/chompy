package com.jeffarese.rexmobile.terminal.emulator

import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen is read a changed row at a time, from libghostty-vt's render state, and must read the
 * same as reading every row afresh: text, where each column's text starts, styles and wraps.
 */
class ScreenRowsTest {
    private val e = "\u001b"

    /** One screen row as the view sees it, to compare. */
    private data class Row(val text: String, val starts: List<Int>, val styles: List<Long>, val wraps: Boolean)

    private fun shown(emulator: TerminalEmulator): List<Row> = List(emulator.mRows) { r ->
        val row = emulator.screen.allocateFullLineIfNecessary(r)
        val columns = emulator.mColumns
        Row(
            String(row.mText, 0, row.spaceUsed),
            List(columns + 1) { row.findStartOfColumn(it) },
            List(columns) { row.getStyle(it) },
            row.lineWrap,
        )
    }

    /** Every screen row read afresh, cell by cell, the way history is read. */
    private fun afresh(emulator: TerminalEmulator): List<Row> {
        val rows = emulator.mRows
        val columns = emulator.mColumns
        var text = CharArray(rows * columns)
        val starts = IntArray(rows * (columns + 1))
        val styles = LongArray(rows * columns)
        val wraps = BooleanArray(rows)
        var length = emulator.readRows(0, rows, text, starts, styles, wraps)
        if (length < 0) {
            text = CharArray(-length)
            length = emulator.readRows(0, rows, text, starts, styles, wraps)
        }
        assertTrue(length > 0)
        return List(rows) { r ->
            val at = r * (columns + 1)
            val from = starts[at]
            Row(
                String(text, from, starts[at + columns] - from),
                List(columns + 1) { starts[at + it] - from },
                List(columns) { styles[r * columns + it] },
                wraps[r],
            )
        }
    }

    private fun feedInPieces(t: TestSession, output: String, piece: Int, check: () -> Unit) {
        val bytes = output.toByteArray()
        var at = 0
        while (at < bytes.size) {
            val n = minOf(piece, bytes.size - at)
            t.session.feed(bytes.copyOfRange(at, at + n), n)
            at += n
            check()
        }
    }

    @Test
    fun changedRowsReadAsEveryRowReadAfresh() {
        val t = TestSession(80, 30, transcriptRows = 1000)
        fun same() = assertEquals(afresh(t.emulator), shown(t.emulator))
        // Pieces that split sequences and characters, read after each as a frame would be.
        feedInPieces(t, corpus(), 7) { same() }
        feedInPieces(t, claudeFrames(), 23) { same() }

        // A fullscreen program on the alternate screen, and back.
        feedInPieces(t, "$e[?1049h$e[H$e[2J$e[7m top bar $e[0m\r\n$e[44m$e[K blue rows $e[0m\r\n" + "fullscreen\r\n".repeat(5) + "$e[?1049l", 11) { same() }

        // A resize reflows the screen, and a reset clears it.
        t.emulator.resize(60, 20)
        same()
        feedInPieces(t, corpus(), 13) { same() }
        t.emulator.resize(100, 35)
        same()
        t.session.reset()
        same()
        feedInPieces(t, "after the reset 漢字 🙂\r\n", 5) { same() }
    }

    @Test
    fun whatJoinsACharacterInLaterOutputShows() {
        // Ghostty doesn't mark a row changed when a variation selector or a combining mark joins a
        // character already there.
        val t = TestSession(20, 4)
        t.print("ok ❤")
        assertEquals("ok ❤", t.text(0, 1).single())
        t.print("️\r\nnext e")
        assertEquals("next e", t.text(1, 1).single())
        t.print("́")
        assertEquals(listOf("ok ❤️", "next é"), t.text(0, 2))
    }

    @Test
    fun aSpinnerTurningReadsItsOwnRow() {
        val t = TestSession(60, 40)
        t.print(claudeScreen())
        shown(t.emulator)
        val rows = IntArray(40)
        val text = CharArray(60 * 40 * 2)
        val starts = IntArray(40 * 61)
        val styles = LongArray(40 * 60)
        val wraps = BooleanArray(40)
        assertEquals("nothing changed, nothing read", 0, t.emulator.readScreen(false, rows, text, starts, styles, wraps))

        t.print("$e[37;1H$e[38;5;174m✽$e[0m")
        val read = t.emulator.readScreen(false, rows, text, starts, styles, wraps)
        assertTrue("read $read rows: ${rows.take(read)}", read in 1..2 && 36 in rows.take(read))
        assertEquals(0, t.emulator.readScreen(false, rows, text, starts, styles, wraps))
    }

    @Test
    fun aFrameThatChangesARowMakesNoGarbage() {
        val t = TestSession(60, 40, transcriptRows = 4000)
        t.print(claudeScreen())
        val spinner = listOf("✻", "✽", "✶", "✳", "✢", "·").map { "$e[37;1H$e[38;5;174m$it$e[0m$e[39;5H".toByteArray() }
        fun frame(i: Int) {
            val b = spinner[i % spinner.size]
            t.session.feed(b, b.size)
            val emulator = t.emulator
            emulator.cursorStyle
            for (row in 0 until emulator.mRows) emulator.screen.allocateFullLineIfNecessary(row)
        }
        repeat(200, ::frame)
        val frames = 2000
        val before = allocatedBytes()
        repeat(frames, ::frame)
        val perFrame = (allocatedBytes() - before) / frames
        // Reading every row afresh made about 34 KB of garbage a frame.
        assertTrue("$perFrame bytes a frame", perFrame < 64)
    }

    @Test
    fun theCursorFollowsTheProgramsStyle() {
        val t = TestSession(40, 5)
        t.print("a")
        assertEquals(TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE, t.emulator.cursorStyle)
        t.print("$e[5 q")
        assertEquals(TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR, t.emulator.cursorStyle)
        assertEquals(TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR, t.emulator.cursorStyle)
        t.print("$e[4 q")
        assertEquals(TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE, t.emulator.cursorStyle)
        t.print("$e[0 q")
        assertEquals(TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE, t.emulator.cursorStyle)
    }

    @Test
    fun aFewScreensOfHistoryStayRead() {
        val t = TestSession(150, 40, transcriptRows = 4000)
        t.print((0 until 4200).joinToString("") { "line $it " + "x".repeat(120) + "\r\n" })
        val screen = t.emulator.screen
        val history = screen.activeTranscriptRows
        for (row in -history until 0) screen.allocateFullLineIfNecessary(row)
        assertEquals(TerminalBuffer.HISTORY_KEPT, screen.historyRowsKept())
        // The last line sits in row 38, above the cursor's empty row.
        assertEquals("line 4160 " + "x".repeat(120), t.text(-1, 1).single())
        assertEquals("line ${4161 - history} " + "x".repeat(120), t.text(-history, 1).single())

        // Nothing shows it: every row read goes, and is read again when wanted.
        screen.dropRows()
        assertEquals(0, screen.historyRowsKept())
        assertEquals("line 4199 " + "x".repeat(120), t.text(38, 1).single())
        assertEquals("line 4160 " + "x".repeat(120), t.text(-1, 1).single())
    }

    /** The JVM's own count of what this thread has allocated; java.lang.management isn't in android.jar. */
    private fun allocatedBytes(): Long {
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocated = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        return allocated.invoke(threads, Thread.currentThread().id) as Long
    }

    /** Claude Code's screen: replies, a diff, dim asides, wide characters, a spinner and the input box. */
    private fun claudeScreen(): String {
        val sb = StringBuilder("$e[H$e[2J")
        for (row in 0 until 36) {
            when (row % 4) {
                0 -> sb.append("$e[1;38;5;174m⏺$e[0m Some reply text that runs across the row, row $row")
                1 -> sb.append("  ⎿  $e[48;2;40;80;40m$e[38;2;200;255;200m+ val rows = emulator.screen.activeTranscriptRows$e[0m")
                2 -> sb.append("$e[2m  dim aside $row$e[0m 漢字 🙂 done")
                else -> sb.append("plain line $row with some words in it")
            }
            sb.append("\r\n")
        }
        sb.append("$e[38;5;174m✻ Thinking… $e[2m(esc to interrupt)$e[0m\r\n")
        sb.append("╭" + "─".repeat(58) + "╮\r\n")
        sb.append("│ > $e[7m $e[0m" + " ".repeat(53) + "│\r\n")
        sb.append("╰" + "─".repeat(58) + "╯")
        return sb.toString()
    }

    /** Claude Code at work: its spinner turning in place, a synchronized redraw, and lines scrolling in. */
    private fun claudeFrames(): String {
        val sb = StringBuilder(claudeScreen())
        for (i in 0 until 12) {
            sb.append("$e[37;1H$e[38;5;174m${"✻✽✶✳✢·"[i % 6]}$e[0m$e[39;5H")
            if (i % 4 == 3) sb.append("$e[?2026h$e[30;1H$e[2K$e[1mredrawn $i$e[0m$e[?2026l")
        }
        sb.append("$e[40;1H\r\n" + (0 until 8).joinToString("") { "$e[38;5;174m⏺$e[0m streamed line $it\r\n" })
        return sb.toString()
    }

    /** The hard cases of LiveFidelityBench, and backgrounds that erasing leaves without text. */
    private fun corpus(): String {
        val sb = StringBuilder("$e[H$e[2J")
        fun row(s: String) = sb.append(s).append("$e[60G|\r\n")
        row("emoji zwj: 👨‍👩‍👧‍👦 🏳️‍🌈 🧑‍💻 |")
        row("flags: 🇯🇵 🇪🇸 🇺🇸 |")
        row("skin: 👍🏽 👋🏿 |")
        row("vs16: ❤️ ☀️ ✔️ | text: ❤ ☀ ✔ |")
        row("combining: é ä ñ ộ Z̵ |")
        row("cjk: 日本語の文字 한국어 中文 |")
        sb.append("w".repeat(79) + "中文|\r\n")
        row("tabs:\ta\tb\tc|")
        row("box: ┌──┬──┐ │ab│cd│ └──┴──┘ ╔═╗ ╠╬╣ |")
        row("dec: $e(0lqqwqqk x x mqqvqqj ~ a `$e(B back |")
        sb.append("$e[14;17r$e[14;1H")
        sb.append((1..6).joinToString("\r\n") { "region R$it$e[60G|" })
        sb.append("$e[r$e[18;1H")
        row("IL-A |")
        row("IL-B |")
        row("IL-C |")
        sb.append("$e[19;1H$e[L$e[21;1H$e[M$e[21;1H")
        row("chars: abcdef$e[4D$e[2@$e[4C$e[P|")
        row("rep: Z$e[9b|")
        row("osc8: $e]8;;https://example.com/bench$e\\link$e]8;;$e\\ after |")
        row("ul: $e[4:3mcurly$e[0m $e[4:2mdouble$e[0m $e[58:2::255:0:0m$e[4mred-ul$e[59;24m $e[4:4mdotted$e[0m |")
        row("color: $e[38;2;255;120;0morange$e[0m $e[48;2;0;0;128mon-navy$e[0m $e[38;5;196m196$e[0m $e[1;3;9mbis$e[0m |")
        sb.append("$e[44m$e[2K$e[0m palette background\r\n")
        sb.append("$e[48;2;10;20;30m$e[K$e[0m truecolor background$e[5X\r\n")
        sb.append("$e[7mreverse$e[27m $e[8mhidden$e[28m $e[53moverline$e[55m\r\n")
        sb.append("CORPUS-END\r\n")
        return sb.toString()
    }
}
