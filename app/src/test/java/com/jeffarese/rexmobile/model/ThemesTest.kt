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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

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
    fun rexsThemesAreAllThere() {
        val all = model.themes.all
        assertEquals(41, all.size)
        assertEquals(27, all.count { it.dark })
        val whitby = model.themes.byId("whitby-bay")!!
        assertEquals(TextStyle.NUM_INDEXED_COLORS, whitby.terminalColors.size)
        assertEquals(0xff2c2f3c.toInt(), whitby.terminalColors[TextStyle.COLOR_INDEX_BACKGROUND])
        assertNull(model.themes.byId(Themes.DEFAULT))
        assertEquals("Chompy", model.themes.name(Themes.DEFAULT))
    }

    @Test
    fun theAppStartsInItsOwnLookWithTheMacsTerminalColors() {
        assertEquals(Palette.Default, Rex.palette)
        assertNull(RemoteTerminal.themeColors)
    }

    @Test
    fun aThemeColorsTheAppAndTheTerminalsUnlessTheyKeepTheMacs() {
        model.settings.themeDark.value = "whitby-bay"
        waitFor("Whitby Bay") { Rex.bg == Color(0xFF272A38) }
        assertSame(model.themes.byId("whitby-bay")!!.terminalColors, RemoteTerminal.themeColors)

        model.settings.terminalColorsFromTheme.value = false
        waitFor("the Mac's terminal colors") { RemoteTerminal.themeColors == null }
        assertEquals(Color(0xFF272A38), Rex.bg)
    }

    @Test
    fun asThePhoneIsFollowsDarkMode() {
        model.settings.appearance.value = Appearance.SYSTEM
        model.settings.themeLight.value = "alpine-milk"
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
}
