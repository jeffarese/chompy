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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.jeffarese.rexmobile.model.RexTheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The app's colors: its own dark look, or made from one of its themes with [paletteOf]. */
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

    // Claude Code's permission modes, in the colors its footer uses: its dark theme's, or its light theme's on a light
    // palette, where the dark theme's pale ones read at about 2.5:1.
    val planMode = modeColor(if (dark) Color(0xFF48B0A2) else Color(0xFF006666))
    val acceptEdits = modeColor(if (dark) Color(0xFFAF87FF) else Color(0xFF8700FF))
    val bypassMode = modeColor(if (dark) Color(0xFFFF6B80) else Color(0xFFAB2B3F))

    /** [color] as text on this palette's cards and canvas: as it is if it reads at [ratio] there, else nearer [text]. */
    fun readable(color: Color, ratio: Float = READABLE) = legible(color, text, listOf(bg, surface), ratio)

    /** A mode's [color], readable also where the Claude strip shows it: on a card washed with 14% of the color. */
    private fun modeColor(color: Color) = closest(color, text) { c ->
        contrastOn(c, listOf(bg, surface, c.copy(alpha = 0.14f).compositeOver(surface))) >= READABLE
    }

    // Here rather than at the top level, whose class also holds what reads Rex.mono: Rex starting up would wait on itself.
    companion object {
        /** The contrast text needs to be read with ease, as WCAG puts it: the least any text here has. */
        const val READABLE = 4.5f

        /** How much more [textDim] stands out than [textFaint] at least, so the two still look different. */
        const val DIM_OVER_FAINT = 1.25f

        /** WCAG's contrast ratio of two opaque colors: 1 for the same, 21 for black on white. */
        fun contrast(a: Color, b: Color): Float {
            val (la, lb) = a.luminance() to b.luminance()
            return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
        }

        /** [color]'s contrast on the one of [on] it stands out least from. */
        fun contrastOn(color: Color, on: List<Color>) = on.minOf { contrast(color, it) }

        /**
         * [color] if it reads at [ratio] on each of [on], else the shade of it closest to it, on the way to [toward],
         * that does: a grey too faint on a dark canvas gets lighter, one on a light canvas darker.
         */
        fun legible(color: Color, toward: Color, on: List<Color>, ratio: Float = READABLE) =
            closest(color, toward) { contrastOn(it, on) >= ratio }

        /** [color] if it [reads], else the shade closest to it, on the way to [toward], that does. */
        fun closest(color: Color, toward: Color, reads: (Color) -> Boolean): Color {
            if (reads(color)) return color
            var (short, enough) = 0f to 1f
            repeat(20) {
                val f = (short + enough) / 2
                if (reads(lerp(color, toward, f))) enough = f else short = f
            }
            return lerp(color, toward, enough)
        }

        /** The app's own look, from before it had themes, with its faint text brought up to [READABLE]. */
        val Default = Palette(
            dark = true,
            bg = Color(0xFF0E0E12),
            surface = Color(0xFF1A1A21),
            surfaceHigh = Color(0xFF24242D),
            divider = Color(0xFF2C2C36),
            text = Color(0xFFF2F2F7),
            textDim = Color(0xFF9494A0),
            textFaint = Color(0xFF82828D),
            accent = Color(0xFFFF6A3D),
            onAccent = Color.White,
            unseen = Color(0xFF3B8BFF),
            working = Color(0xFF3B8BFF),
            blocked = Color(0xFFF5A524),
            done = Color(0xFF34D399),
            error = Color(0xFFF87171),
            keyBg = Color(0xFF22222A),
            keyActive = Color(0xFF3A3A48),
        )
    }
}


/** A theme's colors for this app: its cards, menus and keys are steps from the canvas toward the text, as the app's own look's are. */
fun paletteOf(theme: RexTheme): Palette {
    fun c(token: String) = Color(theme.color(token))
    val bg = c("canvas")
    val text = c("content")
    fun step(f: Float) = lerp(bg, text, if (theme.dark) f else f * 0.8f)
    val surface = step(0.055f)
    // The tertiary text carries times, folders and subtasks, so it reads at READABLE at least, and the secondary a step
    // above it. art/themes/themes.py makes them so already: this keeps it true of a card's colors too.
    val cards = listOf(bg, surface)
    val faint = Palette.legible(c("contentTertiary"), text, cards)
    val dim = Palette.legible(c("contentSecondary"), text, cards, Palette.contrastOn(faint, cards) * Palette.DIM_OVER_FAINT)
    return Palette(
        dark = theme.dark,
        bg = bg,
        surface = surface,
        surfaceHigh = step(0.1f),
        divider = c("strokeSubtle"),
        text = text,
        textDim = dim,
        textFaint = faint,
        accent = c("accent"),
        onAccent = c("accentOnSolid"),
        unseen = c("info"),
        // Blue, as Rex shows a working agent: the theme's info, which is unseen's too.
        working = c("info"),
        blocked = c("warning"),
        done = c("success"),
        error = c("danger"),
        keyBg = step(0.08f),
        keyActive = step(0.16f),
    )
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

    // Claude Code's permission modes, in the colors its footer uses, readable on the palette's cards.
    val planMode get() = palette.planMode
    val acceptEdits get() = palette.acceptEdits
    val bypassMode get() = palette.bypassMode

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
