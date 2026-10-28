package com.jeffarese.rexmobile.ui

import androidx.compose.ui.graphics.Color
import com.jeffarese.rexmobile.model.RexTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/** Working is violet in every theme: the theme's own where its magenta is one, else one for its canvas. */
class WorkingColourTest {
    private fun theme(magenta: String, dark: Boolean = true) =
        RexTheme("t", "T", dark, emptyMap(), RexTheme.TerminalTheme("#000000", "#ffffff", "#ffffff", List(16) { if (it == 5) magenta else "#808080" }))

    @Test
    fun takesTheThemesMagentaWhereItIsAViolet() {
        assertEquals(Color(0xFF9179C9), violetOf(theme("#9179c9")))
    }

    @Test
    fun putsAVioletInPlaceOfAPinkOrangeGreenOrGreyMagenta() {
        for (m in listOf("#ff7ed1", "#cf8a62", "#8fce74", "#b4b4b4")) assertEquals(m, Color(0xFFA78BFA), violetOf(theme(m)))
        assertEquals(Color(0xFF7C5CC4), violetOf(theme("#a2538c", dark = false)))
    }
}
