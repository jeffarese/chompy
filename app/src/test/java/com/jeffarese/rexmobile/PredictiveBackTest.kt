package com.jeffarese.rexmobile

import androidx.activity.BackEventCompat
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A back swipe shows the screen under while it lasts, goes there once it ends, and springs back when let go of. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class PredictiveBackTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val back get() = rule.activity.onBackPressedDispatcher

    private fun swipe(progress: Float) = BackEventCompat(touchX = 0f, touchY = 500f, progress = progress, swipeEdge = BackEventCompat.EDGE_LEFT)

    /** Settings, with half a back swipe made over it. */
    private fun halfwayBackFromSettings() {
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.waitForIdle()
        rule.runOnUiThread {
            back.dispatchOnBackStarted(swipe(0f))
            back.dispatchOnBackProgressed(swipe(0.5f))
        }
        rule.waitForIdle()
        // Settings, with Home showing through.
        rule.onNodeWithText("Settings").assertExists()
        rule.onNodeWithContentDescription("Show agents only").assertExists()
    }

    @Test
    fun aFinishedSwipeGoesBack() {
        halfwayBackFromSettings()
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Settings").assertDoesNotExist()
        rule.onNodeWithContentDescription("Show agents only").assertExists()
    }

    @Test
    fun aSwipeLetGoOfSpringsBack() {
        halfwayBackFromSettings()
        rule.runOnUiThread { back.dispatchOnBackCancelled() }
        rule.waitForIdle()
        rule.onNodeWithText("Settings").assertExists()
        rule.onNodeWithContentDescription("Show agents only").assertDoesNotExist()
        // And Back still works afterwards.
        rule.runOnUiThread { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Settings").assertDoesNotExist()
    }
}
