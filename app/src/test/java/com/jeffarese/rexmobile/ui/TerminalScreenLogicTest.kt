package com.jeffarese.rexmobile.ui

import com.jeffarese.rexmobile.model.ConnStatus
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
}
