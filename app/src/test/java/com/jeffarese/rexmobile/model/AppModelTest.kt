package com.jeffarese.rexmobile.model

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import com.jeffarese.rexmobile.WatchService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.FakeRex
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The app's terminals across returns to the app and changes to the hosts, against a fake server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppModelTest {
    private val rex = FakeRex("b1", "b2")
    // An empty saved list, so the store doesn't seed this build's preset hosts beside the fake one.
    private val context: Context = ApplicationProvider.getApplicationContext<Context>().also {
        it.getSharedPreferences("hosts", Context.MODE_PRIVATE).edit().putString("list", "[]").commit()
    }
    private val model = AppModel(context, rex)
    private val config = model.hostStore.add("fake", "fake.test")

    @After
    fun tearDown() {
        model.dropTerminal("b1")
        model.dropTerminal("b2")
        model.connections.value.forEach { it.disconnect() }
        model.scope.cancel()
    }

    private fun host(): HostConnection {
        waitFor("the host's connection") { model.connection(config.id) != null }
        return model.connection(config.id)!!
    }

    @Test
    fun onReturnOnlyTheTerminalOnScreenStreamsAgain() {
        val host = host()
        val shown = model.terminal(host, "s1", "b1")
        val other = model.terminal(host, "s1", "b2")
        // As after "Stay connected after leaving" ran out.
        shown.pause()
        other.pause()
        model.visibleBlockId = "b1"

        model.onForeground()
        assertFalse(shown.paused)
        assertTrue("one not on screen waits until opened", other.paused)

        assertSame(other, model.terminal(host, "s1", "b2"))
        assertFalse(other.paused)
    }

    @Test
    fun aPhoneSizedBlockIsTakenBackAfterAReconnectAndOnReturn() {
        model.onForeground()
        val host = host()
        waitFor("online") { host.state.value.status == ConnStatus.ONLINE }
        val t = model.terminal(host, "s1", "b1")
        t.setPhoneSized(true)
        waitFor("the take") { rex.count("session.own_block") == 1 }

        // A connection made again, maybe as a new client, which Rex gives no blocks back to.
        rex.controls.last().drop()
        waitFor("the take after reconnecting") { rex.count("session.own_block") == 2 }

        // The desktop may have taken it while the phone was away, within the time it stays connected.
        model.onBackground()
        model.onForeground()
        waitFor("the take on return") { rex.count("session.own_block") == 3 }
    }

    @Test
    fun anEditedHostsTerminalsAreClosed() {
        val host = host()
        val t = model.terminal(host, "s1", "b1")
        model.hostStore.update(config.copy(label = "renamed"))
        waitFor("the new connection") { model.connection(config.id).let { it != null && it !== host } }
        pump(100)
        // Forgotten, so asking again for the old connection's starts another.
        val again = model.terminal(host, "s1", "b1")
        assertNotSame(t, again)
    }

    @Test
    fun watchingKeepsTheHostsConnectedAndNotifiesWhileAway() {
        model.settings.watchInBackground.value = true
        model.settings.stayConnectedSeconds.value = 0
        model.onForeground()
        val host = host()
        waitFor("online") { host.state.value.status == ConnStatus.ONLINE }
        val started = shadowOf(context as Application).nextStartedService
        assertEquals(WatchService::class.java.name, started?.component?.className)

        model.onBackground()
        pump(500)
        assertEquals(ConnStatus.ONLINE, host.state.value.status)

        rex.controls.last().blockEvent("b1", "program_status_changed", buildJsonObject {
            putJsonObject("record") { put("id", "claude"); put("state", "blocked"); put("title", "Fix the tests") }
        })
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        waitFor("the notification") { notifications.allNotifications.isNotEmpty() }
        val posted = notifications.allNotifications.single()
        assertEquals("Fix the tests", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Needs you · fake", posted.extras.getString(Notification.EXTRA_SUB_TEXT))

        // Coming back, Home's dots say it instead.
        model.onForeground()
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test
    fun aQuestionInThePaneOnScreenRaisesNoBanner() {
        model.onForeground()
        val host = host()
        waitFor("online") { host.state.value.status == ConnStatus.ONLINE }
        val raised = java.util.concurrent.CopyOnWriteArrayList<Alert>()
        val listening = model.scope.launch { model.alerts.collect { raised += it } }
        model.visibleBlockId = "b1"
        fun blocked(bid: String) = rex.controls.last().blockEvent(bid, "program_status_changed", buildJsonObject {
            putJsonObject("record") { put("id", "claude"); put("state", "blocked"); put("kind", "question") }
        })

        blocked("b1")
        blocked("b2")
        waitFor("the other pane's alert") { raised.isNotEmpty() }
        pump(200)
        assertEquals(listOf("b2"), raised.map { it.blockId })
        listening.cancel()
    }

    @Test
    fun withoutWatchingLeavingClosesTheConnectionsAndNothingIsNotified() {
        model.settings.stayConnectedSeconds.value = 0
        model.onForeground()
        val host = host()
        waitFor("online") { host.state.value.status == ConnStatus.ONLINE }
        model.onBackground()
        waitFor("disconnected") { host.state.value.status == ConnStatus.IDLE }
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    /** Robolectric runs the main looper only when told, so keep it going while real time passes. */
    private fun pump(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            Thread.sleep(25)
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            pump(25)
        }
    }
}
