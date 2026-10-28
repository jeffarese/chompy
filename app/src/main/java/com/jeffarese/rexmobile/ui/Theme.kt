package com.jeffarese.rexmobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.jeffarese.rexmobile.model.RexTheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The app's colors: its own dark look, or made from one of Rex's themes with [paletteOf]. */
@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val divider: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    /** For text and icons on [accent]. */
    val onAccent: Color,
    val unseen: Color,
    val working: Color,
    val blocked: Color,
    val done: Color,
    val error: Color,
    val keyBg: Color,
    val keyActive: Color,
) {
    val accentSoft get() = accent.copy(alpha = 0.2f)

    /** For text on [blocked]: amber is bright in a dark theme and deep in a light one. */
    val onBlocked get() = if (dark) Color.Black else Color.White

    // Here rather than at the top level, whose class also holds what reads Rex.mono: Rex starting up would wait on itself.
    companion object {
        /** The app's own look, from before it had themes. */
        val Default = Palette(
            dark = true,
            bg = Color(0xFF0E0E12),
            surface = Color(0xFF1A1A21),
            surfaceHigh = Color(0xFF24242D),
            divider = Color(0xFF2C2C36),
            text = Color(0xFFF2F2F7),
            textDim = Color(0xFF8E8E9A),
            textFaint = Color(0xFF5E5E6A),
            accent = Color(0xFFFF6A3D),
            onAccent = Color.White,
            unseen = Color(0xFF3B8BFF),
            working = Color(0xFFA78BFA),
            blocked = Color(0xFFF5A524),
            done = Color(0xFF34D399),
            error = Color(0xFFF87171),
            keyBg = Color(0xFF22222A),
            keyActive = Color(0xFF3A3A48),
        )
    }
}


/**
 * A Rex theme's colors for this app. Rex's surfaces suit a desktop's panes, so the cards, menus and
 * keys here are steps from the canvas toward the text instead, as the app's own look is.
 */
fun paletteOf(theme: RexTheme): Palette {
    fun c(token: String) = Color(theme.color(token))
    val bg = c("canvas")
    val text = c("content")
    fun step(f: Float) = lerp(bg, text, if (theme.dark) f else f * 0.8f)
    return Palette(
        dark = theme.dark,
        bg = bg,
        surface = step(0.055f),
        surfaceHigh = step(0.1f),
        divider = c("strokeSubtle"),
        text = text,
        textDim = c("contentSecondary"),
        textFaint = c("contentTertiary"),
        accent = c("accent"),
        onAccent = c("accentOnSolid"),
        unseen = c("info"),
        working = violetOf(theme),
        blocked = c("warning"),
        done = c("success"),
        error = c("danger"),
        keyBg = step(0.08f),
        keyActive = step(0.16f),
    )
}

/**
 * Working's colour: the theme's own magenta where that is a violet, as it is in about half of them, else a violet
 * that reads on a dark or a light canvas. Elsewhere magenta is pink, orange or green, which Failed, Needs you or Done
 * would be taken for.
 */
internal fun violetOf(theme: RexTheme): Color {
    val magenta = Color(RexTheme.argb(theme.terminal.palette[5]))
    val (r, g, b) = Triple(magenta.red, magenta.green, magenta.blue)
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val hue = when {
        max == min -> -1f
        max == r -> 60f * (((g - b) / (max - min)).mod(6f))
        max == g -> 60f * ((b - r) / (max - min) + 2f)
        else -> 60f * ((r - g) / (max - min) + 4f)
    }
    val violet = hue in 245f..290f && (max - min) / max > 0.2f
    return when {
        violet -> magenta
        theme.dark -> Color(0xFFA78BFA)
        else -> Color(0xFF7C5CC4)
    }
}

object Rex {
    /** The colors now. State, so whatever drew with them draws again when the theme changes. */
    var palette by mutableStateOf(Palette.Default)
        private set

    /**
     * Change the colors, from any thread. Applied at once: a change written straight into the global
     * snapshot waits there until a UI applies it, and one made away from any UI never would be.
     */
    fun wear(p: Palette) = Snapshot.withMutableSnapshot { palette = p }

    val bg get() = palette.bg
    val surface get() = palette.surface
    val surfaceHigh get() = palette.surfaceHigh
    val divider get() = palette.divider
    val text get() = palette.text
    val textDim get() = palette.textDim
    val textFaint get() = palette.textFaint
    val accent get() = palette.accent
    val accentSoft get() = palette.accentSoft
    val onAccent get() = palette.onAccent
    val onBlocked get() = palette.onBlocked
    val unseen get() = palette.unseen
    val working get() = palette.working
    val blocked get() = palette.blocked
    val done get() = palette.done
    val error get() = palette.error
    val online get() = palette.done
    val offline get() = palette.error
    val keyBg get() = palette.keyBg
    val keyActive get() = palette.keyActive

    // Claude Code's permission modes, in the colors its footer uses.
    val planMode = Color(0xFF48B0A2)
    val acceptEdits = Color(0xFFAF87FF)
    val bypassMode = Color(0xFFFF6B80)

    /** With the symbol fonts as fallbacks, from [RexFonts], once the app has built them. */
    val mono: FontFamily by lazy { RexFonts.monoFamily() }
}

private fun colorScheme(p: Palette) = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
    primary = p.accent,
    onPrimary = p.onAccent,
    secondary = p.unseen,
    background = p.bg,
    onBackground = p.text,
    surface = p.surface,
    onSurface = p.text,
    surfaceVariant = p.surfaceHigh,
    onSurfaceVariant = p.textDim,
    // Menus draw on surfaceContainer: a step above the cards they open from, so they don't blend in.
    surfaceContainer = p.surfaceHigh,
    surfaceContainerHigh = p.surfaceHigh,
    surfaceContainerHighest = p.surfaceHigh,
    outline = p.divider,
    outlineVariant = p.divider,
    error = p.error,
)

private val base = Typography()
private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.5).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    // Text takes bodyLarge's line height and letter spacing even where it sets its own size. Relative to the
    // font, they scale with it: a fixed 24sp line spaced 13sp subtitles almost double.
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 1.38.em, letterSpacing = 0.2.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 1.38.em, letterSpacing = 0.2.sp),
    bodySmall = base.bodySmall.copy(lineHeight = 1.38.em, letterSpacing = 0.2.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
)

@Composable
fun RexTheme(content: @Composable () -> Unit) {
    val palette = Rex.palette
    val scheme = remember(palette) { colorScheme(palette) }
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

val MonoSmall = TextStyle(fontFamily = Rex.mono, fontSize = 12.sp)
