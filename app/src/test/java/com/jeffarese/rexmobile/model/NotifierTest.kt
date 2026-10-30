package com.jeffarese.rexmobile.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.AnswerReceiver
import com.jeffarese.rexmobile.terminal.PromptOption
import com.jeffarese.rexmobile.terminal.QuestionTab
import com.jeffarese.rexmobile.terminal.ScreenPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The notifications' channels, how a pane's alerts are spaced out, and a prompt's buttons. */
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

    @Test
    fun aPermissionPromptComesWithItsCommandAndAnswers() {
        val screen = PanePrompts.screenOf(vt("claude-permission-bash-staged"), 46, 30)
        val prompt = PanePrompt("h1", "s1", "b1", screen.prompt!!, seenAt = 42)
        notifier().post(alert("b1", Alert.Kind.BLOCKED), "mac", prompt)

        val n = posted("b1")
        assertEquals("Do you want to proceed?", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Bash command\ndate > out2.txt\nDo you want to proceed?", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(3, n.actions.size)
        assertEquals("Yes", n.actions[0].title)
        assertTrue(n.actions[1].title.startsWith("Yes, and always allow"))
        assertEquals("No", n.actions[2].title)
        // Typed into a terminal: only once the phone is unlocked.
        assertTrue(n.actions.all { it.isAuthenticationRequired })

        // Each button carries the prompt as read, to check the screen against.
        val pending = shadowOf(n.actions[2].actionIntent)
        assertTrue(pending.isBroadcast)
        assertEquals(AnswerReceiver::class.java.name, pending.savedIntent.component?.className)
        val tap = Notifier.answerTap(pending.savedIntent)!!
        assertEquals("No", tap.option.label)
        assertEquals("Fix the tests", tap.title)
        assertEquals("mac", tap.hostLabel)
        assertEquals(PanePrompt("h1", "s1", "b1", tap.prompt.prompt, 42), tap.prompt)
        assertEquals(prompt.prompt.question, tap.prompt.prompt.question)
        assertEquals(prompt.prompt.context, tap.prompt.prompt.context)
        assertEquals(prompt.prompt.options.map { it.label }, tap.prompt.prompt.options.map { it.label })
        assertEquals(3, tap.prompt.prompt.options.indexOf(tap.option))
    }

    @Test
    fun aQuestionOffersEachOfItsAnswers() {
        val screen = PanePrompts.screenOf(vt("claude-ask-colour-staged"), 46, 30)
        notifier().post(alert("b1", Alert.Kind.BLOCKED), "mac", PanePrompt("h1", "s1", "b1", screen.prompt!!, seenAt = 1))
        assertEquals("Which of these three colours do you prefer?", posted("b1").extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Red", "Green", "Blue"), posted("b1").actions.map { it.title.toString() })
    }

    @Test
    fun aLongQuestionOffersItsFirstTwoAnswers() {
        val options = listOf("Red", "Green", "Blue", "Purple").mapIndexed { i, label -> PromptOption(i + 1, label, selected = i == 0) } +
            PromptOption(5, "Type something.", role = PromptOption.Role.OWN)
        val question = ScreenPrompt(ScreenPrompt.Kind.MENU, "Which colour?", options, tabs = listOf(QuestionTab("Colour", QuestionTab.State.OPEN)))
        val prompt = PanePrompt("h1", "s1", "b1", question, seenAt = 1)
        assertEquals(listOf("Red", "Green"), Notifier.offered(prompt).map { it.label })
        notifier().post(alert("b1", Alert.Kind.BLOCKED), "mac", prompt)
        assertEquals(listOf("Red", "Green"), posted("b1").actions.map { it.title.toString() })
        assertEquals("Which colour?", posted("b1").extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun anAnswerSaysHowItWentInItsNotification() {
        val notifier = notifier()
        val screen = PanePrompts.screenOf(vt("claude-permission-bash-staged"), 46, 30)
        val prompt = PanePrompt("h1", "s1", "b1", screen.prompt!!, seenAt = 42)
        notifier.post(alert("b1", Alert.Kind.BLOCKED), "mac", prompt)
        val tap = Notifier.answerTap(shadowOf(posted("b1").actions[0].actionIntent).savedIntent)!!

        notifier.answering(tap)
        assertEquals("Answering: Yes…", text("b1"))
        assertEquals(0, posted("b1").actions?.size ?: 0)
        assertTrue(posted("b1").quiet)
        notifier.answered(tap, PanePrompts.Answer.CHANGED)
        assertEquals("Not answered: the screen has changed since. Tap to open it.", text("b1"))
        notifier.answered(tap, PanePrompts.Answer.UNREACHABLE)
        assertEquals("Not answered: couldn't reach mac. Tap to open it.", text("b1"))

        // Taken, it says so for a moment, then goes.
        notifier.answered(tap, PanePrompts.Answer.ANSWERED)
        assertEquals("Answered: Yes", text("b1"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertNull(shadowOf(system).getNotification("b1", Notifier.ALERT_ID))

        // Claude finishing meanwhile stays: its alert has taken the answer's place.
        notifier.post(alert("b1", Alert.Kind.BLOCKED), "mac", prompt)
        notifier.answered(tap, PanePrompts.Answer.ANSWERED)
        notifier.post(alert("b1", Alert.Kind.DONE), "mac")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(Notifier.CHANNEL_RESULTS, posted("b1").channelId)
        assertEquals("Finished", text("b1"))
        // And how a late answer went leaves it be.
        notifier.answered(tap, PanePrompts.Answer.CHANGED)
        assertEquals("Finished", text("b1"))
    }

    private fun text(block: String) = posted(block).extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun vt(name: String) = javaClass.classLoader!!.getResource("screens/$name.vt")!!.readText()
}
