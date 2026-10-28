package com.jeffarese.rexmobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReturnLogTest {
    @get:Rule val dir = TemporaryFolder()

    @Test
    fun keepsTheNewestEntriesFirst() {
        val log = ReturnLog(dir.root.resolve("returns.txt"))
        assertNull(log.read())

        log.add("first\n  host: answered in 40 ms")
        log.add("second\n  host: answered in 35 ms\n")
        assertEquals("second\n  host: answered in 35 ms\n\nfirst\n  host: answered in 40 ms", log.read())

        repeat(ReturnLog.MAX_ENTRIES + 5) { log.add("return $it") }
        val entries = log.read()!!.split("\n\n")
        assertEquals(ReturnLog.MAX_ENTRIES, entries.size)
        assertEquals("return ${ReturnLog.MAX_ENTRIES + 4}", entries.first())
        assertEquals("return 5", entries.last())
    }
}
