package com.jeffarese.rexmobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jeffarese.rexmobile.terminal.ClassicSwitch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The offer to switch Claude out of fullscreen: its buttons, progress and failures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class ClassicSwitchBannerTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun offersTheSwitchThenShowsHowItGoes() {
        var state by mutableStateOf<ClassicSwitch.State>(ClassicSwitch.State.Idle)
        var switched = 0
        var dismissed = 0
        rule.setContent { ClassicSwitchBanner(state, onSwitch = { switched++ }, onDismiss = { dismissed++ }) }

        rule.onNodeWithText("Claude is in fullscreen", substring = true).assertIsDisplayed()
        // It says Claude restarts, and what survives, so the pane going blank for a moment isn't a surprise.
        rule.onNodeWithText("Same conversation", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Switch").performClick()
        assertEquals(1, switched)

        state = ClassicSwitch.State.Working("Quitting Claude…")
        rule.onNodeWithText("Quitting Claude…").assertIsDisplayed()
        rule.onNodeWithText("Switch").assertDoesNotExist()

        state = ClassicSwitch.State.Failed("Claude is working. Switch once it has finished.")
        rule.onNodeWithText("Claude is working", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(2, switched)
        rule.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(1, dismissed)
    }
}
