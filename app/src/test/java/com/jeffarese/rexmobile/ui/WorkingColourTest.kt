package com.jeffarese.rexmobile.ui

import androidx.compose.ui.graphics.Color
import com.jeffarese.rexmobile.model.RexTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/** Working is blue, as in Rex: the theme's info, or the app's own blue in its own look. */
class WorkingColourTest {
    private val tokens = listOf("canvas", "content", "contentSecondary", "contentTertiary", "strokeSubtle", "accent", "accentOnSolid", "success", "warning", "danger")

    private fun theme(info: String, dark: Boolean = true) = RexTheme(
        "t", "T", dark,
        tokens.associateWith { if (it == "canvas") "#101010" else "#c0c0c0" } + ("info" to info),
        RexTheme.TerminalTheme("#000000", "#ffffff", "#ffffff", List(16) { "#808080" }),
    )

    @Test
    fun takesTheThemesInfo() {
        assertEquals(Color(0xFF4A90C9), paletteOf(theme("#4a90c9")).working)
        assertEquals(Color(0xFF2F6FB0), paletteOf(theme("#2f6fb0", dark = false)).working)
    }

    @Test
    fun isUnseensBlueInEveryLook() {
        assertEquals(Color(0xFF3B8BFF), Palette.Default.working)
        assertEquals(Palette.Default.unseen, Palette.Default.working)
        val p = paletteOf(theme("#4a90c9"))
        assertEquals(p.unseen, p.working)
    }
}
