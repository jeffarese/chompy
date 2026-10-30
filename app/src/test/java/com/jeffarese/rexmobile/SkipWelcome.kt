package com.jeffarese.rexmobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.ui.onboarding.FirstRun
import org.junit.rules.ExternalResource

/**
 * Opens on Home rather than the welcome, as a phone that has been set up does. Without it, what a test
 * sees depends on the hosts the build was given (rex.defaultHosts), which a fresh clone has none of.
 * It must run before the rule that launches the activity.
 */
class SkipWelcome : ExternalResource() {
    override fun before() {
        FirstRun(ApplicationProvider.getApplicationContext<Context>()).markDone()
    }
}
