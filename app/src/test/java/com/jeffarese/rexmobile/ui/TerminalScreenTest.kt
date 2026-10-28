package com.jeffarese.rexmobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.RexMobileApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opens the terminal screen for a host that isn't connected, so a crash in its layout fails here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class TerminalScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun opensWithoutAServer() {
        val model = ApplicationProvider.getApplicationContext<RexMobileApp>().model
        val host = model.hostStore.add("nowhere", "127.0.0.1:1")
        rule.waitUntil(5_000) { model.connection(host.id) != null }
        var back = false
        rule.setContent { RexTheme { TerminalScreen(model, Target(host.id, "session", "window", null), onBack = { back = true }) } }
        // Not connected, so nothing typed can go yet: input waits, and says why.
        rule.onNodeWithText("Waiting for the connection…").assertIsDisplayed()
        rule.onNodeWithText("^C").assertIsDisplayed()
        rule.onNodeWithText("esc").assertIsDisplayed()
        rule.onNodeWithContentDescription("Escape").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Press Enter").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Copy screen").assertIsDisplayed()
        rule.onNodeWithText("Copy all text").assertIsDisplayed()
        assertFalse(back)
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun inLandscapeTheHeaderFoldsWithoutChangingTheSetting() {
        val model = ApplicationProvider.getApplicationContext<RexMobileApp>().model
        val host = model.hostStore.add("nowhere", "127.0.0.1:1")
        rule.waitUntil(5_000) { model.connection(host.id) != null }
        assertFalse(model.settings.terminalHeaderCollapsed.value)
        rule.setContent { RexTheme { TerminalScreen(model, Target(host.id, "session", "window", null), onBack = {}) } }
        rule.onNodeWithContentDescription("More").assertDoesNotExist()
        // A tap unfolds it for now, and folding it again leaves the setting as it was.
        rule.onNodeWithContentDescription("Show the header").performClick()
        rule.onNodeWithContentDescription("More").assertIsDisplayed()
        rule.onNodeWithContentDescription("Hide the header").performClick()
        rule.onNodeWithContentDescription("More").assertDoesNotExist()
        assertFalse(model.settings.terminalHeaderCollapsed.value)
    }

    @Test
    fun theHeaderFoldsToOneLineAndBack() {
        val model = ApplicationProvider.getApplicationContext<RexMobileApp>().model
        val host = model.hostStore.add("nowhere", "127.0.0.1:1")
        rule.waitUntil(5_000) { model.connection(host.id) != null }
        rule.setContent { RexTheme { TerminalScreen(model, Target(host.id, "session", "window", null), onBack = {}) } }
        rule.onNodeWithContentDescription("Hide the header").performClick()
        assertTrue(model.settings.terminalHeaderCollapsed.value)
        rule.onNodeWithContentDescription("More").assertDoesNotExist()
        rule.onNodeWithContentDescription("Show the header").performClick()
        assertFalse(model.settings.terminalHeaderCollapsed.value)

        // Fitting to the phone moved into the menu.
        val fit = model.settings.phoneMode.value
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Fit to this phone").performClick()
        assertEquals(!fit, model.settings.phoneMode.value)
    }
}
