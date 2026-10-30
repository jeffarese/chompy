package com.jeffarese.rexmobile.model

import android.content.Context
import com.jeffarese.rexmobile.BuildConfig
import com.jeffarese.rexmobile.rex.RexJson
import com.jeffarese.rexmobile.terminal.emulator.TextStyle
import kotlinx.serialization.Serializable
import java.io.File

/** Whether the app is dark, light, or as the phone is. */
enum class Appearance(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("As the phone is"),
}

/**
 * One of Chompy's themes, from art/themes/themes.py: its UI colors by token name, and its terminal
 * colors.
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

/**
 * Chompy's themes, read once from the app's assets, and its own look beside them. Reading them all
 * takes a while, so the ones the app last wore, a dark one and a light one, are also kept in a small
 * file: a launch in one of them wears it without reading the rest.
 */
class Themes(private val context: Context) {
    private val read = lazy {
        runCatching {
            context.assets.open(ASSET).bufferedReader().use { RexJson.decodeFromString<List<RexTheme>>(it.readText()) }
        }.getOrDefault(emptyList())
    }
    val all: List<RexTheme> by read

    /** Whether [all] has been read yet. */
    internal val allRead: Boolean get() = read.isInitialized()

    private val wornFile = File(context.filesDir, WORN_FILE)
    @Volatile private var worn: List<RexTheme>? = null

    /** The themes [keep] kept, unless they were kept by another version of the app, which may have changed them. */
    private fun worn(): List<RexTheme> = worn ?: runCatching {
        RexJson.decodeFromString(Worn.serializer(), wornFile.readText()).takeIf { it.version == BuildConfig.VERSION_CODE }?.themes
    }.getOrNull().orEmpty().also { worn = it }

    /** The theme with [id], or null for the app's own look (or a theme since taken away, such as Rex's, which it had until 0.4.3). */
    fun byId(id: String): RexTheme? {
        if (id == DEFAULT) return null
        // Once they are all read, from there: the same theme is then always the same object.
        if (!read.isInitialized()) worn().firstOrNull { it.id == id }?.let { return it }
        return all.firstOrNull { it.id == id }
    }

    /** "Chompy" for the app's own look, else the theme's name. */
    fun name(id: String): String = byId(id)?.name ?: DEFAULT_NAME

    /** Keep [theme], which the app now wears, for the next launch, in place of the last one as dark or as light. */
    fun keep(theme: RexTheme) {
        val now = worn()
        if (theme in now) return
        val kept = now.filter { it.dark != theme.dark } + theme
        worn = kept
        runCatching { wornFile.writeText(RexJson.encodeToString(Worn.serializer(), Worn(BuildConfig.VERSION_CODE, kept))) }
    }

    @Serializable
    private class Worn(val version: Int, val themes: List<RexTheme>)

    companion object {
        const val ASSET = "themes.json"
        /** In the app's files: the themes it last wore. */
        const val WORN_FILE = "worn-themes.json"
        /** The app's own dark look, from before it had themes. */
        const val DEFAULT = "rex"
        const val DEFAULT_NAME = "Chompy"
        const val DEFAULT_LIGHT = "sandstone"
    }
}
