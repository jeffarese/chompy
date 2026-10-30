package com.jeffarese.rexmobile.ui

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.RexMobileApp
import com.jeffarese.rexmobile.model.Notifier
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settings › Alerts leaves each kind's sound and pop-up to Android's settings for its channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class AlertSettingsTest {
    @get:Rule val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<RexMobileApp>()
    private val model = app.model

    @Test
    fun eachKindOpensItsChannelInAndroidsSettings() {
        model.settings.watchInBackground.value = true
        rule.setContent { RexTheme { SettingsScreen(model, onBack = {}, onHosts = {}) } }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Finished and failed"))
        rule.onNodeWithText("Finished and failed").performClick()
        val opened = shadowOf(app as Application).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, opened.action)
        assertEquals(app.packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(Notifier.CHANNEL_RESULTS, opened.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }
}
