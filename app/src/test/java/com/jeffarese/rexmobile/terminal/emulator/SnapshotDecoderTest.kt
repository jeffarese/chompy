package com.jeffarese.rexmobile.terminal.emulator

import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stream's snapshot restores the terminal it was taken of: the screen once that part has arrived,
 * then the history. The fixture is the snapshot a Rex server sends of a terminal fed
 * snapshots/history.vt, which scripts/snapshot-fixture/make.sh writes again for a new Ghostty.
 */
class SnapshotDecoderTest {
    private val snapshot = resource("snapshots/history.snap")
    private val direct = TestSession(120, 30, transcriptRows = 10_000).apply {
        val vt = resource("snapshots/history.vt")
        session.feed(vt, vt.size)
    }

    @Test
    fun theScreenShowsBeforeTheHistoryArrives() {
        val restored = TestSession(120, 30, transcriptRows = 10_000)
        SnapshotDecoder().use { decoder ->
            var shownAt = -1
            var at = 0
            while (at < snapshot.size) {
                val n = minOf(1024, snapshot.size - at)
                val state = decoder.feed(snapshot, at, n)
                at += n
                if (state >= SnapshotDecoder.READY && shownAt < 0) {
                    assertTrue(restored.session.showSnapshot(decoder))
                    shownAt = at
                    assertEquals(direct.text(0), restored.text(0))
                    assertEquals(direct.emulator.cursorRow to direct.emulator.cursorCol, restored.emulator.cursorRow to restored.emulator.cursorCol)
                }
            }
            assertEquals(SnapshotDecoder.COMPLETE, decoder.state())
            assertTrue("shown after $shownAt of ${snapshot.size} bytes", shownAt in 1 until snapshot.size / 2)

            var restoredRows = 0
            while (true) {
                val rows = restored.session.restoreHistory(decoder, 1)
                if (rows < 0) {
                    assertEquals(-1, rows)
                    break
                }
                restoredRows += rows
            }
            assertTrue(restoredRows > 0)
            assertEquals(direct.history, restored.history)
            assertEquals(direct.emulator.screen.transcriptText, restored.emulator.screen.transcriptText)
            for (row in -direct.history until 30) {
                for (column in 0 until 120) {
                    assertEquals("style at $row,$column", direct.emulator.screen.getStyleAt(row, column), restored.emulator.screen.getStyleAt(row, column))
                }
            }
        }
    }

    @Test
    fun theModesTitleAndColorsCarryOver() {
        val restored = restore(TestSession(120, 30))
        val e = restored.emulator
        assertTrue(e.isBracketedPasteMode)
        assertTrue(e.isMouseTrackingActive)
        assertEquals(1, e.kittyKeyboardFlags)
        assertEquals("snapshot fixture", e.title)
        assertEquals(0xff102030.toInt(), e.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])

        // Reset by the program, the background is the scheme's again.
        restored.print("\u001b]111\u001b\\")
        assertEquals(TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND], e.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
    }

    @Test
    fun outputCarriesOnFromTheRestoredScreen() {
        val restored = restore(TestSession(120, 30, transcriptRows = 10_000))
        val more = "\r\nmore output 漢字\r\n$ "
        direct.print(more)
        restored.print(more)
        assertEquals(direct.text(0), restored.text(0))
    }

    @Test
    fun anotherFormatFailsAtItsFirstByte() {
        SnapshotDecoder().use { assertEquals(SnapshotDecoder.FAILED, it.feed("SNAPSHOT".toByteArray(), 0, 1)) }
    }

    @Test
    fun anotherVersionOfTheFormatFails() {
        val next = snapshot.copyOf().also { it[8] = 2 }
        SnapshotDecoder().use { assertEquals(SnapshotDecoder.FAILED, it.feed(next, 0, 64)) }
    }

    @Test
    fun aDamagedScreenIsNotShown() {
        val damaged = snapshot.copyOf().also { it[40] = (it[40] + 1).toByte() }
        val t = TestSession(120, 30)
        t.print("what was there")
        val before = t.emulator
        SnapshotDecoder().use { decoder ->
            decoder.feed(damaged, 0, damaged.size)
            assertFalse(t.session.showSnapshot(decoder))
            assertEquals(SnapshotDecoder.FAILED, decoder.state())
        }
        assertSame(before, t.emulator)
        assertEquals("what was there", t.text(0)[0])
    }

    @Test
    fun aDamagedHistoryKeepsTheScreenAndWhatCameBeforeIt() {
        // Inside the oldest history page, just before the end.
        val damaged = snapshot.copyOf().also { it[it.size - 100] = (it[it.size - 100] + 1).toByte() }
        val t = TestSession(120, 30, transcriptRows = 10_000)
        SnapshotDecoder().use { decoder ->
            decoder.feed(damaged, 0, damaged.size)
            assertTrue(t.session.showSnapshot(decoder))
            var rows: Int
            do rows = t.session.restoreHistory(decoder, 1) while (rows >= 0)
            assertEquals(-2, rows)
        }
        assertEquals(direct.text(0), t.text(0))
        assertTrue(t.history in 1 until direct.history)
    }

    @Test
    fun theHistoryUnderAFullscreenProgramIsAllRestored() {
        val alternate = resource("snapshots/alternate.snap")
        val expected = TestSession(120, 30, transcriptRows = 10_000).apply {
            val vt = resource("snapshots/alternate.vt")
            session.feed(vt, vt.size)
        }
        val t = TestSession(120, 30, transcriptRows = 10_000)
        SnapshotDecoder().use { decoder ->
            assertEquals(SnapshotDecoder.COMPLETE, decoder.feed(alternate, 0, alternate.size))
            assertTrue(t.session.showSnapshot(decoder))
            assertEquals("a fullscreen program", t.text(0)[0])
            var rows: Int
            do rows = t.session.restoreHistory(decoder, 1) while (rows >= 0)
            assertEquals(-1, rows)
        }
        // The program ends, and the conversation under it is all there.
        expected.print("\u001b[?1049l")
        t.print("\u001b[?1049l")
        assertEquals(expected.history, t.history)
        assertEquals(expected.emulator.screen.transcriptText, t.emulator.screen.transcriptText)
    }

    private fun restore(t: TestSession): TestSession {
        SnapshotDecoder().use { decoder ->
            assertEquals(SnapshotDecoder.COMPLETE, decoder.feed(snapshot, 0, snapshot.size))
            assertTrue(t.session.showSnapshot(decoder))
            while (t.session.restoreHistory(decoder, 8) >= 0) Unit
        }
        return t
    }

    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readBytes()
}
