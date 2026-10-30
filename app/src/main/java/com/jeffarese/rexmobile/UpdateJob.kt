package com.jeffarese.rexmobile

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.jeffarese.rexmobile.model.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps Chompy up to date while it is closed: every few hours on Wi-Fi it looks for a new version and
 * installs it, and a while after the user leaves the app it installs the one already downloaded. Only
 * in builds that update themselves; the play build leaves it out.
 */
class UpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val updater = (application as RexMobileApp).model.updater
        if (!updater.enabled) return false
        running = scope.launch {
            updater.updateQuietly()
            jobFinished(params, false)
        }
        return true
    }

    // A download under way carries on in the app's own scope while it can. Either way the next check comes round.
    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHECK = 1
        private const val INSTALL = 2

        /** Look for updates every few hours on Wi-Fi, unless already asked to. */
        fun schedule(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java) ?: return
            if (jobs.getPendingJob(CHECK) != null) return
            runCatching {
                jobs.schedule(
                    JobInfo.Builder(CHECK, ComponentName(context, UpdateJob::class.java))
                        .setPeriodic(Updater.CHECK_EVERY_MS)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
            }
        }

        /** Install the update downloaded in [delayMs], whether the app is still running then or not. Replaces the one asked for before. */
        fun scheduleInstall(context: Context, delayMs: Long) {
            val jobs = context.getSystemService(JobScheduler::class.java) ?: return
            runCatching {
                jobs.schedule(JobInfo.Builder(INSTALL, ComponentName(context, UpdateJob::class.java)).setMinimumLatency(delayMs).build())
            }
        }
    }
}
