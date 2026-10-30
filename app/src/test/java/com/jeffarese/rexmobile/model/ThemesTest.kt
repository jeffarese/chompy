package com.jeffarese.rexmobile.model

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.FakeRex
import com.jeffarese.rexmobile.terminal.RemoteTerminal
import com.jeffarese.rexmobile.ui.Palette
import com.jeffarese.rexmobile.ui.Rex
import com.jeffarese.rexmobile.terminal.emulator.TextStyle
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val model = AppModel(context, FakeRex())

    @After
    fun tearDown() {
        model.scope.cancel()
        Rex.wear(Palette.Default)
        RemoteTerminal.themeColors = null
    }

    /** The theme is worked out off the main thread and worn on it: keep the main looper going until [done]. */
    private fun waitFor(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!done()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun theThemesAreAllThere() {
        val all = model.themes.all
        assertEquals(15, all.size)
        assertEquals(9, all.count { it.dark })
        val ocean = model.themes.byId("panthalassa")!!
        assertEquals(TextStyle.NUM_INDEXED_COLORS, ocean.terminalColors.size)
        assertEquals(0xff091a30.toInt(), ocean.terminalColors[TextStyle.COLOR_INDEX_BACKGROUND])
        assertEquals("Sandstone", model.themes.name(Themes.DEFAULT_LIGHT))
        assertNull(model.themes.byId(Themes.DEFAULT))
        assertEquals("Chompy", model.themes.name(Themes.DEFAULT))
    }

    @Test
    fun aThemeTheAppNoLongerHasGivesWayToItsOwnLook() {
        // The model every test has made has looked already: as an app that hadn't yet, with themes chosen before 0.4.3,
        // from Rex's.
        val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        waitFor("the first look") { prefs.getBoolean(Settings.LOST_THEMES_FORGOTTEN, false) }
        prefs.edit().remove(Settings.LOST_THEMES_FORGOTTEN)
            .putString(Settings.THEME_DARK, "whitby-bay").putString(Settings.THEME_LIGHT, "alpine-milk").commit()
        val next = AppModel(context, FakeRex())
        try {
            waitFor("the app's own look") { next.settings.themeDark.value == Themes.DEFAULT }
            waitFor("the default light theme") { next.settings.themeLight.value == Themes.DEFAULT_LIGHT }
        } finally {
            next.scope.cancel()
        }
    }

    @Test
    fun theAppStartsInItsOwnLookWithTheMacsTerminalColors() {
        assertEquals(Palette.Default, Rex.palette)
        assertNull(RemoteTerminal.themeColors)
    }

    @Test
    fun aThemeColorsTheAppAndTheTerminalsUnlessTheyKeepTheMacs() {
        model.settings.themeDark.value = "panthalassa"
        waitFor("Panthalassa") { Rex.bg == Color(0xFF091A30) }
        assertSame(model.themes.byId("panthalassa")!!.terminalColors, RemoteTerminal.themeColors)

        model.settings.terminalColorsFromTheme.value = false
        waitFor("the Mac's terminal colors") { RemoteTerminal.themeColors == null }
        assertEquals(Color(0xFF091A30), Rex.bg)
    }

    @Test
    fun asThePhoneIsFollowsDarkMode() {
        model.settings.appearance.value = Appearance.SYSTEM
        model.settings.themeLight.value = "chalk"
        val light = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        model.onConfigurationChanged(light)
        waitFor("light") { !Rex.palette.dark }
        val dark = Configuration(light).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES }
        model.onConfigurationChanged(dark)
        waitFor("dark") { Rex.palette.dark }
        assertEquals(Palette.Default, Rex.palette)
    }

    @Test
    fun theAppsOwnLookReadsNoThemes() {
        assertFalse(model.themes.allRead)
    }

    @Test
    fun aThemeWornIsKeptSoTheNextLaunchStartsInItWithoutReadingThemAll() {
        model.settings.themeDark.value = "panthalassa"
        waitFor("Panthalassa kept") { File(context.filesDir, Themes.WORN_FILE).exists() }
        model.settings.appearance.value = Appearance.LIGHT
        model.settings.themeLight.value = "chalk"
        waitFor("Chalk kept") { File(context.filesDir, Themes.WORN_FILE).readText().contains("chalk") }

        // Launched again, light: in Chalk from the start, with Panthalassa still kept for dark.
        val next = AppModel(context, FakeRex())
        try {
            val bg = Rex.bg
            assertEquals("Panthalassa", next.themes.byId("panthalassa")?.name)
            assertFalse(next.themes.allRead)
            assertEquals(Color(next.themes.all.first { it.id == "chalk" }.color("canvas")), bg)
        } finally {
            next.scope.cancel()
        }
    }
}
