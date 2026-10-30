package com.jeffarese.rexmobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.jeffarese.rexmobile.model.Updater

/** Android's word on an update Chompy is installing, and on Chompy having been replaced. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STATUS -> {
                val app = context.applicationContext as RexMobileApp
                // A process started just for this has no update in hand: nothing to tell.
                if (!app.modelStarted) return
                app.model.updater.onInstallStatus(
                    intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java),
                )
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> Updater.onReplaced(context)
        }
    }

    companion object {
        const val ACTION_STATUS = "com.jeffarese.rexmobile.UPDATE_STATUS"
    }
}
