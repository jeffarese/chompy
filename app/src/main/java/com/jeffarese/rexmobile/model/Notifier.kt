package com.jeffarese.rexmobile.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jeffarese.rexmobile.MainActivity
import com.jeffarese.rexmobile.R
import com.jeffarese.rexmobile.WatchService

/**
 * The phone's own notifications: one per pane for what an agent or program did while the app was
 * away, the quiet one Android needs while [WatchService] keeps the hosts connected, and one when Rex
 * has updated itself.
 *
 * Each kind of alert has a channel of its own, so Android's settings can quiet one and not the
 * others: Needs you pops up over the screen, the rest only sound. A pane alerts at most once every
 * [QUIET_MS]; what it posts in between updates its notification without a sound.
 *
 * @param now the clock that spacing is measured on.
 */
class Notifier(private val context: Context, now: () -> Long = SystemClock::elapsedRealtime) {
    private val manager = NotificationManagerCompat.from(context)
    private val pacer = AlertPacer(now)

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

    /** Tell the user about [alert] on [hostLabel]. A newer one for the same pane replaces it. */
    fun post(alert: Alert, hostLabel: String?) {
        val what = when (alert.kind) {
            Alert.Kind.BLOCKED -> "Needs you"
            Alert.Kind.DONE -> "Finished"
            Alert.Kind.ERROR -> "Failed"
            Alert.Kind.BELL -> "Bell"
            Alert.Kind.NOTIFICATION -> "Notification"
        }
        val notification = NotificationCompat.Builder(context, channelOf(alert.kind))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(alert.body?.takeIf { it.isNotBlank() } ?: what)
            .setSubText(listOfNotNull(what, hostLabel).joinToString(" · "))
            .setStyle(alert.body?.takeIf { it.isNotBlank() }?.let { NotificationCompat.BigTextStyle().bigText(it) })
            .setCategory(if (alert.kind == Alert.Kind.BLOCKED) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (alert.kind == Alert.Kind.BLOCKED) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openPane(alert))
            // An agent flipping between states updates its notification without buzzing each time.
            .setSilent(!pacer.alerts(alert.blockId))
            .build()
        runCatching { manager.notify(alert.blockId, ALERT_ID, notification) }
    }

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

    private fun openPane(alert: Alert): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_PANE)
            .putExtra(EXTRA_HOST, alert.hostId)
            .putExtra(EXTRA_SESSION, alert.sessionId)
            .putExtra(EXTRA_BLOCK, alert.blockId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // One per pane, so each opens its own.
        return PendingIntent.getActivity(
            context, alert.blockId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

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
        const val EXTRA_HOST = "host_id"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_BLOCK = "block_id"

        /** How long a pane's later posts stay quiet after it alerted. */
        const val QUIET_MS = 30_000L

        /** The channel for [kind]. A bell goes with programs' notifications, though it isn't posted today. */
        fun channelOf(kind: Alert.Kind): String = when (kind) {
            Alert.Kind.BLOCKED -> CHANNEL_NEEDS_YOU
            Alert.Kind.DONE, Alert.Kind.ERROR -> CHANNEL_RESULTS
            Alert.Kind.BELL, Alert.Kind.NOTIFICATION -> CHANNEL_PROGRAMS
        }
    }
}

/**
 * Which of a pane's posts alert: one at most every [quietMs]. The posts in between update its
 * notification without a sound or a pop-up, so an agent flipping between states can't buzz again
 * and again, while other panes alert as ever.
 */
internal class AlertPacer(private val now: () -> Long, private val quietMs: Long = Notifier.QUIET_MS) {
    private val alertedAt = HashMap<String, Long>()

    /** True if a post for [blockId] alerts now, which then counts as its last alert. */
    @Synchronized
    fun alerts(blockId: String): Boolean {
        val t = now()
        val last = alertedAt[blockId]
        if (last != null && t - last < quietMs) return false
        alertedAt[blockId] = t
        return true
    }

    /** The pane's next post alerts, as after the user has seen it. */
    @Synchronized
    fun forget(blockId: String) {
        alertedAt.remove(blockId)
    }

    @Synchronized
    fun forgetAll() = alertedAt.clear()
}
