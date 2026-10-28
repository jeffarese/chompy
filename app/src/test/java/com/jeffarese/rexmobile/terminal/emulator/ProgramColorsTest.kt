package com.jeffarese.rexmobile.terminal.emulator

import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.Assert.assertEquals
import org.junit.Test

/** The colors a program sets with OSC sequences show over the scheme's, from the sequence's end. */
class ProgramColorsTest {
    private val term = TestSession(40, 5)
    private val colors get() = term.emulator.mColors.mCurrentColors
    private val scheme = TerminalColors.COLOR_SCHEME.mDefaultColors

    @Test
    fun aColorSetShowsOnceItsSequenceEnds() {
        term.print("\u001b]11;rgb:10/20/30\u001b\\")
        assertEquals(0xff102030.toInt(), colors[TextStyle.COLOR_INDEX_BACKGROUND])
    }

    @Test
    fun aSequenceSplitAcrossReadsStillSetsTheColor() {
        term.print("\u001b]4;1;rgb:aa/bb/")
        term.print("cc\u0007")
        assertEquals(0xffaabbcc.toInt(), colors[1])
    }

    @Test
    fun aColorResetIsTheSchemesAgain() {
        term.print("\u001b]4;1;rgb:aa/bb/cc\u001b\\")
        term.print("\u001b]104;1\u001b\\")
        assertEquals(scheme[1], colors[1])
    }
}
