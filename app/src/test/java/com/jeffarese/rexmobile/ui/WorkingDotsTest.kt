package com.jeffarese.rexmobile.ui

import android.provider.Settings
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The working dots step a few times a second, so a screen of working agents doesn't keep the display at full rate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkingDotsTest {
    @get:Rule val rule = createComposeRule()

    private val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    @After
    fun tearDown() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    /** How many times something on screen changed over a second, after the dots have settled in. */
    private fun changesInASecond(): Int {
        rule.mainClock.autoAdvance = false
        rule.setContent { WorkingDots() }
        rule.mainClock.advanceTimeBy(1_000)
        var changes = 0
        val observer = Snapshot.registerApplyObserver { changed, _ -> if (changed.isNotEmpty()) changes++ }
        try {
            rule.mainClock.advanceTimeBy(1_000)
        } finally {
            observer.dispose()
        }
        return changes
    }

    @Test
    fun theyChangeAFewTimesASecondRatherThanEveryFrame() {
        val changes = changesInASecond()
        assertTrue("$changes changes", changes in 3..4)
    }

    @Test
    fun theyStandStillWithAnimationsOff() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertEquals(0, changesInASecond())
    }

    @Test
    fun theLitDotRunsLeftToRight() {
        assertEquals(listOf(1f, 0.25f, 0.55f), (0..2).map { dotAlpha(it, lit = 0) })
        assertEquals(listOf(0.55f, 1f, 0.25f), (0..2).map { dotAlpha(it, lit = 1) })
        assertEquals(listOf(0.25f, 0.55f, 1f), (0..2).map { dotAlpha(it, lit = 2) })
    }
}
