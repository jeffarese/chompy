package com.jeffarese.rexmobile.terminal.emulator

import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Control sequences the emulator doesn't handle, such as the Kitty keyboard protocol's, leave nothing on the screen. */
class UnhandledControlSequenceTest {
    private val term = TestSession(40, 5)

    private fun shown(vararg output: String): String {
        output.forEach { term.print(it) }
        return term.text(0, 1).single()
    }

    @Test
    fun kittyKeyboardSequencesAreNotDrawn() {
        assertEquals("AB", shown("A\u001b[<uB"))
    }

    @Test
    fun kittyKeyboardSetIsNotDrawn() {
        assertEquals("AB", shown("A\u001b[=5;1uB"))
    }

    @Test
    fun kittyKeyboardPopWithACountIsNotDrawn() {
        assertEquals("AB", shown("A\u001b[<1uB"))
    }

    @Test
    fun modifyOtherKeysIsNotDrawn() {
        assertEquals("AB", shown("A\u001b[>4;2mB"))
    }

    @Test
    fun theKittyFlagsQueryIsNotDrawnOrAnswered() {
        assertEquals("AB", shown("A\u001b[?uB"))
        assertEquals("", term.typed.toString())
    }

    @Test
    fun otherUnhandledSequencesAreSkippedToTheirFinalByte() {
        assertEquals("AB", shown("A\u001b[>0qB")) // XTVERSION
        term.print("\r\u001b[2K")
        assertEquals("AB", shown("A\u001b[4:3mB")) // Colon sub-parameters.
        term.print("\r\u001b[2K")
        assertEquals("AB", shown("A\u001b[1#PB")) // An intermediate byte it doesn't know.
    }

    @Test
    fun aSequenceSplitAcrossReadsIsStillSkipped() {
        assertEquals("AB", shown("A\u001b[=5", ";1", "uB"))
    }

    @Test
    fun theKittyGraphicsQueryIsNotDrawn() {
        // Claude Code sends it at startup; drawn, it would land in Claude's input box.
        assertEquals("AB", shown("A\u001b_Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA\u001b\\B"))
        assertEquals("", term.typed.toString())
    }

    @Test
    fun stringsAreSkippedToTheirEndEvenAcrossReads() {
        // As in Rex's own terminal, only ST ends an APC string: a bell inside one is part of it.
        assertEquals("AC", shown("A\u001b_Gi=1;AAAA\u0007B\u001b\\C"))
        term.print("\r\u001b[2K")
        assertEquals("AB", shown("A\u001b_Gi=31", ";AAAA\u001b", "\\B"))
        term.print("\r\u001b[2K")
        assertEquals("AB", shown("A\u001b^privacy message\u001b\\B"))
    }

    @Test
    fun anEscapeThatDoesNotEndAStringStartsASequence() {
        assertEquals("AB", shown("A\u001b_Gi=1\u001b[mB"))
    }

    @Test
    fun anEscapeEndsASkippedSequence() {
        assertEquals("AB", shown("A\u001b[<1\u001b[mB"))
    }

    @Test
    fun handledSequencesStillWork() {
        term.print("\u001b[?1000h\u001b[?1006h")
        assertTrue(term.emulator.isMouseTrackingActive)
        term.print("\u001b[?1049h")
        assertTrue(term.emulator.isAlternateBufferActive)
        term.print("\u001b[?1049l\u001b[H\u001b[2J")
        assertEquals("XY", shown("X\u001b[1;1HXY"))
    }
}
