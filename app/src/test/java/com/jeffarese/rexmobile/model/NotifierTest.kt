package com.jeffarese.rexmobile.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The notifications' channels, and how a pane's alerts are spaced out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val system = context.getSystemService(NotificationManager::class.java)
    private var now = 1_000_000L

    private fun notifier() = Notifier(context) { now }

    private fun alert(block: String, kind: Alert.Kind) = Alert("h1", "s1", block, kind, "Fix the tests", null)

    private fun posted(block: String): Notification = shadowOf(system).getNotification(block, Notifier.ALERT_ID)

    /** Posted without a sound or pop-up of its own, as NotificationCompat silences one. */
    private val Notification.quiet get() = group == NotificationCompat.GROUP_KEY_SILENT

    @Test
    fun eachKindHasAChannelOfItsOwn() {
        system.createNotificationChannel(NotificationChannel("alerts", "Agents and programs", NotificationManager.IMPORTANCE_HIGH))
        val notifier = notifier()
        assertNull("the one channel alerts went to before", system.getNotificationChannel("alerts"))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, system.getNotificationChannel(Notifier.CHANNEL_NEEDS_YOU).importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, system.getNotificationChannel(Notifier.CHANNEL_RESULTS).importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, system.getNotificationChannel(Notifier.CHANNEL_PROGRAMS).importance)

        notifier.post(alert("b1", Alert.Kind.BLOCKED), "mac")
        notifier.post(alert("b2", Alert.Kind.DONE), "mac")
        notifier.post(alert("b3", Alert.Kind.ERROR), "mac")
        notifier.post(alert("b4", Alert.Kind.NOTIFICATION), "mac")
        assertEquals(Notifier.CHANNEL_NEEDS_YOU, posted("b1").channelId)
        assertEquals(Notifier.CHANNEL_RESULTS, posted("b2").channelId)
        assertEquals(Notifier.CHANNEL_RESULTS, posted("b3").channelId)
        assertEquals(Notifier.CHANNEL_PROGRAMS, posted("b4").channelId)
        assertEquals("Needs you · mac", posted("b1").extras.getString(Notification.EXTRA_SUB_TEXT))
    }

    @Test
    fun aPaneAlertsAtMostOnceEveryThirtySeconds() {
        val notifier = notifier()
        notifier.post(alert("b1", Alert.Kind.BLOCKED), "mac")
        assertFalse(posted("b1").quiet)

        // An agent flipping states: each post replaces the last, quietly.
        now += 5_000
        notifier.post(alert("b1", Alert.Kind.DONE), "mac")
        assertEquals(1, shadowOf(system).size())
        assertEquals(Notifier.CHANNEL_RESULTS, posted("b1").channelId)
        assertTrue(posted("b1").quiet)
        now += 20_000
        notifier.post(alert("b1", Alert.Kind.BLOCKED), "mac")
        assertTrue(posted("b1").quiet)

        // Another pane alerts as ever.
        notifier.post(alert("b2", Alert.Kind.DONE), "mac")
        assertFalse(posted("b2").quiet)

        // Once the time has passed since the pane last alerted, it alerts again.
        now += 6_000
        notifier.post(alert("b1", Alert.Kind.ERROR), "mac")
        assertFalse(posted("b1").quiet)

        // As it does once the user has looked.
        now += 1_000
        notifier.cancel("b1")
        notifier.post(alert("b1", Alert.Kind.BLOCKED), "mac")
        assertFalse(posted("b1").quiet)
    }
}
