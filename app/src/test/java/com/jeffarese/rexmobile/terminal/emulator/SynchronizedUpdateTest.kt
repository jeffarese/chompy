package com.jeffarese.rexmobile.terminal.emulator

import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A frame drawn inside a synchronized update (mode 2026) is shown once it is whole, not half drawn. */
class SynchronizedUpdateTest {
    private var updates = 0
    private val term = TestSession(40, 5) { updates++ }

    init {
        updates = 0 // Setting the size at the start is an update too.
    }

    private fun shown() = term.text(0, 1).single()

    @Test
    fun outputOutsideAnUpdateShowsAtOnce() {
        term.print("A")
        assertEquals(1, updates)
        assertFalse(term.session.isUpdateHeld)
    }

    @Test
    fun aFrameSplitAcrossMessagesShowsWhenItEnds() {
        term.print("\u001b[?2026hHal")
        assertEquals(0, updates)
        assertTrue(term.session.isUpdateHeld)
        assertTrue(term.emulator.isSynchronizedUpdateActive)

        term.print("f done\u001b[?2026l")
        assertEquals(1, updates)
        assertFalse(term.session.isUpdateHeld)
        assertEquals("Half done", shown())
    }

    @Test
    fun aWholeFrameInOneMessageShowsOnce() {
        term.print("\u001b[?2026hframe\u001b[?2026l")
        assertEquals(1, updates)
        assertEquals("frame", shown())
    }

    @Test
    fun aFrameThatNeverEndsCanBeShownAnyway() {
        term.print("\u001b[?2026hstuck")
        term.session.releaseHeldUpdate()
        assertEquals(1, updates)
        assertFalse(term.session.isUpdateHeld)
        // Nothing more to show: a second release does nothing.
        term.session.releaseHeldUpdate()
        assertEquals(1, updates)
    }

    @Test
    fun aResetEndsTheUpdate() {
        term.print("\u001b[?2026hx")
        term.print("\u001bc")
        assertFalse(term.emulator.isSynchronizedUpdateActive)
        assertEquals(1, updates)
    }

    @Test
    fun cursorSaveAndRestoreLeaveTheUpdateAlone() {
        // DECSC/DECRC save and restore DECSET bits; the update is not one of them.
        term.print("\u001b7\u001b[?2026h\u001b8")
        assertTrue(term.emulator.isSynchronizedUpdateActive)
        term.print("\u001b[?2026l")
        assertFalse(term.emulator.isSynchronizedUpdateActive)
    }
}
