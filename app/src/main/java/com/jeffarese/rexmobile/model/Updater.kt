package com.jeffarese.rexmobile.model

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.core.content.pm.PackageInfoCompat
import com.jeffarese.rexmobile.BuildConfig
import com.jeffarese.rexmobile.UpdateReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where the app's own update is. */
sealed interface UpdateState {
    /** Not checked yet, or this build doesn't update itself. */
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    /** The last check the user asked for failed. Checks of the app's own stay quiet. */
    data class Failed(val message: String) : UpdateState

    /** A newer version, not downloaded yet: the phone is on a metered network, or the download failed. */
    data class Available(override val release: Release, val error: String? = null) : WithRelease
    data class Downloading(override val release: Release, val progress: Float) : WithRelease
    /** Downloaded and checked, waiting for the user. [error] is why the last try to install it failed. */
    data class Ready(override val release: Release, val apk: File, val error: String? = null) : WithRelease
    /** Handed to Android, which may ask the user to confirm. */
    data class Installing(override val release: Release, val apk: File) : WithRelease

    sealed interface WithRelease : UpdateState { val release: Release }
}

/**
 * Keeps the app up to date from its releases on GitHub: checks now and then while the app is open,
 * downloads a new version by itself on Wi-Fi, and installs it when the user says so.
 *
 * Android asks the user once to let Rex install apps, then to confirm the first update. From then
 * on Rex installed itself, so on Android 12 and later its updates need no confirming.
 */
class Updater(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Null where the build doesn't update itself, such as a debug build, whose key differs. */
    private val feed: ReleaseFeed?,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    private val dir: File = File(context.cacheDir, "updates"),
    private val verify: (File) -> String? = { checkApk(context, it) },
    private val metered: () -> Boolean = { isMetered(context) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = prefs(context)

    val enabled: Boolean get() = feed != null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** The release this version came from, while its notes are still to be seen. */
    private val _justUpdated = MutableStateFlow<Release?>(null)
    val justUpdated: StateFlow<Release?> = _justUpdated

    /** The version whose card the user put off, for [SNOOZE_MS]. Settings still offers it. */
    private val _snoozed = MutableStateFlow<String?>(null)
    val snoozed: StateFlow<String?> = _snoozed

    /** When a check last reached GitHub, in milliseconds since the epoch, or 0. */
    val lastChecked: Long get() = prefs.getLong(CHECKED_AT, 0)

    /** Install once Android lets Rex, after sending the user to allow it. */
    @Volatile private var installWhenAllowed = false
    @Volatile private var session: Int? = null
    /** Android's confirmation for [session], to show again if the user left the app while it was up. */
    @Volatile private var confirm: Intent? = null

    init {
        val saved = prefs.getString(RELEASE, null)?.let(Release::fromJson)
        val last = prefs.getString(LAST_RUN, null)
        when {
            saved == null -> Unit
            compareVersions(saved.version, currentVersion) > 0 ->
                // Downloaded before the app last closed: ready again without the network.
                fileFor(saved).takeIf { it.exists() }?.let { _state.value = UpdateState.Ready(saved, it) }
            compareVersions(saved.version, currentVersion) == 0 && last != null && compareVersions(last, currentVersion) < 0 ->
                _justUpdated.value = saved
            else -> prefs.edit().remove(RELEASE).apply()
        }
        if (_justUpdated.value == null) prefs.edit().putString(LAST_RUN, currentVersion).apply()
        if (_state.value !is UpdateState.Ready) dir.listFiles()?.forEach { it.delete() }
        prefs.getString(SNOOZED, null)?.takeIf { clock() - prefs.getLong(SNOOZED_AT, 0) < SNOOZE_MS }?.let { _snoozed.value = it }
    }

    /** Main thread, each time the app's screen comes back. */
    fun onResume() {
        context.getSystemService(android.app.NotificationManager::class.java)?.cancel(Notifier.UPDATED_ID)
        if (installWhenAllowed && canInstall()) {
            installWhenAllowed = false
            install()
        }
        (_state.value as? UpdateState.Installing)?.let { s ->
            val id = session
            if (id == null || context.packageManager.packageInstaller.getSessionInfo(id) == null) {
                _state.value = UpdateState.Ready(s.release, s.apk)
            } else confirm?.let { showConfirm(it) }
        }
        // Once per launch, then every few hours.
        if (enabled && (_state.value is UpdateState.Idle || clock() - lastChecked > CHECK_EVERY_MS)) check(manual = false)
    }

    /** Ask GitHub for a newer version. A check the user asked for downloads on any network, and says when it fails. */
    fun check(manual: Boolean = true) {
        val feed = feed ?: return
        val before = _state.value
        if (before is UpdateState.Checking || before is UpdateState.Downloading || before is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        scope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.IO) { feed.latest() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.onFailure { e ->
                _state.value = if (manual) UpdateState.Failed(e.message ?: "No connection") else before
                return@launch
            }
            prefs.edit().putLong(CHECKED_AT, clock()).apply()
            val release = result.getOrNull()
            if (release == null || compareVersions(release.version, currentVersion) <= 0) {
                _state.value = UpdateState.UpToDate
                return@launch
            }
            val have = (before as? UpdateState.Ready)?.takeIf { it.release.version == release.version && it.apk.exists() }
            when {
                have != null -> _state.value = have
                manual || !metered() -> download(release)
                else -> _state.value = UpdateState.Available(release)
            }
        }
    }

    /** Download [release] and check it is Chompy, newer, and signed with Chompy's key. */
    fun download(release: Release) {
        val feed = feed ?: return
        val now = _state.value
        if (now is UpdateState.Downloading || now is UpdateState.Installing) return
        _state.value = UpdateState.Downloading(release, 0f)
        scope.launch {
            _state.value = try {
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    dir.listFiles()?.forEach { it.delete() }
                    val apk = fileFor(release)
                    feed.download(release, apk) { read, total ->
                        _state.value = UpdateState.Downloading(release, if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f)
                    }
                    verify(apk)?.let { problem ->
                        apk.delete()
                        throw java.io.IOException(problem)
                    }
                    prefs.edit().putString(RELEASE, release.toJson()).apply()
                    UpdateState.Ready(release, apk)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Available(release, e.message ?: "The download failed")
            }
        }
    }

    /** Whether Android lets Rex install apps. Without it, [permissionIntent] asks. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Android's page for letting Rex install apps. [install] runs on coming back once it is allowed. */
    fun permissionIntent(): Intent {
        installWhenAllowed = true
        return Intent(SystemSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    }

    /** Hand the downloaded version to Android. Rex closes when it is installed. */
    fun install() {
        val ready = _state.value as? UpdateState.Ready ?: return
        _state.value = UpdateState.Installing(ready.release, ready.apk)
        prefs.edit().putString(INSTALLING, ready.release.version).apply()
        scope.launch(Dispatchers.IO) {
            try {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(ready.apk.length())
                    setInstallReason(PackageManager.INSTALL_REASON_USER)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                session = id
                confirm = null
                installer.openSession(id).use { s ->
                    s.openWrite("base.apk", 0, ready.apk.length()).use { out ->
                        ready.apk.inputStream().use { it.copyTo(out) }
                        s.fsync(out)
                    }
                    val status = Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_STATUS).setPackage(context.packageName)
                    // Mutable: Android adds the outcome to it.
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    s.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Ready(ready.release, ready.apk, e.message ?: "Couldn't start the update")
            }
        }
    }

    /** What Android said about the install, from [UpdateReceiver]. */
    fun onInstallStatus(status: Int, message: String?, confirmIntent: Intent?) {
        val installing = _state.value as? UpdateState.Installing ?: return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirmIntent?.let {
                confirm = it
                showConfirm(it)
            }
            // Rex is replaced and this process ends; the new version tells the user it is there.
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                session = null
                confirm = null
                prefs.edit().remove(INSTALLING).apply()
                val error = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) null else installError(status, message)
                _state.value = UpdateState.Ready(installing.release, installing.apk, error)
            }
        }
    }

    /** Keep Home's card away for a day. Settings still offers the update. */
    fun snooze(release: Release) {
        prefs.edit().putString(SNOOZED, release.version).putLong(SNOOZED_AT, clock()).apply()
        _snoozed.value = release.version
    }

    /** The user has seen what the new version brought. */
    fun dismissWhatsNew() {
        prefs.edit().putString(LAST_RUN, currentVersion).remove(RELEASE).apply()
        _justUpdated.value = null
    }

    private fun showConfirm(intent: Intent) {
        runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun fileFor(release: Release) = File(dir, "rex-${release.version}.apk")

    companion object {
        /** How long between the checks made while the app is open. */
        const val CHECK_EVERY_MS = 4 * 60 * 60 * 1000L
        const val SNOOZE_MS = 24 * 60 * 60 * 1000L

        private const val PREFS = "updates"
        private const val CHECKED_AT = "checked_at"
        /** The release last downloaded, kept until the version after it has shown its notes. */
        private const val RELEASE = "release"
        /** The version that last ran, to tell an update from a first launch. */
        private const val LAST_RUN = "last_run"
        /** The version handed to Android, so the new one knows it was installed from here. */
        private const val INSTALLING = "installing"
        private const val SNOOZED = "snoozed"
        private const val SNOOZED_AT = "snoozed_at"

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Rex has just been replaced. If it replaced itself, say so, as the old one closed under the user. */
        fun onReplaced(context: Context) {
            val prefs = prefs(context)
            if (prefs.getString(INSTALLING, null) != BuildConfig.VERSION_NAME) return
            prefs.edit().remove(INSTALLING).apply()
            Notifier(context).updated(BuildConfig.VERSION_NAME)
        }

        private fun installError(status: Int, message: String?) = when (status) {
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough space on the phone"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "It conflicts with the installed app"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "It doesn't work on this phone"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "The phone blocked it"
            else -> message?.takeIf { it.isNotBlank() } ?: "The update failed"
        }

        private fun isMetered(context: Context) =
            context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

        /** Why [apk] can't update this app, or null if it can: it must be Rex, newer, and signed with the same key. */
        fun checkApk(context: Context, apk: File): String? {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return "The download isn't an app"
            if (archive.packageName != context.packageName) return "The download is a different app"
            val installed = pm.getPackageInfo(context.packageName, flags)
            if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
                return "The download isn't newer than this version"
            }
            val theirs = signers(archive)
            val ours = signers(installed)
            if (theirs != null && ours != null && theirs != ours) return "The download isn't signed with Chompy's key"
            return null
        }

        @Suppress("DEPRECATION")
        private fun signers(p: PackageInfo): Set<String>? =
            if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            else p.signatures?.map { it.toCharsString() }?.toSet()
    }
}
