package com.jeffarese.rexmobile.terminal.emulator

import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.Assert.assertEquals
import org.junit.Test

/** A session done with frees its native terminal, and what still reads it reads a blank screen. */
class ReleasedSessionTest {
    @Test
    fun aReleasedSessionReadsBlank() {
        val t = TestSession(40, 5)
        t.print("some output\r\nmore")
        t.session.release()
        t.print("after the release")
        assertEquals(List(5) { "" }, t.text(0))
        assertEquals(0, t.history)
    }
}
