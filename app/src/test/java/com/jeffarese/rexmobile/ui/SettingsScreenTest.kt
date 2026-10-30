package com.jeffarese.rexmobile.ui

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.RexMobileApp
import androidx.compose.ui.graphics.Color
import com.jeffarese.rexmobile.model.Alert
import com.jeffarese.rexmobile.model.Appearance
import com.jeffarese.rexmobile.model.KeepScreenOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val model = ApplicationProvider.getApplicationContext<RexMobileApp>().model
    private val settings = model.settings

    /** Settings is a lazy list: bring a row into it before finding it. */
    private fun scrollTo(text: String): SemanticsNodeInteraction {
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        return rule.onNodeWithText(text)
    }

    private fun show(onHosts: () -> Unit = {}) =
        rule.setContent { RexTheme { SettingsScreen(model, onBack = {}, onHosts = onHosts) } }

    @Test
    fun keysComeOffTheBarAndBackAndMove() {
        show()
        scrollTo("Keys").performClick()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Hide pgup"))
        rule.onNodeWithContentDescription("Hide pgup").performClick()
        assertEquals(setOf("pgup"), settings.hiddenKeys.value)
        assertFalse("pgup" in settings.shownKeys(KEY_BAR_IDS))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Show pgup"))
        rule.onNodeWithContentDescription("Show pgup").performClick()
        assertEquals(emptySet<String>(), settings.hiddenKeys.value)
        // Put back at the end.
        assertEquals("pgup", settings.shownKeys(KEY_BAR_IDS).last())
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Move ^C  ·  Control C up"))
        rule.onNodeWithContentDescription("Move ^C  ·  Control C up").performClick()
        assertEquals(listOf("^C", "esc"), settings.shownKeys(KEY_BAR_IDS).take(2))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Reset to the defaults"))
        rule.onNodeWithText("Reset to the defaults").performClick()
        assertEquals(KEY_BAR_IDS, settings.shownKeys(KEY_BAR_IDS))
    }

    @Test
    fun theStripCanBeArranged() {
        show()
        scrollTo("Claude's strip").performClick()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Show /cost"))
        rule.onNodeWithContentDescription("Show /cost").performClick()
        assertEquals(DEFAULT_STRIP + "/cost", settings.stripItems.value)
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Hide Effort"))
        rule.onNodeWithContentDescription("Hide Effort").performClick()
        assertFalse(STRIP_EFFORT in settings.stripItems.value!!)
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Reset to the defaults"))
        rule.onNodeWithText("Reset to the defaults").performClick()
        assertEquals(null, settings.stripItems.value)
    }

    @Test
    fun aThemeIsWornAtOnceAndALightOneMakesTheAppLight() {
        show()
        scrollTo("Theme").performClick()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Whitby Bay"))
        rule.onNodeWithText("Whitby Bay").performClick()
        rule.waitForIdle()
        assertEquals("whitby-bay", settings.themeDark.value)
        assertEquals(Color(0xFF272A38), Rex.bg)
        assertTrue(Rex.palette.dark)

        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Alpine Milk"))
        rule.onNodeWithText("Alpine Milk").performClick()
        rule.waitForIdle()
        assertEquals(Appearance.LIGHT, settings.appearance.value)
        assertEquals("alpine-milk", settings.themeLight.value)
        assertFalse(Rex.palette.dark)
        assertEquals(Color(0xFFFAFAFA), Rex.bg)

        // Back to the app's own look.
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("This app's own"))
        rule.onNodeWithText("This app's own").performClick()
        rule.waitForIdle()
        assertEquals(Appearance.DARK, settings.appearance.value)
        assertEquals(Palette.Default, Rex.palette)
    }

    @Test
    fun bannerKindsCanBeMuted() {
        show()
        scrollTo("Bell").performClick()
        assertFalse(settings.alertShown(Alert.Kind.BELL))
        assertTrue(settings.alertShown(Alert.Kind.BLOCKED))
    }

    @Test
    fun switchRowsToggleTheirSetting() {
        show()
        scrollTo("Haptic feedback").performClick()
        assertFalse(settings.haptics.value)
        scrollTo("Fit to this phone").performClick()
        assertFalse(settings.phoneMode.value)
        scrollTo("Vibrate").performClick()
        assertFalse(settings.alertVibrate.value)
    }

    @Test
    fun textSizeStepper() {
        show()
        rule.onNodeWithText("13").assertExists()
        rule.onNodeWithContentDescription("Larger").performClick()
        rule.onNodeWithText("14").assertExists()
        assertEquals(14, settings.textSizeSp())
    }

    @Test
    fun choicesOpenAMenu() {
        show()
        rule.onNodeWithText("Keep the screen on").performClick()
        rule.onNodeWithText("Always").performClick()
        assertEquals(KeepScreenOn.ALWAYS, settings.keepScreenOn.value)

        scrollTo("Stay connected after leaving").performClick()
        rule.onNodeWithText("15 minutes").performClick()
        assertEquals(900, settings.stayConnectedSeconds.value)
    }

    @Test
    fun hostsRowOpensHosts() {
        var opened = false
        show(onHosts = { opened = true })
        rule.onNodeWithText("Hosts").performClick()
        assertTrue(opened)
    }

    @Test
    fun licensesPageShowsTheEngineAndFontLicenses() {
        show()
        scrollTo("Open-source licenses").performClick()
        rule.onNodeWithText("libghostty-vt, from Ghostty").assertExists()
        for (license in listOf("Mitchell Hashimoto", "UNICODE LICENSE V3", "SIL OPEN FONT LICENSE")) {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(license, substring = true))
            rule.onNodeWithText(license, substring = true).assertExists()
        }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Settings").assertExists()
    }

    @Test
    fun comingBackFromAPageKeepsTheScrollPosition() {
        show()
        scrollTo("Open-source licenses").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        // Still scrolled down: the licenses row is on screen and the first card isn't.
        rule.onNodeWithText("Open-source licenses").assertIsDisplayed()
        rule.onNodeWithText("Hosts").assertIsNotDisplayed()
    }

    @Test
    fun hapticsOffSilencesComposeHaptics() {
        lateinit var seen: HapticFeedback
        lateinit var system: HapticFeedback
        settings.haptics.value = false
        rule.setContent {
            system = LocalHapticFeedback.current
            HapticsFromSettings(settings) { seen = LocalHapticFeedback.current }
        }
        assertNotSame(system, seen)
        settings.haptics.value = true
        rule.waitForIdle()
        assertSame(system, seen)
    }

    @Test
    fun sectionTitlesAreHeadings() {
        show()
        rule.onNode(hasText("Look") and isHeading()).assertExists()
        rule.onNode(hasText("Hosts") and isHeading()).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1000dp-h700dp-xhdpi")
    fun onAWideScreenTheCardsStayReadablyNarrowAndCentered() {
        show()
        // The Hosts row, a button as wide as its card, as wide as the column.
        val row = rule.onNodeWithText("Hosts").getUnclippedBoundsInRoot()
        assertEquals(180f, row.left.value, 1f)
        assertEquals(820f, row.right.value, 1f)
    }

    @Test
    fun keyBarLeavesOutHiddenKeys() {
        val controls = TerminalControls(object : TerminalSink {
            override fun key(code: Int, ctrl: Boolean, alt: Boolean, shift: Boolean) = Unit
            override fun bytes(bytes: ByteArray) = Unit
            override fun paste(text: String) = Unit
            override fun choose(prompt: com.jeffarese.rexmobile.terminal.ScreenPrompt, option: com.jeffarese.rexmobile.terminal.PromptOption) = Unit
        }, StickyModifiers())
        rule.setContent { RexTheme { KeyBar(controls, directTyping = false, onPaste = {}, onToggleTyping = {}, keys = KEY_BAR_IDS - setOf("pgup", "~")) } }
        rule.onNodeWithText("pgup").assertDoesNotExist()
        rule.onNodeWithText("~").assertDoesNotExist()
        rule.onNodeWithText("pgdn").assertExists()
        rule.onNodeWithText("esc").assertExists()
    }
}

class ReflowTest {
    @Test
    fun joinsWrappedLinesButKeepsParagraphsAndHeadings() {
        val text = "-----\nSIL OPEN FONT LICENSE\n-----\n\nPREAMBLE\nThe goals of the\nlicense are\n\n1) Neither the\nsoftware\n"
        assertEquals(
            "-----\nSIL OPEN FONT LICENSE\n-----\n\nPREAMBLE\nThe goals of the license are\n\n1) Neither the software",
            reflow(text),
        )
    }
}
