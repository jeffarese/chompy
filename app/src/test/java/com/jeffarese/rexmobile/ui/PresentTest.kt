package com.jeffarese.rexmobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresentTest {
    @Test
    fun fileUrlsBecomeHomeRelativePaths() {
        assertEquals("~/src/app", prettyPath("file://studio/Users/alex/src/app"))
        assertEquals("~/My Project", prettyPath("file://studio/Users/alex/My%20Project"))
        assertEquals("~/café", prettyPath("file://studio/home/alex/caf%C3%A9"))
        assertEquals("~/x", prettyPath("file://box/root/x"))
        assertNull(prettyPath(""))
    }

    @Test
    fun plusAndStrayPercentSurvive() {
        // URLDecoder turned "c++" into "c  " and threw on a stray '%', while Home was drawing.
        assertEquals("~/src/c++", prettyPath("file://studio/Users/alex/src/c++"))
        assertEquals("~/100%", prettyPath("file://studio/Users/alex/100%"))
        assertEquals("~/50%off", prettyPath("file://studio/Users/alex/50%off"))
        assertEquals("~/a%2", prettyPath("file://studio/Users/alex/a%2"))
    }

    @Test
    fun plainPathsAreNotDecoded() {
        assertEquals("~/100%20done", prettyPath("/Users/alex/100%20done"))
        assertEquals("~/c++", prettyPath("/home/alex/c++"))
    }

    @Test
    fun percentDecodeKeepsWhatIsNotAnEscape() {
        assertEquals("a b", percentDecode("a%20b"))
        assertEquals("%zz", percentDecode("%zz"))
        assertEquals("%", percentDecode("%"))
        assertEquals("emoji 🦖", percentDecode("emoji%20🦖"))
        // Bytes that aren't UTF-8 don't throw.
        assertEquals("�", percentDecode("%FF"))
    }
}
