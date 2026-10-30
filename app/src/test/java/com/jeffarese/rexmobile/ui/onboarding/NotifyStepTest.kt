package com.jeffarese.rexmobile.ui.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.RexMobileApp
import com.jeffarese.rexmobile.ui.RexTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The welcome's last step, once a host is added: Notify when away, or not now. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class NotifyStepTest {
    @get:Rule val rule = createComposeRule()

    private val model = ApplicationProvider.getApplicationContext<RexMobileApp>().model
    private var done = 0

    @After
    fun off() {
        model.settings.watchInBackground.value = false
    }

    private fun show() = rule.setContent { RexTheme { NotifyStep(model, onDone = { done++ }) } }

    @Test
    fun notifyMeTurnsOnNotifyWhenAway() {
        show()
        rule.onNodeWithText("Notify me").performClick()
        assertTrue(model.settings.watchInBackground.value)
        rule.onNodeWithText("Notify when away is on").assertExists()
        assertEquals(0, done)
        rule.onNodeWithText("Open Chompy").performClick()
        assertEquals(1, done)
    }

    @Test
    fun notNowLeavesItOff() {
        show()
        rule.onNodeWithText("Not now").performClick()
        assertFalse(model.settings.watchInBackground.value)
        assertEquals(1, done)
    }
}
