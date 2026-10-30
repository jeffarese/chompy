package com.jeffarese.rexmobile

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's state survives opening another screen and coming back: its filter (which is also saved) and scroll position. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class HomeStateTest {
    @get:Rule(order = 0) val skipWelcome = SkipWelcome()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun agentsOnlyFilterSurvivesNavigation() {
        rule.onNodeWithContentDescription("Show agents only").performClick()
        rule.onNodeWithContentDescription("Show all tabs").assertExists()

        rule.onNodeWithContentDescription("Settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Show all tabs").assertExists()
        val prefs = rule.activity.getSharedPreferences("ui", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("watch_only", false))
    }

    @Test
    fun scrollPositionSurvivesNavigation() {
        val model = (rule.activity.application as RexMobileApp).model
        val hosts = (1..12).map { model.hostStore.add("Mac $it", "127.0.0.1:$it") }
        rule.waitUntil(5_000) { model.hostStates.value.any { it.first.id == hosts.last().id } }
        rule.onNode(hasScrollToKeyAction()).performScrollToKey("host-${hosts.last().id}")
        rule.onNodeWithText("Mac 12").assertIsDisplayed()

        goToSettingsAndBack()

        rule.onNodeWithText("Mac 12").assertIsDisplayed()
    }

    @Test
    fun aDoubleTapOpensOneScreen() {
        // Both taps land before a frame, as a quick double tap does.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithText("Settings").assertExists()

        // One Back is home.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Settings").assertDoesNotExist()
        rule.onNodeWithContentDescription("Show agents only").assertExists()
    }

    @Test
    fun backClosesSearchBeforeLeaving() {
        rule.onNodeWithContentDescription("Search").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("claude")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        assertFalse(rule.activity.isFinishing)
    }

    private fun goToSettingsAndBack() {
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
    }
}
