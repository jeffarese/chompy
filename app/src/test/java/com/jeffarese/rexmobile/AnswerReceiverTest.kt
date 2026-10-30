package com.jeffarese.rexmobile

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.model.Alert
import com.jeffarese.rexmobile.model.AppModel
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConnection
import com.jeffarese.rexmobile.model.Notifier
import com.jeffarese.rexmobile.model.PanePrompt
import com.jeffarese.rexmobile.model.PanePrompts
import com.jeffarese.rexmobile.rex.obj
import com.jeffarese.rexmobile.rex.str
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Claude Code needing the user while the app is away, as a notification with its prompt's answers,
 * and a tap on one of them, against a fake server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerReceiverTest {
    private val rex = FakeRex("b1").apply {
        columns = 46
        rows = 30
        block("b1").screen = vt("claude-permission-bash-staged")
    }
    // An empty saved list, so the store doesn't seed this build's preset hosts beside the fake one.
    private val context: Context = ApplicationProvider.getApplicationContext<Context>().also {
        it.getSharedPreferences("hosts", Context.MODE_PRIVATE).edit().putString("list", "[]").commit()
    }
    private val model = AppModel(context, rex)
    private val config = model.hostStore.add("fake", "fake.test")
    private val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
    /** The keys pressed in the pane, as "digit_4 press". */
    private val pressed = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        model.connections.value.forEach { it.disconnect() }
        model.scope.cancel()
    }

    @Test
    fun claudeNeedingYouIsAnsweredFromItsNotification() {
        val posted = awayWhileClaudeAsks()
        assertEquals("Do you want to proceed?", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Yes", "No"), posted.actions.map { it.title.toString() }.let { listOf(it.first(), it.last()) })

        // Taken, Claude goes on and the prompt goes.
        pressKeys { rex.block("b1").screen = "\u001b[H⏺ Done." }
        runBlocking { AnswerReceiver.answer(context, model, tap(posted, "No")) }
        assertEquals(listOf("digit_4 press", "digit_4 release"), pressed)
        assertEquals("Answered: No", text())
    }

    @Test
    fun aPromptThatChangedIsLeftForThePane() {
        val posted = awayWhileClaudeAsks()
        pressKeys()
        rex.block("b1").screen = vt("claude-permission-bash-staged").replace("date > out2.txt", "rm -rf build")
        runBlocking { AnswerReceiver.answer(context, model, tap(posted, "Yes")) }
        assertEquals(emptyList<String>(), pressed)
        assertEquals("Not answered: the screen has changed since. Tap to open it.", text())
        assertEquals(0, notification().actions?.size ?: 0)
        // A tap on it opens the pane.
        val open = shadowOf(notification().contentIntent).savedIntent
        assertEquals(Notifier.ACTION_OPEN_PANE, open.action)
        assertEquals("b1", open.getStringExtra(Notifier.EXTRA_BLOCK))
    }

    @Test
    fun aTapWithTheHostGoneSaysToOpenThePane() {
        // As Android delivers it, to the app's own model, which has no such host.
        val prompt = PanePrompt("gone", "s1", "b1", PanePrompts.screenOf(vt("claude-permission-bash-staged"), 46, 30).prompt!!, seenAt = 7)
        model.notifier.post(Alert("gone", "s1", "b1", Alert.Kind.BLOCKED, "Fix the tests", null), "mac", prompt)
        notification().actions.first().actionIntent.send()
        waitFor("the answer") { text() == "Not answered: couldn't reach mac. Tap to open it." }
    }

    /** Leave the app watching, then have Claude Code in b1 say it needs the user; the notification posted. */
    private fun awayWhileClaudeAsks(): Notification {
        model.settings.watchInBackground.value = true
        model.settings.stayConnectedSeconds.value = 0
        model.onForeground()
        val host = host()
        waitFor("online") { host.state.value.status == ConnStatus.ONLINE }
        model.onBackground()
        pump(300)
        rex.controls.last().blockEvent("b1", "program_status_changed", buildJsonObject {
            putJsonObject("record") {
                put("id", "claude")
                put("state", "blocked")
                put("title", "Fix the tests")
                put("app", "claude-code")
            }
        })
        // Read first, so it comes with the prompt's answers.
        waitFor("the notification") { notifications.allNotifications.any { it.actions?.isNotEmpty() == true } }
        return notification()
    }

    private fun host(): HostConnection {
        waitFor("the host's connection") { model.connection(config.id) != null }
        return model.connection(config.id)!!
    }

    private fun notification(): Notification = notifications.getNotification("b1", Notifier.ALERT_ID)

    private fun text() = notification().extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun tap(posted: Notification, label: String): Notifier.AnswerTap =
        Notifier.answerTap(shadowOf(posted.actions.first { it.title == label }.actionIntent).savedIntent)!!

    /** Note keys pressed in the pane in [pressed], with [onPress] run as one goes down. */
    private fun pressKeys(onPress: () -> Unit = {}) {
        rex.handle = { method, payload ->
            if (method == "block.event") {
                val key = payload.obj("event")?.obj("payload")
                if (key != null) {
                    pressed += "${key.str("key")} ${key.str("action")}"
                    if (key.str("action") == "press") onPress()
                }
            }
            rex.answer(method, payload)
        }
    }

    private fun vt(name: String) = javaClass.classLoader!!.getResource("screens/$name.vt")!!.readText()

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
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < until)
            pump(25)
        }
    }
}
