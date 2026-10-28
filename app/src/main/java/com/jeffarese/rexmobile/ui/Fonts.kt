package com.jeffarese.rexmobile.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import com.jeffarese.rexmobile.R

/**
 * The monospace font of the terminal and of the app's terminal text: JetBrainsMono Nerd Font Mono, then
 * Noto's symbol fonts for what it lacks, such as Claude Code's ⏵ ⏸ ⏺ ⎿ ✻ ◐. Android's own fonts draw
 * those as boxes, or as color emoji, where macOS has fonts for them all.
 *
 * Fallback fonts of the app's own need Android 10. Before that, Android's fallback follows the Nerd Font.
 * Built on first use, or ahead of it by [prewarm]; anything that fails to load falls back rather than
 * failing the app. Each is built once, whichever thread asks first, while the others wait for it.
 */
object RexFonts {
    private val SYMBOLS = intArrayOf(R.font.noto_sans_symbols2, R.font.noto_sans_symbols, R.font.noto_sans_math)

    @Volatile private var app: Context? = null
    /** The font files, read once for both typefaces: together they come to about 7 MB. */
    private val files by lazy { if (Build.VERSION.SDK_INT >= 29) loadFiles() else null }
    private val terminal by lazy { build(bold = false) ?: plain() ?: Typeface.MONOSPACE }
    private val mono by lazy { build(bold = true) }

    /** Remember the app, to load the fonts from when they are first wanted. */
    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Build the fonts now, off the main thread, so the first terminal or screen of them doesn't wait. */
    fun prewarm() {
        terminal
        mono
    }

    /** For the terminal, which draws bold by thickening. */
    fun terminal(context: Context): Typeface {
        if (app == null) init(context)
        return terminal
    }

    /** For Compose: regular and bold, with the symbol fonts when there are custom fallbacks. */
    fun monoFamily(): FontFamily = mono?.let { FontFamily(it) }
        ?: FontFamily(Font(R.font.jetbrains_mono), Font(R.font.jetbrains_mono_bold, FontWeight.Bold))

    private fun plain(): Typeface? = app?.let { ctx -> runCatching { ResourcesCompat.getFont(ctx, R.font.jetbrains_mono) }.getOrNull() }

    private fun build(bold: Boolean): Typeface? {
        if (Build.VERSION.SDK_INT < 29) return null
        // Throwable: some environments, such as unit tests, can't load fonts at all.
        return try {
            files?.typeface(bold)
        } catch (e: Throwable) {
            null
        }
    }

    @RequiresApi(29)
    private fun loadFiles(): Files? {
        val res = app?.resources ?: return null
        return try {
            Files(res)
        } catch (e: Throwable) {
            null
        }
    }

    /** The fonts, shared by the typefaces built from them. */
    @RequiresApi(29)
    private class Files(res: Resources) {
        private fun font(res: Resources, id: Int, weight: Int = 400) = android.graphics.fonts.Font.Builder(res, id).setWeight(weight).build()

        private val regular = font(res, R.font.jetbrains_mono)
        private val bold = font(res, R.font.jetbrains_mono_bold, 700)
        private val symbols = SYMBOLS.map { android.graphics.fonts.FontFamily.Builder(font(res, it)).build() }

        fun typeface(withBold: Boolean): Typeface {
            val main = android.graphics.fonts.FontFamily.Builder(regular)
            if (withBold) main.addFont(bold)
            val builder = Typeface.CustomFallbackBuilder(main.build())
            for (family in symbols) builder.addCustomFallback(family)
            return builder.setSystemFallback("monospace").build()
        }
    }
}
