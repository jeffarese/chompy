package com.jeffarese.rexmobile.model

import android.content.Context
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class SettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("settings-test", Context.MODE_PRIVATE)

    private fun px(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics).toInt()

    @Test
    fun defaultsAreHowTheAppBehavedBefore() {
        val s = Settings(context, prefs)
        assertEquals(px(13f), s.textSizePx.value)
        assertEquals(13, s.textSizeSp())
        assertTrue(s.phoneMode.value)
        assertEquals(KeepScreenOn.WORKING, s.keepScreenOn.value)
        assertTrue(s.haptics.value)
        assertEquals(emptySet<String>(), s.hiddenKeys.value)
        assertTrue(Alert.Kind.entries.all { s.alertShown(it) })
        assertTrue(s.alertVibrate.value)
        assertEquals(90, s.stayConnectedSeconds.value)
    }

    @Test
    fun readsWhatTheTerminalScreenSavedBeforeSettingsExisted() {
        prefs.edit().putInt("phone_text_px", px(17f)).putBoolean("phone_mode", false).apply()
        val s = Settings(context, prefs)
        assertEquals(px(17f), s.textSizePx.value)
        assertEquals(17, s.textSizeSp())
        assertFalse(s.phoneMode.value)
    }

    @Test
    fun changesAreSavedForTheNextLaunch() {
        val s = Settings(context, prefs)
        s.keepScreenOn.value = KeepScreenOn.ALWAYS
        s.haptics.value = false
        s.hiddenKeys.value = setOf("pgup", "~")
        s.mutedAlerts.value = setOf(Alert.Kind.BELL, Alert.Kind.DONE)
        s.alertVibrate.value = false
        s.stayConnectedSeconds.value = 300

        val again = Settings(context, prefs)
        assertEquals(KeepScreenOn.ALWAYS, again.keepScreenOn.value)
        assertFalse(again.haptics.value)
        assertEquals(setOf("pgup", "~"), again.hiddenKeys.value)
        assertFalse(again.alertShown(Alert.Kind.BELL))
        assertFalse(again.alertShown(Alert.Kind.DONE))
        assertTrue(again.alertShown(Alert.Kind.BLOCKED))
        assertFalse(again.alertVibrate.value)
        assertEquals(300, again.stayConnectedSeconds.value)
    }

    @Test
    fun keysAndTheStripKeepTheirOrder() {
        val all = listOf("esc", "^C", "↑", "pgup")
        val s = Settings(context, prefs)
        assertEquals(all, s.shownKeys(all))
        s.showKeys(listOf("pgup", "esc"), all)
        assertEquals(setOf("^C", "↑"), s.hiddenKeys.value)
        s.stripItems.value = listOf("/cost", "mode")

        val again = Settings(context, prefs)
        assertEquals(listOf("pgup", "esc"), again.shownKeys(all))
        // A key added in a later version shows, at the end; one that went away drops out.
        assertEquals(listOf("pgup", "esc", "new"), again.shownKeys(listOf("esc", "^C", "↑", "pgup", "new")))
        assertEquals(listOf("esc"), again.shownKeys(listOf("esc", "^C")))
        assertEquals(listOf("/cost", "mode"), again.stripItems.value)
        again.stripItems.value = null
        assertEquals(null, Settings(context, prefs).stripItems.value)
    }

    @Test
    fun unknownSavedValuesFallBackToTheDefault() {
        prefs.edit().putString("keep_screen_on", "SOMETIMES").putStringSet("alerts_muted", setOf("BELL", "GONE")).apply()
        val s = Settings(context, prefs)
        assertEquals(KeepScreenOn.WORKING, s.keepScreenOn.value)
        assertEquals(setOf(Alert.Kind.BELL), s.mutedAlerts.value)
    }

    @Test
    fun textSizeStepsByWholeSpWithinTheTerminalsRange() {
        val s = Settings(context, prefs)
        s.stepTextSize(1)
        assertEquals(px(14f), s.textSizePx.value)
        s.stepTextSize(-3)
        assertEquals(px(11f), s.textSizePx.value)
        s.stepTextSize(100)
        assertEquals(s.maxTextPx, s.textSizePx.value)
        assertEquals(24, s.textSizeSp())
        s.stepTextSize(-100)
        assertEquals(s.minTextPx, s.textSizePx.value)
    }

    @Test
    fun aPinchedSizeShowsAsTheNearestWholeSp() {
        val s = Settings(context, prefs)
        // Pinching stops anywhere, such as just over 15sp.
        s.textSizePx.value = px(15f) + 1
        assertEquals(15, s.textSizeSp())
        s.stepTextSize(1)
        assertEquals(px(16f), s.textSizePx.value)
    }

    @Test
    fun watchersSeeChanges() {
        val s = Settings(context, prefs)
        val flow = s.phoneMode.flow
        s.phoneMode.value = false
        assertFalse(flow.value)
    }

    @Test
    fun dismissedLagNotesAreKeptForTheLatestBlocks() {
        val s = Settings(context, prefs)
        (1..60).forEach { s.dismissLagNote("b$it") }
        // Dismissed again, an old one counts as the latest.
        s.dismissLagNote("b20")

        val next = Settings(context, prefs).lagNoteDismissed.value
        assertEquals(Settings.MAX_LAG_NOTE_DISMISSED, next.size)
        assertEquals("b20", next.last())
        assertTrue("b60" in next)
        assertFalse("b10" in next)
        assertTrue("b11" in next)
    }

    @Test
    fun stayConnectedLabels() {
        assertEquals("Disconnect right away", Settings.stayConnectedLabel(0))
        assertEquals("90 seconds", Settings.stayConnectedLabel(90))
        assertEquals("1 minute", Settings.stayConnectedLabel(60))
        assertEquals("15 minutes", Settings.stayConnectedLabel(900))
        assertTrue(90 in Settings.STAY_CONNECTED_CHOICES)
    }
}
