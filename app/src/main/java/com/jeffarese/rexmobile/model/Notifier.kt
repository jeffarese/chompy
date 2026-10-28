package com.jeffarese.rexmobile.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jeffarese.rexmobile.MainActivity
import com.jeffarese.rexmobile.R
import com.jeffarese.rexmobile.WatchService

/**
 * The phone's own notifications: one per pane for what an agent or program did while the app was
 * away, the quiet one Android needs while [WatchService] keeps the hosts connected, and one when Rex
 * has updated itself.
 */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system?.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Agents and programs", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When an agent needs you, finishes or fails, or a program sends a notification"
            },
        )
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

    /** Tell the user about [alert] on [hostLabel]. A newer one for the same pane replaces it. */
    fun post(alert: Alert, hostLabel: String?) {
        val what = when (alert.kind) {
            Alert.Kind.BLOCKED -> "Needs you"
            Alert.Kind.DONE -> "Finished"
            Alert.Kind.ERROR -> "Failed"
            Alert.Kind.BELL -> "Bell"
            Alert.Kind.NOTIFICATION -> "Notification"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.title)
            .setContentText(alert.body?.takeIf { it.isNotBlank() } ?: what)
            .setSubText(listOfNotNull(what, hostLabel).joinToString(" · "))
            .setStyle(alert.body?.takeIf { it.isNotBlank() }?.let { NotificationCompat.BigTextStyle().bigText(it) })
            .setCategory(if (alert.kind == Alert.Kind.BLOCKED) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPane(alert))
            .build()
        runCatching { manager.notify(alert.blockId, ALERT_ID, notification) }
    }

    /** The pane's notification, once the user has seen the pane. */
    fun cancel(blockId: String) = manager.cancel(blockId, ALERT_ID)

    fun cancelAll() {
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
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_WATCHING = "watching"
        const val CHANNEL_UPDATES = "updates"
        const val ALERT_ID = 1
        const val WATCHING_ID = 2
        const val UPDATED_ID = 3
        const val ACTION_OPEN_PANE = "com.jeffarese.rexmobile.OPEN_PANE"
        const val EXTRA_HOST = "host_id"
        const val EXTRA_SESSION = "session_id"
        const val EXTRA_BLOCK = "block_id"
    }
}
