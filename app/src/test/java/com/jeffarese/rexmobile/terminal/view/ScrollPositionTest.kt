package com.jeffarese.rexmobile.terminal.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scroll arithmetic, against a real emulator, without measuring fonts. */
class ScrollPositionTest {
    private val lineHeight = 30
    private val position = ScrollPosition()

    /** What [TerminalView.onScreenUpdated] does with each batch of output. */
    private fun TestSession.follow(pinned: Boolean = false) {
        position.followOutput(emulator.scrollCounter, history, emulator.screen.maxTranscriptRows, pinned)
        emulator.clearScrollCounter()
    }

    @Test
    fun scrollsByPixelsWithAPartRowAndIsExactlyZeroAtTheBottom() {
        assertEquals(-45f, position.scrollBy(-45f, lineHeight, 50))
        assertEquals(-2, position.topRow)
        assertEquals(15f, position.offsetPx)
        assertEquals(2, position.rowsAboveBottom)

        position.scrollBy(44.5f, lineHeight, 50)
        assertEquals(-1, position.topRow)
        assertEquals(29.5f, position.offsetPx)
        assertEquals(29, position.offsetPixels)

        assertEquals(0.5f, position.scrollBy(100f, lineHeight, 50))
        assertTrue(position.isAtBottom)
        assertEquals(0f, position.offsetPx)
        assertEquals(0, position.rowsAboveBottom)
        assertEquals(0f, position.scrollBy(10f, lineHeight, 50))
    }

    @Test
    fun stopsAtTheOldestLine() {
        assertEquals(-1500f, position.scrollBy(-1e6f, lineHeight, 50))
        assertEquals(-50, position.topRow)
        assertEquals(0f, position.offsetPx)
    }

    @Test
    fun scrolledUpKeepsTheSameLinesInViewAsOutputArrives() {
        val t = TestSession(columns = 20, rows = 5)
        t.printLines(0, 30)
        t.follow()
        position.scrollBy(-(10f * lineHeight + 7), lineHeight, t.history)
        val top = position.topRow
        val offset = position.offsetPx
        val shown = t.text(top)

        t.printLines(30, 7)
        t.follow()

        assertEquals(top - 7, position.topRow)
        assertEquals(offset, position.offsetPx)
        assertEquals(shown, t.text(position.topRow))
    }

    @Test
    fun aRedrawInPlaceDoesNotMoveTheView() {
        val t = TestSession(columns = 20, rows = 5)
        t.printLines(0, 30)
        t.follow()
        position.scrollBy(-3.5f * lineHeight, lineHeight, t.history)
        val top = position.topRow
        // A spinner: the same line rewritten over and over, nothing scrolls.
        repeat(20) { t.print("\r\u001b[2K* Thinking $it") }
        t.follow()
        assertEquals(top, position.topRow)
    }

    @Test
    fun atTheBottomFollowsOutput() {
        val t = TestSession(columns = 20, rows = 5)
        t.printLines(0, 30)
        t.follow()
        t.printLines(30, 12)
        t.follow()
        assertTrue(position.isAtBottom)
        assertEquals(0f, position.offsetPx)
        assertEquals("line 41", t.text(0).last { it.isNotEmpty() })
    }

    @Test
    fun aFullHistoryHoldsAtTheOldestLine() {
        val t = TestSession(columns = 20, rows = 5, transcriptRows = 100)
        t.printLines(0, 200)
        t.follow()
        position.scrollBy(-1e6f, lineHeight, t.history)
        assertEquals(-t.history, position.topRow)

        // The terminal drops its oldest lines a page at a time, once the history is past its limit.
        var next = 200
        var before = t.history
        while (t.history >= before) {
            before = t.history
            t.printLines(next++, 1)
            t.follow()
        }
        assertEquals(-t.history, position.topRow)
        assertEquals(0f, position.offsetPx)
    }

    @Test
    fun aClearedHistoryKeepsTheDistanceFromTheBottom() {
        val t = TestSession(columns = 20, rows = 5)
        t.printLines(0, 60)
        t.follow()
        position.scrollBy(-5f * lineHeight, lineHeight, t.history)
        // A full redraw: clear the screen and history, then print it all again.
        t.print("\u001b[H\u001b[2J\u001b[3J")
        t.printLines(0, 60)
        t.follow()
        assertEquals(-5, position.topRow)
    }

    @Test
    fun selectingPinsTheLinesEvenAtTheBottom() {
        val t = TestSession(columns = 20, rows = 5)
        t.printLines(0, 30)
        t.follow()
        t.printLines(30, 3)
        t.follow(pinned = true)
        assertEquals(-3, position.topRow)
    }

    @Test
    fun aLineHeightChangeLinesUpOnWholeRows() {
        position.scrollBy(-40f, lineHeight, 50)
        position.alignToRow()
        assertEquals(-2, position.topRow)
        assertEquals(0f, position.offsetPx)
    }
}
