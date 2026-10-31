package com.jeffarese.rexmobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The note on lagging scrolls with Claude in fullscreen: it only explains, and can be put away. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class ScrollLagNoteTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun explainsAndOffersNothingToSwitch() {
        var dismissed = 0
        var never = 0
        rule.setContent { ScrollLagNote(onDismiss = { dismissed++ }, onNever = { never++ }) }

        rule.onNodeWithText("Scrolling lags because Claude is in fullscreen", substring = true).assertIsDisplayed()
        rule.onNodeWithText("classic mode", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Switch", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Restart", substring = true).assertDoesNotExist()

        rule.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(1, dismissed)
        rule.onNodeWithText("Don't show again").performClick()
        assertEquals(1, never)
    }
}
