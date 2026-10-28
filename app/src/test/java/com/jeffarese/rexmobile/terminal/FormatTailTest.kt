package com.jeffarese.rexmobile.terminal

import com.jeffarese.rexmobile.terminal.emulator.TerminalEmulator
import com.jeffarese.rexmobile.terminal.emulator.TerminalOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FormatTailTest {
    private val esc = "\u001b"
    private val opening = "$esc[?1004h$esc[?2004h$esc[3g$esc[9G${esc}H$esc[17G${esc}H$esc[H"

    /**
     * Shaped like Rex's vt redraw: modes and tab stops, then styled lines, each a row padded to the
     * terminal's width, from the row after the cursor's home, then the cursor and character set.
     */
    private fun redraw(lines: Int) = opening + "\n" + (1..lines).joinToString("\n") { n ->
        val text = "line $n ${"·".repeat(n % 20)}"
        "$esc[0m$esc[38;5;${n % 200}m$text$esc[0m$esc[1C$esc[2mend${" ".repeat(WIDTH - text.length - 4)}$esc[0m"
    } + "$esc[3;5H$esc(B"

    @Test
    fun theOpeningIsItsEscapeSequences() {
        assertEquals(opening, FormatTail.preamble(redraw(3)))
        assertEquals("$esc[H", FormatTail.preamble("$esc[H$esc[0m$esc[38;5;1mline"))
        assertEquals("$esc(B$esc[H", FormatTail.preamble("$esc(B$esc[Hplain"))
        assertEquals("", FormatTail.preamble("plain$esc[H"))
        // One cut short isn't taken.
        assertEquals("$esc[H", FormatTail.preamble("$esc[H$esc[?20"))
    }

    @Test
    fun theLastLinesDrawWhatTheWholeRedrawDoes() {
        val full = redraw(3000)
        // Read from partway through a line, as reading from an offset does.
        val tail = full.substring(full.length - full.length / 3)
        val cut = FormatTail.join(FormatTail.preamble(full), tail, lines = 300)!!
        assertEquals(300, cut.count { it == '\n' } + 1 - opening.count { it == '\n' })

        val whole = emulator(full)
        val short = emulator(cut)
        assertEquals(kept(whole), kept(short))
        assertEquals(whole.cursorRow to whole.cursorCol, short.cursorRow to short.cursorCol)
        for (row in -190 until 10) {
            for (col in 0 until WIDTH) assertEquals("style at $row,$col", whole.screen.getStyleAt(row, col), short.screen.getStyleAt(row, col))
        }
    }

    @Test
    fun tooFewLinesIsNoCut() {
        assertNull(FormatTail.join(opening, "partial\nline 1\nline 2", lines = 3))
        assertEquals("$opening$esc[0mline 1\nline 2", FormatTail.join(opening, "partial\nline 1\nline 2", lines = 2))
    }

    @Test
    fun strayBytesCountOnceEach() {
        // Two continuation bytes of a cut character came back as two replacement characters.
        assertEquals(2L + 1, FormatTail.bytesRead("��\n", fromAnyOffset = true))
        assertEquals(6L + 1, FormatTail.bytesRead("��\n", fromAnyOffset = false))
        assertEquals(3L + 1, FormatTail.bytesRead("▀\n", fromAnyOffset = true))
    }

    @Test
    fun aPrefixStandsForTheBytesAsked() {
        assertEquals("a·", FormatTail.prefix("a·b", 3, fromAnyOffset = false))
        // A stray byte of a character cut into counts one.
        assertEquals("��\n", FormatTail.prefix("��\nline", 3, fromAnyOffset = true))
        assertEquals("ab", FormatTail.prefix("ab", 10, fromAnyOffset = false))
    }

    /**
     * A wide terminal's lines are longer than the first read from the end allows for: only the part
     * before it that is missing is read then, and what is drawn is what the whole redraw draws.
     */
    @Test
    fun aWideRedrawIsReadOnceFromNearItsEnd() = runBlocking {
        val width = 200
        // Lines end as the server ends them, and aren't padded: the server leaves out blank cells at the end.
        val full = opening + "\r\n" + (1..3000).joinToString("\r\n") { n ->
            // Claude Code's rules run the width in three-byte characters.
            if (n % 3 == 0) "$esc[0m$esc[2m${"─".repeat(width)}$esc[0m" else "$esc[0m$esc[38;5;${n % 200}mline $n ${"·".repeat(n % 40)}$esc[0m"
        } + "$esc[3;5H$esc(B"
        val bytes = full.toByteArray()
        val head = FormatTail.prefix(full, 2048, fromAnyOffset = false)
        val served = mutableListOf<LongRange>()
        var reads = 0
        // Sized as for a narrower terminal's lines, so it falls short.
        val cut = FormatTail.load(head, bytes.size.toLong(), lines = 300, tailBytes = 300L * (80 + 48)) { from, until, _ ->
            reads++
            serve(bytes, from, until, served)
        }

        assertEquals("the end, then what it lacked", 2, reads)
        // Only the line a read cut into is read again, to join at its end.
        val sorted = served.sortedBy { it.first }
        sorted.zipWithNext { a, b -> assertTrue("$a and $b overlap by more than a line", a.last - b.first < width * 3 + 20) }
        assertTrue("less than the whole", served.sumOf { it.last - it.first + 1 } < bytes.size - head.toByteArray().size)

        val whole = emulator(full, width)
        val short = emulator(cut, width)
        assertEquals(kept(whole), kept(short))
        assertEquals(whole.cursorRow to whole.cursorCol, short.cursorRow to short.cursorCol)
    }

    @Test
    fun aRedrawThatEndsNearItsHeadIsReadWhole() = runBlocking {
        val full = redraw(30)
        val bytes = full.toByteArray()
        val head = FormatTail.prefix(full, 200, fromAnyOffset = false)
        val loaded = FormatTail.load(head, bytes.size.toLong(), lines = 300, tailBytes = 64L * 1024) { from, until, _ -> serve(bytes, from, until, mutableListOf()) }
        assertEquals(full, loaded)
    }

    /**
     * Serves [bytes] from [from] to at least [until], as blob_read does: in parts that never end
     * inside a character, where a start inside one comes back as a replacement character per byte.
     */
    private fun serve(bytes: ByteArray, from: Long, until: Long, served: MutableList<LongRange>): String {
        val sb = StringBuilder()
        var at = from.toInt()
        while (at < until && at < bytes.size) {
            var end = minOf(bytes.size, at + 50_000, until.toInt().coerceAtLeast(at + 1))
            while (end < bytes.size && bytes[end].toInt() and 0xc0 == 0x80) end++
            served += at.toLong() until end.toLong()
            sb.append(String(bytes, at, end - at, Charsets.UTF_8))
            at = end
        }
        return sb.toString()
    }

    /** [width] by 10, keeping 200 rows: the 300 lines kept are more than it holds, as on the phone. */
    /** The screen and the last of the history, which a cut to 300 lines holds as the whole redraw does. */
    private fun kept(emulator: TerminalEmulator) = emulator.screen.getSelectedText(0, -190, emulator.mColumns, emulator.mRows - 1)

    /** Room for all of a redraw: the terminal drops history a page of rows at a time, so a smaller limit keeps a different amount of each. */
    private fun emulator(vt: String, width: Int = WIDTH) = TerminalEmulator(NullOutput, width, 10, 10_000, null).also {
        val b = vt.toByteArray()
        it.append(b, b.size)
    }

    private companion object {
        const val WIDTH = 40
    }

    private object NullOutput : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
}
