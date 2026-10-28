package com.jeffarese.rexmobile

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostState
import com.jeffarese.rexmobile.model.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the app running while it is away, so the hosts' control connections stay up and an agent
 * that needs the user can tell them with a notification. Only control connections: the terminals'
 * streams still pause, so watching costs little data. Runs while Notify when away is on.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val model get() = (application as RexMobileApp).model
    /** Keeps the ongoing notification's text current. Main thread only. */
    private var updates: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            model.settings.watchInBackground.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        val notifier = model.notifier
        ServiceCompat.startForeground(
            this, Notifier.WATCHING_ID, notifier.watching(summary(model.hostStates.value.map { it.second })),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        // After Android restarted the service, the app itself may not have opened yet.
        model.onWatchStarted()
        updates?.cancel()
        updates = scope.launch {
            model.hostStates.map { list -> summary(list.map { it.second }) }.distinctUntilChanged().collect { text ->
                runCatching { NotificationManagerCompat.from(this@WatchService).notify(Notifier.WATCHING_ID, notifier.watching(text)) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.jeffarese.rexmobile.STOP_WATCHING"

        /** "2 hosts connected · 1 agent needs you": what the ongoing notification says. */
        fun summary(hosts: List<HostState>): String {
            val online = hosts.count { it.status == ConnStatus.ONLINE }
            val states = hosts.flatMap { h -> h.blocks.values.mapNotNull { it.status?.state } }
            val parts = mutableListOf(
                when {
                    hosts.isEmpty() -> "No hosts"
                    online == hosts.size -> if (online == 1) "1 host connected" else "$online hosts connected"
                    else -> "$online of ${hosts.size} hosts connected"
                },
            )
            states.count { it == "blocked" }.takeIf { it > 0 }?.let { parts += if (it == 1) "1 agent needs you" else "$it agents need you" }
            states.count { it == "working" }.takeIf { it > 0 }?.let { parts += "$it working" }
            return parts.joinToString(" · ")
        }
    }
}
