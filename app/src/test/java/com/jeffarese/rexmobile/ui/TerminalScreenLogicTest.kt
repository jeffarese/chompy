package com.jeffarese.rexmobile.ui

import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.KeepScreenOn
import com.jeffarese.rexmobile.rex.BlockInfo
import com.jeffarese.rexmobile.rex.Layer
import com.jeffarese.rexmobile.rex.StreamState
import com.jeffarese.rexmobile.rex.WindowView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScreenLogicTest {
    @Test
    fun theScreenFadesOnlyWhileReconnecting() {
        assertFalse(screenFaded(ConnStatus.ONLINE, StreamState.LIVE))
        assertFalse(screenFaded(ConnStatus.ONLINE, StreamState.CONNECTING))
        assertTrue(screenFaded(ConnStatus.ONLINE, StreamState.RECONNECTING))
        assertTrue(screenFaded(ConnStatus.OFFLINE, StreamState.LIVE))
    }

    @Test
    fun aTabOpensToItsZoomedThenFocusedThenFirstPane() {
        fun win(zoomed: String? = null, focused: String? = null, vararg ids: String) =
            WindowView(windowId = "w", zoomedBlockId = zoomed, focusedBlockId = focused, layers = listOf(Layer(blocks = ids.map { BlockInfo(blockId = it) })))
        assertEquals("b", shownBlock(win("b", "c", "a", "b", "c")))
        assertEquals("c", shownBlock(win(null, "c", "a", "b", "c")))
        assertEquals("a", shownBlock(win("gone", "gone", "a", "b")))
        assertNull(shownBlock(win()))
    }

    @Test
    fun anAgentsWorkKeepsTheScreenOnUntilTenMinutesAfterATouch() {
        val minute = 60_000L
        // A touch just now: the ten minutes start over.
        assertEquals(10 * minute, keepScreenOnFor(KeepScreenOn.WORKING, working = true, sinceTouch = 0))
        assertEquals(3 * minute, keepScreenOnFor(KeepScreenOn.WORKING, working = true, sinceTouch = 7 * minute))
        // Past them, the system's own timeout applies, as it does while nothing works.
        assertEquals(0, keepScreenOnFor(KeepScreenOn.WORKING, working = true, sinceTouch = 11 * minute))
        assertEquals(0, keepScreenOnFor(KeepScreenOn.WORKING, working = false, sinceTouch = 0))
        // Always and Never don't count the time.
        assertEquals(Long.MAX_VALUE, keepScreenOnFor(KeepScreenOn.ALWAYS, working = false, sinceTouch = 60 * minute))
        assertEquals(0, keepScreenOnFor(KeepScreenOn.NEVER, working = true, sinceTouch = 0))
    }
}
