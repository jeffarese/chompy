package com.jeffarese.rexmobile.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The path every update starts on: the app's cold start, Home with a host's tabs, a terminal opened
 * from there and its history scrolled. ART compiles the code these touch at install time, from the
 * profile this writes to app/src/main/generated/baselineProfiles.
 *
 * It needs the app built with the demo server of docs/demo as its only host, and a session there with
 * a window named [TAB] and a long history: scripts/generate-baseline-profile.sh sets both up.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startHomeAndTerminal() = rule.collect(packageName = PACKAGE) {
        // So Android doesn't ask for it over Home.
        device.executeShellCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS")
        pressHome()
        startActivityAndWait()

        assertNotNull("Home lists no $TAB tab", device.wait(Until.findObject(By.text(TAB)), 15_000))
        device.findObject(By.text(TAB)).click()
        // The key bar shows once the terminal screen is up; its history comes from the server after.
        assertNotNull("the terminal didn't open", device.wait(Until.findObject(By.text("esc")), 15_000))
        Thread.sleep(2_000)

        val x = device.displayWidth / 2
        val top = device.displayHeight * 3 / 10
        val bottom = device.displayHeight * 7 / 10
        // Back through the history, slowly and with a fling, then down again.
        repeat(3) { device.swipe(x, top, x, bottom, 20) }
        device.swipe(x, top, x, bottom, 4)
        device.waitForIdle()
        repeat(2) { device.swipe(x, bottom, x, top, 20) }
        device.waitForIdle()

        device.pressBack()
        device.wait(Until.findObject(By.text(TAB)), 5_000)
    }

    private companion object {
        const val PACKAGE = "com.jeffarese.rexmobile"
        const val TAB = "bench-history"
    }
}
