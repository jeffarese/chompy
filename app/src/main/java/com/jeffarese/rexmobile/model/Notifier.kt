package com.jeffarese.rexmobile.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.bundleOf
import com.jeffarese.rexmobile.AnswerReceiver
import com.jeffarese.rexmobile.MainActivity
import com.jeffarese.rexmobile.R
import com.jeffarese.rexmobile.WatchService
import com.jeffarese.rexmobile.terminal.PromptOption
import com.jeffarese.rexmobile.terminal.QuestionTab
import com.jeffarese.rexmobile.terminal.ScreenPrompt

/**
 * The phone's own notifications: one per pane for what an agent or program did while the app was
 * away, the quiet one Android needs while [WatchService] keeps the hosts connected, and one when Rex
 * has updated itself.
 *
 * Each kind of alert has a channel of its own, so Android's settings can quiet one and not the
 * others: Needs you pops up over the screen, the rest only sound. Once a pane has alerted, what it
 * posts in the next [QUIET_MS] updates its notification without a sound, unless it is more urgent:
 * Claude needing the user just after it finished alerts all the same.
 *
 * @param now the clock that spacing is measured on.
 */
class Notifier(private val context: Context, now: () -> Long = SystemClock::elapsedRealtime) {
    private val manager = NotificationManagerCompat.from(context)
    private val pacer = AlertPacer(now)
    private val main = Handler(Looper.getMainLooper())

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system?.createNotificationChannel(
            NotificationChannel(CHANNEL_NEEDS_YOU, "Needs you", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When an agent is waiting for your answer or your permission"
            },
        )
        system?.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, "Finished and failed", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When an agent finishes or fails"
            },
        )
        system?.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRAMS, "Program notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a program in a terminal sends a notification"
            },
        )
        // Every alert went to this one before, so it would linger in Android's settings.
        system?.deleteNotificationChannel(OLD_CHANNEL_ALERTS)
        system?.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCHING, "Watching in the background", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Chompy stays connected to tell you about your agents"
                setShowBadge(false)
            },
        )
        system?.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When Chompy has updated itself"
            },
        )
    }

    val enabled: Boolean get() = manager.areNotificationsEnabled()

    /** False if the user turned off [channel] in Android's settings. */
    fun channelOn(channel: String): Boolean =
        context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channel)
            ?.let { it.importance != NotificationManager.IMPORTANCE_NONE } != false

    /**
     * Tell the user about [alert] on [hostLabel]. A newer one for the same pane replaces it. With
     * [prompt], the Claude prompt the pane shows, it says what Claude asks, and offers the answers
     * as buttons that [AnswerReceiver] presses.
     */
    fun post(alert: Alert, hostLabel: String?, prompt: PanePrompt? = null) {
        val what = when (alert.kind) {
            Alert.Kind.BLOCKED -> "Needs you"
            Alert.Kind.DONE -> "Finished"
            Alert.Kind.ERROR -> "Failed"
            Alert.Kind.BELL -> "Bell"
            Alert.Kind.NOTIFICATION -> "Notification"
        }
        val body = alert.body?.takeIf { it.isNotBlank() }
        val asks = prompt?.prompt
        val builder = NotificationCompat.Builder(context, channelOf(alert.kind))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(asks?.let(::questionOf) ?: body ?: what)
            .setSubText(listOfNotNull(what, hostLabel).joinToString(" · "))
            .setStyle((asks?.let { (it.context + questionOf(it)).joinToString("\n") } ?: body)?.let { NotificationCompat.BigTextStyle().bigText(it) })
            .setCategory(if (alert.kind == Alert.Kind.BLOCKED) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (alert.kind == Alert.Kind.BLOCKED) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openPane(alert.hostId, alert.sessionId, alert.blockId))
            // An agent flipping between states updates its notification without buzzing each time.
            .setSilent(!pacer.alerts(alert.blockId, alert.kind))
        if (prompt != null) {
            offered(prompt).forEach { builder.addAction(answerAction(prompt, it, alert.title, hostLabel)) }
            builder.addExtras(bundleOf(EXTRA_SEEN_AT to prompt.seenAt))
        }
        runCatching { manager.notify(alert.blockId, ALERT_ID, builder.build()) }
    }

    /** Say [tap]'s answer is on its way, in place of the buttons, so a second tap can't send another. */
    fun answering(tap: AnswerTap) = update(tap, "Answering: ${tap.option.label}…")

    /** Say how [tap]'s answer went. One given leaves after a moment; otherwise a tap opens the pane to answer there. */
    fun answered(tap: AnswerTap, result: PanePrompts.Answer) {
        val host = tap.hostLabel ?: "its host"
        when (result) {
            PanePrompts.Answer.ANSWERED -> {
                // Claude asking again soon after is news, not an agent flipping between states.
                pacer.forget(tap.prompt.blockId)
                update(tap, "Answered: ${tap.option.label}")
                // Gone after a moment, unless a newer alert for the pane has taken its place, such as Claude
                // finishing soon after. Android's own timeout would take that away too: it cancels whatever
                // the pane's notification is by then.
                main.postDelayed({ if (showing(tap.prompt)) manager.cancel(tap.prompt.blockId, ALERT_ID) }, ANSWERED_SHOWN_MS)
            }
            PanePrompts.Answer.UNCONFIRMED -> update(tap, "Sent ${tap.option.label}, but Claude didn't seem to take it. Tap to check.")
            PanePrompts.Answer.CHANGED -> update(tap, "Not answered: the screen has changed since. Tap to open it.")
            PanePrompts.Answer.UNREACHABLE -> update(tap, "Not answered: couldn't reach $host. Tap to open it.")
            // The answer under way says how it went.
            PanePrompts.Answer.BUSY -> Unit
        }
    }

    /**
     * Show [text] in place of [tap]'s question, quietly and without buttons, while the pane's
     * notification is still the one tapped: a newer alert for the pane has replaced it otherwise.
     */
    private fun update(tap: AnswerTap, text: String) {
        val p = tap.prompt
        if (!showing(p)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_NEEDS_YOU)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tap.title)
            .setContentText(text)
            .setSubText(listOfNotNull("Needs you", tap.hostLabel).joinToString(" · "))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openPane(p.hostId, p.sessionId, p.blockId))
            .addExtras(bundleOf(EXTRA_SEEN_AT to p.seenAt))
            .build()
        runCatching { manager.notify(p.blockId, ALERT_ID, notification) }
    }

    /** Whether the pane's notification is still the one that offered [p], or what an answer to it made of it. */
    private fun showing(p: PanePrompt) = runCatching { manager.activeNotifications }.getOrDefault(emptyList())
        .any { it.tag == p.blockId && it.id == ALERT_ID && it.notification.extras.getLong(EXTRA_SEEN_AT) == p.seenAt }

    /** The pane's notification, once the user has seen the pane. */
    fun cancel(blockId: String) {
        pacer.forget(blockId)
        manager.cancel(blockId, ALERT_ID)
    }

    fun cancelAll() {
        pacer.forgetAll()
        manager.activeNotifications.filter { it.id == ALERT_ID }.forEach { manager.cancel(it.tag, it.id) }
    }

    /** What [WatchService] shows while it runs, with a button to stop watching. */
    fun watching(text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_WATCHING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Watching your agents")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0, "Stop",
                PendingIntent.getService(
                    context, 1, Intent(context, WatchService::class.java).setAction(WatchService.ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    /** Rex closed to update itself to [version]: one tap opens it again. */
    fun updated(version: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Chompy is updated to $version")
            .setContentText("Tap to open it again")
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 2, Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        runCatching { manager.notify(UPDATED_ID, notification) }
    }

    private fun openPane(hostId: String, sessionId: String, blockId: String): PendingIntent =
        // One per pane, so each opens its own.
        PendingIntent.getActivity(
            context, blockId.hashCode(), paneIntent(context, hostId, sessionId, blockId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * A button that answers [prompt] with [option]. It carries the prompt as it was read, which the
     * answer is checked against; typing into a terminal waits for the phone to be unlocked (before
     * Android 12, [AnswerReceiver] opens the pane from the lock screen instead).
     */
    private fun answerAction(prompt: PanePrompt, option: PromptOption, title: String, hostLabel: String?): NotificationCompat.Action {
        val index = prompt.prompt.options.indexOf(option)
        val intent = Intent(context, AnswerReceiver::class.java)
            .setAction(ACTION_ANSWER)
            .putExtra(EXTRA_HOST, prompt.hostId)
            .putExtra(EXTRA_SESSION, prompt.sessionId)
            .putExtra(EXTRA_BLOCK, prompt.blockId)
            .putExtra(EXTRA_QUESTION, prompt.prompt.question)
            .putExtra(EXTRA_CONTEXT, prompt.prompt.context.toTypedArray())
            .putExtra(EXTRA_OPTIONS, prompt.prompt.options.map { it.label }.toTypedArray())
            .putExtra(EXTRA_TABS, prompt.prompt.tabs.map { it.label }.toTypedArray())
            .putExtra(EXTRA_SEEN_AT, prompt.seenAt)
            .putExtra(EXTRA_OPTION, index)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_HOST_LABEL, hostLabel)
        // One per pane and option, so each button carries its own.
        val pending = PendingIntent.getBroadcast(
            context, "${prompt.blockId}#$index".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, option.label, pending)
            .setAuthenticationRequired(true)
            .setShowsUserInterface(false)
            .build()
    }

    /** A tap on one of a notification's answers: [option] of [prompt], in the notification titled [title]. */
    data class AnswerTap(val prompt: PanePrompt, val option: PromptOption, val title: String, val hostLabel: String?)

    companion object {
        const val CHANNEL_NEEDS_YOU = "needs_you"
        const val CHANNEL_RESULTS = "results"
        const val CHANNEL_PROGRAMS = "programs"
        const val CHANNEL_WATCHING = "watching"
        const val CHANNEL_UPDATES = "updates"
        /** The one channel every alert went to before they had a channel each. */
        private const val OLD_CHANNEL_ALERTS = "alerts"
        const val ALERT_ID = 1
        const val WATCHING_ID = 2
        const val UPDATED_ID = 3
        const val ACTION_OPEN_PANE = "com.jeffarese.rexmobile.OPEN_PANE"
        const val ACTION_ANSWER = "com.jeffarese.rexmobile.ANSWER"
        const val EXTRA_HOST = "host_id"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_BLOCK = "block_id"
        private const val EXTRA_QUESTION = "question"
        private const val EXTRA_CONTEXT = "context"
        private const val EXTRA_OPTIONS = "options"
        private const val EXTRA_TABS = "tabs"
        private const val EXTRA_SEEN_AT = "seen_at"
        private const val EXTRA_OPTION = "option"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HOST_LABEL = "host_label"

        /** How long a pane's later posts, if no more urgent, stay quiet after it alerted. */
        const val QUIET_MS = 30_000L
        /** How long "Answered" shows before the notification goes. */
        private const val ANSWERED_SHOWN_MS = 4_000L

        /** Opens the pane, as a tap on its notification does. */
        fun paneIntent(context: Context, hostId: String, sessionId: String, blockId: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_PANE)
                .putExtra(EXTRA_HOST, hostId)
                .putExtra(EXTRA_SESSION, sessionId)
                .putExtra(EXTRA_BLOCK, blockId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** The channel for [kind]. A bell goes with programs' notifications, though it isn't posted today. */
        fun channelOf(kind: Alert.Kind): String = when (kind) {
            Alert.Kind.BLOCKED -> CHANNEL_NEEDS_YOU
            Alert.Kind.DONE, Alert.Kind.ERROR -> CHANNEL_RESULTS
            Alert.Kind.BELL, Alert.Kind.NOTIFICATION -> CHANNEL_PROGRAMS
        }

        /**
         * The answers a notification offers as buttons, which Android shows three of: all of up to
         * three. Of more, the first two, and for a permission its last, which is No; a tap on the
         * notification opens the pane for the rest.
         */
        fun offered(prompt: PanePrompt): List<PromptOption> {
            val answers = prompt.answers
            return when {
                answers.size <= 3 -> answers
                prompt.permission -> answers.take(2) + answers.last()
                else -> answers.take(2)
            }
        }

        private fun questionOf(prompt: ScreenPrompt) =
            prompt.question ?: if (prompt.claudeQuestion) "Claude has a question" else "Claude asks for permission"

        /** The answer a tap on a notification's button asks for; null for an intent that isn't one. */
        fun answerTap(intent: Intent): AnswerTap? {
            if (intent.action != ACTION_ANSWER) return null
            // As much of the prompt as it is checked against: its options' numbers and roles are read from the screen again.
            val prompt = ScreenPrompt(
                ScreenPrompt.Kind.MENU,
                intent.getStringExtra(EXTRA_QUESTION),
                intent.getStringArrayExtra(EXTRA_OPTIONS)?.map { PromptOption(null, it) } ?: return null,
                context = intent.getStringArrayExtra(EXTRA_CONTEXT)?.toList().orEmpty(),
                tabs = intent.getStringArrayExtra(EXTRA_TABS)?.map { QuestionTab(it, QuestionTab.State.OPEN) }.orEmpty(),
            )
            val pane = PanePrompt(
                intent.getStringExtra(EXTRA_HOST) ?: return null,
                intent.getStringExtra(EXTRA_SESSION) ?: return null,
                intent.getStringExtra(EXTRA_BLOCK) ?: return null,
                prompt,
                intent.getLongExtra(EXTRA_SEEN_AT, 0),
            )
            val option = prompt.options.getOrNull(intent.getIntExtra(EXTRA_OPTION, -1)) ?: return null
            return AnswerTap(pane, option, intent.getStringExtra(EXTRA_TITLE).orEmpty(), intent.getStringExtra(EXTRA_HOST_LABEL))
        }
    }
}

/**
 * Which of a pane's posts alert. One no more urgent than an alert the pane gave in the last [quietMs]
 * updates its notification without a sound or a pop-up, so an agent flipping between states can't
 * buzz again and again. A more urgent one alerts all the same: Needs you just after Finished, say,
 * which a quiet update would leave for the user to stumble on. Other panes alert as ever.
 */
internal class AlertPacer(private val now: () -> Long, private val quietMs: Long = Notifier.QUIET_MS) {
    /** When each pane last alerted at each urgency, by the pane and the urgency. */
    private val alertedAt = HashMap<Pair<String, Int>, Long>()

    /** True if a post of [kind] for [blockId] alerts now, which then counts as its last alert at that urgency. */
    @Synchronized
    fun alerts(blockId: String, kind: Alert.Kind): Boolean {
        val t = now()
        val urgency = urgencyOf(kind)
        val said = (urgency..MOST_URGENT).any { u -> alertedAt[blockId to u]?.let { t - it < quietMs } == true }
        if (said) return false
        alertedAt[blockId to urgency] = t
        return true
    }

    /** The pane's next post alerts, as after the user has seen it. */
    @Synchronized
    fun forget(blockId: String) {
        alertedAt.keys.removeAll { it.first == blockId }
    }

    @Synchronized
    fun forgetAll() = alertedAt.clear()

    private companion object {
        const val MOST_URGENT = 2

        /** How much an alert of [kind] asks of the user: needing them most, then a result, then a program's own say. */
        fun urgencyOf(kind: Alert.Kind) = when (kind) {
            Alert.Kind.BLOCKED -> MOST_URGENT
            Alert.Kind.DONE, Alert.Kind.ERROR -> 1
            Alert.Kind.BELL, Alert.Kind.NOTIFICATION -> 0
        }
    }
}
