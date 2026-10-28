package com.jeffarese.rexmobile

import android.os.Looper
import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Starts the app the way a launcher does, so a crash on open fails here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTest {
    @Test
    fun opensHome() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(5) { shadowOf(Looper.getMainLooper()).idle() }
            scenario.onActivity { check(!it.isFinishing) }
        }
    }
}
