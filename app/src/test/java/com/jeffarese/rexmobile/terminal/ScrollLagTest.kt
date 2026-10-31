package com.jeffarese.rexmobile.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollLagTest {
    @Test
    fun aFastLinkIsNotSlow() {
        val lag = ScrollLag()
        repeat(10) { assertFalse(lag.add(30)) }
    }

    @Test
    fun aMobileLinkIsSlowOnceEnoughBatchesHaveGone() {
        val lag = ScrollLag()
        assertFalse(lag.add(250))
        assertFalse(lag.add(260))
        assertFalse(lag.add(240))
        // A fourth batch is enough to tell.
        assertTrue(lag.add(255))
    }

    @Test
    fun oneLateAnswerIsNotLag() {
        val lag = ScrollLag()
        listOf(20L, 25L, 900L, 30L, 22L).forEach { lag.add(it) }
        assertFalse(lag.slow)
    }

    @Test
    fun onlyTheLastBatchesCount() {
        val lag = ScrollLag()
        repeat(5) { lag.add(300) }
        assertTrue(lag.slow)
        // The link got better: five quick batches push the slow ones out.
        repeat(5) { lag.add(20) }
        assertFalse(lag.slow)
    }
}
