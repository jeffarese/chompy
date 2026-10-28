package com.jeffarese.rexmobile.model

import android.content.Context
import com.jeffarese.rexmobile.rex.RexJson
import com.jeffarese.rexmobile.terminal.emulator.TextStyle
import kotlinx.serialization.Serializable

/** Whether the app is dark, light, or as the phone is. */
enum class Appearance(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("As the phone is"),
}

/**
 * One of Rex's themes, as copied from the Mac app's themes.json: its UI colors
 * by Rex's token names, and its terminal colors.
 */
@Serializable
data class RexTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val ui: Map<String, String>,
    val terminal: TerminalTheme,
) {
    @Serializable
    data class TerminalTheme(val background: String, val foreground: String, val cursor: String, val palette: List<String>)

    /** A UI color as 0xAARRGGBB, opaque. */
    fun color(token: String): Int = argb(ui[token] ?: error("$id has no $token"))

    /** The terminal's colors as the emulator takes them: the 256-color palette, then foreground, background and cursor. */
    val terminalColors: IntArray by lazy {
        IntArray(TextStyle.NUM_INDEXED_COLORS).also { c ->
            terminal.palette.take(256).forEachIndexed { i, hex -> c[i] = argb(hex) }
            c[TextStyle.COLOR_INDEX_FOREGROUND] = argb(terminal.foreground)
            c[TextStyle.COLOR_INDEX_BACKGROUND] = argb(terminal.background)
            c[TextStyle.COLOR_INDEX_CURSOR] = argb(terminal.cursor)
        }
    }

    companion object {
        fun argb(hex: String): Int = 0xff000000.toInt() or hex.removePrefix("#").toInt(16)
    }
}

/** Rex's themes, read once from the app's assets, and the app's own look beside them. */
class Themes(private val context: Context) {
    val all: List<RexTheme> by lazy {
        runCatching {
            context.assets.open(ASSET).bufferedReader().use { RexJson.decodeFromString<List<RexTheme>>(it.readText()) }
        }.getOrDefault(emptyList())
    }

    /** The theme with [id], or null for the app's own look (or a theme a Rex update took away). */
    fun byId(id: String): RexTheme? = all.firstOrNull { it.id == id }

    /** "Chompy" for the app's own look, else the theme's name. */
    fun name(id: String): String = byId(id)?.name ?: DEFAULT_NAME

    companion object {
        const val ASSET = "rex-themes.json"
        /** The app's own dark look, from before it had themes. */
        const val DEFAULT = "rex"
        const val DEFAULT_NAME = "Chompy"
        const val DEFAULT_LIGHT = "merino-light"
    }
}
