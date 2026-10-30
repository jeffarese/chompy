package com.jeffarese.rexmobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.model.Themes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Text reads at 4.5:1 or better on the cards and canvas of the app's own look and of every one of Rex's themes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaletteContrastTest {
    private val themes = Themes(ApplicationProvider.getApplicationContext()).all
    private val palettes = listOf("Chompy" to Palette.Default) + themes.map { it.name to paletteOf(it) }

    @After
    fun tearDown() = Rex.wear(Palette.Default)

    private fun Palette.worst(c: Color) = Palette.contrastOn(c, listOf(bg, surface))

    private fun assertReadable(what: String, ratio: Float, least: Float = Palette.READABLE) =
        assertTrue("$what reads at ${"%.2f".format(ratio)}:1", ratio >= least - 0.01f)

    @Test
    fun everyThemeIsChecked() {
        assertEquals(42, palettes.size)
    }

    @Test
    fun dimAndFaintTextAreReadableAndStillAStepApart() {
        for ((name, p) in palettes) {
            val (text, dim, faint) = Triple(p.worst(p.text), p.worst(p.textDim), p.worst(p.textFaint))
            assertReadable("$name's faint text", faint)
            assertReadable("$name's dim text", dim, faint * Palette.DIM_OVER_FAINT)
            assertTrue("$name's text ($text) stands out more than its dim text ($dim)", text > dim)
        }
    }

    @Test
    fun claudesModesAndNoteAccentAreReadable() {
        for ((name, p) in palettes) {
            Rex.wear(p)
            for ((mode, c) in listOf("plan" to Rex.planMode, "accept edits" to Rex.acceptEdits, "bypass" to Rex.bypassMode)) {
                assertReadable("$name's $mode", p.worst(c))
                // As the Claude strip's chip draws it: on a faint wash of its own colour.
                assertReadable("$name's $mode on its chip", Palette.contrast(c, c.copy(alpha = 0.14f).compositeOver(p.surface)))
            }
            assertReadable("$name's note accent", p.worst(FyiAccent))
        }
    }

    @Test
    fun theAppsOwnLookKeepsItsColorsWhereTheyRead() {
        assertEquals(Color(0xFF48B0A2), Palette.Default.planMode)
        assertEquals(Color(0xFFAF87FF), Palette.Default.acceptEdits)
        assertEquals(Color(0xFFFF6B80), Palette.Default.bypassMode)
        Rex.wear(Palette.Default)
        assertEquals(Color(0xFFB1B9F9), FyiAccent)
    }
}
