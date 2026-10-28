package com.jeffarese.rexmobile.model

import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import androidx.core.content.ContextCompat
import com.jeffarese.rexmobile.BuildConfig
import com.jeffarese.rexmobile.WatchService
import com.jeffarese.rexmobile.ui.Palette
import com.jeffarese.rexmobile.ui.Rex
import com.jeffarese.rexmobile.ui.Target
import com.jeffarese.rexmobile.ui.paletteOf
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.jeffarese.rexmobile.rex.StreamState
import com.jeffarese.rexmobile.terminal.RemoteTerminal
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val MAX_TERMINALS = 6

/** The app's long-lived state: the hosts, their connections, and alerts. */
class AppModel(
    private val context: Context,
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // Pings find a dead control connection, such as after the phone changed networks, on the
        // server's own schedule. The pong waits behind a large response, so a slow link needs the time.
        // Data connections send none: see BlockStream.
        .pingInterval(30, TimeUnit.SECONDS)
        .build(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val hostStore = HostStore(context)

    /** Small UI preferences, such as the terminal size mode. */
    val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** Which finished and failed agents have been looked at, per host and block: see [Reviewed]. */
    private val reviewedPrefs = context.getSharedPreferences("reviewed", Context.MODE_PRIVATE)

    /** What the user can change in Settings. */
    val settings = Settings(context, prefs)

    private val _connections = MutableStateFlow<List<HostConnection>>(emptyList())
    val connections: StateFlow<List<HostConnection>> = _connections

    /**
     * Every host with its current state, in the order the hosts are listed. Always kept current, so
     * a screen has the hosts in its first frame: Home coming back can then restore its scroll
     * position, which an empty first frame would reset.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hostStates: StateFlow<List<Pair<HostConnection, HostState>>> = _connections.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList())
        else combine(list.map { c -> c.state.map { c to it } }) { it.toList() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** At the user's asking, such as a pull to refresh: a connection that doesn't answer is replaced. */
    fun reconnectAll() {
        _connections.value.forEach {
            it.connect()
            it.ensureAlive(force = true)
        }
    }

    private val _alerts = MutableSharedFlow<Alert>(extraBufferCapacity = 16)
    val alerts: SharedFlow<Alert> = _alerts

    /** Unsent message-box text per block, kept while moving between panes and screens. Main thread only. */
    val drafts = HashMap<String, String>()

    /**
     * Claude's effort level per block as last known, read from its footer or set from this app;
     * the Claude strip shows it while the screen doesn't. Main thread only.
     */
    val efforts = HashMap<String, String>()

    /** Claude's permission mode per block as last read from its footer, shown while a dialog hides it. Main thread only. */
    val modes = HashMap<String, String>()

    /** The block on screen, so its own alerts don't interrupt. */
    @Volatile var visibleBlockId: String? = null

    /** How each return to the app went, for Settings to show. */
    val returnLog = ReturnLog(File(context.filesDir, "returns.txt"))

    @Volatile private var foreground = false
    private var backgroundJob: Job? = null
    /** When the app last went to the background, on the clock that keeps counting while the phone sleeps. Main thread only. */
    private var leftAt: Long? = null
    /** The connections closed while the app was away. */
    @Volatile private var closedWhileAway = false

    private val networkWatch = NetworkWatch(context, ::onNetworkChanged)

    /** The phone's notifications, for what happens while the app is away. */
    val notifier = Notifier(context)

    /** Rex's themes, for Settings to offer. */
    val themes = Themes(context)

    /** The app's own updates, from its releases on GitHub. Off in builds that can't install them. */
    val updater = Updater(
        context, scope,
        BuildConfig.UPDATE_REPO.takeIf { it.isNotBlank() }?.let { repo ->
            // Downloads need a read timeout, which the hosts' long-lived sockets go without.
            ReleaseFeed(repo, BuildConfig.UPDATE_TOKEN, http.newBuilder().readTimeout(30, TimeUnit.SECONDS).pingInterval(0, TimeUnit.SECONDS).build())
        },
    )

    /** Whether the phone is in dark mode, which [Appearance.SYSTEM] follows. */
    private val systemDark = MutableStateFlow(isNight(context.resources.configuration))

    /** The theme the app wears now, or null for its own look. */
    val theme: StateFlow<RexTheme?> = combine(
        settings.appearance.flow, settings.themeDark.flow, settings.themeLight.flow, systemDark,
    ) { appearance, dark, light, system -> themeFor(appearance, dark, light, system) }
        .stateIn(scope, SharingStarted.Eagerly, currentTheme())

    private fun currentTheme() =
        themeFor(settings.appearance.value, settings.themeDark.value, settings.themeLight.value, systemDark.value)

    private fun themeFor(appearance: Appearance, dark: String, light: String, systemDark: Boolean): RexTheme? {
        val isDark = when (appearance) {
            Appearance.DARK -> true
            Appearance.LIGHT -> false
            Appearance.SYSTEM -> systemDark
        }
        // Light has no look of the app's own to fall back on.
        return if (isDark) themes.byId(dark) else themes.byId(light) ?: themes.byId(Themes.DEFAULT_LIGHT)
    }

    /** From the activity, which handles its own configuration changes, such as the phone going dark. */
    fun onConfigurationChanged(config: Configuration) {
        systemDark.value = isNight(config)
    }

    private fun isNight(config: Configuration) =
        config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Wear [theme]: the app's colors, and the terminals' unless they keep the Mac's. Main thread only. */
    private fun wear(theme: RexTheme?, terminalsToo: Boolean) {
        Rex.wear(theme?.let(::paletteOf) ?: Palette.Default)
        val colors = theme?.terminalColors?.takeIf { terminalsToo }
        if (RemoteTerminal.themeColors !== colors) {
            RemoteTerminal.themeColors = colors
            terminals.values.forEach { it.refreshColors() }
        }
    }

    init {
        scope.launch {
            hostStore.hosts.collect { syncConnections(it) }
        }
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = onForeground()
                override fun onStop(owner: LifecycleOwner) = onBackground()
            })
        }
        networkWatch.start()
        // At once, so the first frame is already in the theme's colors. No terminal is open yet.
        currentTheme().let { t ->
            Rex.wear(t?.let(::paletteOf) ?: Palette.Default)
            RemoteTerminal.themeColors = t?.terminalColors?.takeIf { settings.terminalColorsFromTheme.value }
        }
        scope.launch(Dispatchers.Main) {
            combine(theme, settings.terminalColorsFromTheme.flow) { t, terminalsToo -> t to terminalsToo }
                .collect { (t, terminalsToo) -> wear(t, terminalsToo) }
        }
        scope.launch(Dispatchers.Main) {
            // Turned on or off from here on: coming to the foreground starts it if it is on.
            settings.watchInBackground.flow.drop(1).collect { on ->
                if (on) {
                    // Android only lets an app start one from the foreground, where the switch is.
                    if (foreground) startWatching()
                } else {
                    context.stopService(Intent(context, WatchService::class.java))
                    // Stopped from its notification while away: leave as leaving without it would have.
                    if (!foreground && backgroundJob?.isActive != true) {
                        closedWhileAway = true
                        _connections.value.forEach { it.disconnect() }
                    }
                }
            }
        }
    }

    private fun startWatching() {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java)) }
    }

    /** From [WatchService] once it runs, which after Android restarted it may be before the app opens. */
    fun onWatchStarted() {
        if (!foreground) _connections.value.forEach { it.connect() }
    }

    fun connection(hostId: String): HostConnection? = _connections.value.firstOrNull { it.id == hostId }

    /**
     * Where a pane is, as a terminal screen opens it, once its host has said: opened from a
     * notification, the app may still be connecting. Null if it doesn't turn up in [timeoutMs].
     */
    suspend fun paneTarget(hostId: String, sessionId: String, blockId: String, timeoutMs: Long = 15_000): Target? {
        val host = connection(hostId) ?: return null
        host.connect()
        return withTimeoutOrNull(timeoutMs) {
            host.state.map { st -> st.views[sessionId]?.windows?.firstOrNull { w -> w.blocks.any { it.blockId == blockId } } }
                .first { it != null }
                ?.let { Target(hostId, sessionId, it.windowId, blockId) }
        }
    }

    // Recently viewed terminals stay streaming, so moving between panes and tabs is instant.
    private val terminals = LinkedHashMap<String, RemoteTerminal>(16, 0.75f, true)

    /** The live terminal for a block, started if needed, and streaming again if it was paused. Main thread only. */
    fun terminal(host: HostConnection, sessionId: String, blockId: String): RemoteTerminal {
        terminals[blockId]?.let { t ->
            if (t.host === host) return t.also { if (it.paused && foreground) it.resume() }
            terminals.remove(blockId)?.close()
        }
        val t = RemoteTerminal(host, sessionId, blockId, http)
        terminals[blockId] = t
        t.start()
        while (terminals.size > MAX_TERMINALS) {
            val eldest = terminals.entries.first()
            terminals.remove(eldest.key)
            eldest.value.close()
        }
        return t
    }

    /**
     * Start streaming a block that may be looked at next, such as the tab beside the one on screen,
     * so it shows at once. One already streaming is left where it is in the recent order, and none
     * starts while the app is away. Main thread only.
     */
    fun prewarm(host: HostConnection, sessionId: String, blockId: String) {
        // Not terminals[blockId], which would count as a visit in the recent order.
        if (!foreground || terminals.entries.any { it.key == blockId && it.value.host === host }) return
        terminal(host, sessionId, blockId)
    }

    /** Forget a block's terminal, such as when the block closed. Main thread only. */
    fun dropTerminal(blockId: String) {
        terminals.remove(blockId)?.close()
    }

    /** Close and forget the terminals that match. Main thread only. */
    private fun closeTerminals(which: (RemoteTerminal) -> Boolean) {
        val each = terminals.values.iterator()
        while (each.hasNext()) {
            val t = each.next()
            if (which(t)) {
                each.remove()
                t.close()
            }
        }
    }

    private fun syncConnections(hosts: List<HostConfig>) {
        val current = _connections.value.associateBy { it.id }
        val next = hosts.map { cfg ->
            val existing = current[cfg.id]
            if (existing != null && existing.config == cfg) existing
            else {
                existing?.disconnect()
                HostConnection(
                    cfg, http, scope, onMoved = { url -> hostStore.update(cfg.copy(url = url)) },
                    reviewed = Reviewed(reviewedPrefs, cfg.id), onAlert = ::onAlert,
                )
                    .also { if (foreground || settings.watchInBackground.value) it.connect() }
            }
        }
        val gone = current.values.filter { c -> next.none { it === c } }
        gone.forEach { it.disconnect() }
        _connections.value = next
        // Their terminals would stream from a connection gone for good. An edited host's open again on its new one.
        if (gone.isNotEmpty()) scope.launch(Dispatchers.Main) { closeTerminals { t -> gone.any { it === t.host } } }
    }

    private fun onAlert(alert: Alert) {
        if (alert.blockId == visibleBlockId && alert.kind == Alert.Kind.BELL) return
        // A kind turned off in Settings still marks its tab unseen, but shows no banner and doesn't buzz.
        if (!settings.alertShown(alert.kind)) return
        // Away, a notification says it too; a bell isn't worth one.
        if (!foreground && settings.watchInBackground.value && alert.kind != Alert.Kind.BELL) {
            notifier.post(alert, connection(alert.hostId)?.config?.label)
        }
        // The pane on screen already shows its question or result: no banner or buzz for it.
        if (foreground && alert.blockId == visibleBlockId) return
        _alerts.tryEmit(alert)
        if (foreground && settings.alertVibrate.value && alert.kind in setOf(Alert.Kind.BLOCKED, Alert.Kind.NOTIFICATION)) buzz()
    }

    private fun buzz() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 30), -1))
    }

    /** Main thread, from the process lifecycle. */
    fun onForeground() {
        foreground = true
        backgroundJob?.cancel()
        // What they said is on Home now, as each tab's dot.
        notifier.cancelAll()
        if (settings.watchInBackground.value) startWatching()
        val returned = SystemClock.elapsedRealtime()
        val away = leftAt?.let { returned - it }
        leftAt = null
        val closed = closedWhileAway
        closedWhileAway = false

        // A stream still live is left alone: dropping it on every return showed "Reconnecting…" each time.
        // Of the paused ones only the terminal on screen streams again; the others wait until they are
        // opened, rather than load alongside it on a slow link.
        val visible = visibleBlockId
        // Panes closed on the host stay closed, so there is nothing to stream or keep.
        closeTerminals { it.streamState.value == StreamState.ENDED && it.blockId != visible }
        val kept = ArrayList<RemoteTerminal>()
        val watched = terminals.values.map { t ->
            when {
                t.paused && t.blockId != visible -> Watch(t, "left paused until opened", watch = false)
                t.paused -> Watch(t, "streamed again").also { t.resume() }
                t.streamState.value != StreamState.LIVE -> Watch(t, "was ${t.streamState.value.name.lowercase()}, retried now").also { t.reconnectNow() }
                else -> Watch(t, "kept its stream").also { kept += t }
            }
        }
        // A stream kept from before leaving can't be trusted when its host is silent, unless it is still
        // receiving, such as a large snapshot: dropped partway, the server can't resume it, so it starts over.
        val hosts = checkHosts { host -> kept.filter { it.host === host && !it.paused } }
        if (away != null) scope.launch { logReturn(returned, away, closed, hosts, watched) }
    }

    /**
     * Check every host's connection, reconnecting a silent one and, on the main thread, the [suspect]
     * streams of a silent host that have gone quiet. Each answer says how its host's check went.
     */
    private fun checkHosts(suspect: (HostConnection) -> List<RemoteTerminal>): List<CompletableDeferred<String>> =
        _connections.value.map { host ->
            val answer = CompletableDeferred<String>()
            host.connect()
            host.ensureAlive { liveness, ms ->
                answer.complete(
                    "${host.config.label}: " + when (liveness) {
                        Liveness.ANSWERED -> "answered in $ms ms"
                        Liveness.NOT_OPEN -> "had no connection"
                        Liveness.BUSY -> "no answer in $ms ms behind replies still arriving, kept"
                        Liveness.SILENT -> "no answer in $ms ms, reconnecting"
                    },
                )
                if (liveness == Liveness.SILENT) scope.launch(Dispatchers.Main) {
                    if (foreground) suspect(host).forEach { it.reconnectIfQuiet(RemoteTerminal.QUIET_STREAM_MS) }
                }
            }
            answer
        }

    /**
     * Main thread. The phone moved to another network, such as from Wi-Fi to cellular: a connection
     * made over the old one can sit dead until its next ping fails, so each is checked now, and a
     * stream waiting to retry tries at once.
     */
    private fun onNetworkChanged(now: String) {
        if (!foreground) return
        val retried = terminals.values.filter { !it.paused && it.streamState.value != StreamState.LIVE }
        retried.forEach { it.reconnectNow() }
        val hosts = checkHosts { host -> terminals.values.filter { it.host === host && !it.paused } }
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        scope.launch {
            val lines = hosts.map { "  " + (withTimeoutOrNull(RETURN_WATCH_MS) { it.await() } ?: "no answer") }
            val streams = if (retried.isEmpty()) emptyList() else listOf("  ${retried.size} waiting stream(s) retried at once")
            val entry = (listOf("$at  network changed to $now") + lines + streams).joinToString("\n")
            withContext(Dispatchers.IO) { returnLog.add(entry) }
        }
    }

    /** Main thread, from the process lifecycle. */
    fun onBackground() {
        foreground = false
        leftAt = SystemClock.elapsedRealtime()
        backgroundJob?.cancel()
        // Keep connections a little while, so switching apps briefly costs nothing.
        backgroundJob = scope.launch {
            delay(settings.stayConnectedSeconds.value * 1000L)
            // Terminals keep their screens to show on return, while only their streams stop.
            val handBacks = withContext(Dispatchers.Main) { if (foreground) null else terminals.values.mapNotNull { it.pause() } }
                ?: return@launch
            withTimeoutOrNull(3_000) { handBacks.joinAll() }
            // Watching keeps the control connections, to hear what agents do, and nothing else.
            if (!foreground && !settings.watchInBackground.value) {
                closedWhileAway = true
                _connections.value.forEach { it.disconnect() }
            }
        }
    }

    private suspend fun logReturn(
        returned: Long,
        away: Long,
        closed: Boolean,
        hosts: List<CompletableDeferred<String>>,
        watched: List<Watch>,
    ) = coroutineScope {
        val terminalLines = watched.map { w ->
            async { "  ${terminalName(w.terminal)}: ${w.how}" + if (w.watch) "; ${watchReturn(w.terminal, returned)}" else "" }
        }
        val hostLines = hosts.map { async { "  " + (withTimeoutOrNull(RETURN_WATCH_MS) { it.await() } ?: "no answer") } }
        val header = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}  away ${seconds(away)}, " +
            (if (closed) "connections closed" else "connections kept") + " (stay connected ${settings.stayConnectedSeconds.value} s)"
        val entry = (listOf(header) + hostLines.awaitAll() + terminalLines.awaitAll()).joinToString("\n")
        withContext(Dispatchers.IO) { returnLog.add(entry) }
    }

    /** How a terminal came back: when it was live, whether it was drawn again, and whether a kept stream dropped soon after. */
    private suspend fun watchReturn(t: RemoteTerminal, returned: Long): String {
        fun since() = SystemClock.elapsedRealtime() - returned
        var redrawn = false
        // Shown ahead of its snapshot, a screen is live once the stream has caught up.
        val live = withTimeoutOrNull(RETURN_WATCH_MS) {
            combine(t.streamState, t.syncing, t.catchingUp) { state, syncing, catchingUp -> Triple(state, syncing, catchingUp) }
                .first { (state, syncing, catchingUp) ->
                    if (syncing || catchingUp) redrawn = true
                    state == StreamState.LIVE && !syncing && !catchingUp
                }
            since()
        } ?: return (if (t.catchingUp.value) "shown in ${t.shownEarlyIn} ms, " else "") +
            "not live after ${seconds(RETURN_WATCH_MS)} (${t.streamState.value.name.lowercase()})"
        val parts = mutableListOf(if (live < 50) "live at once" else "live in $live ms", if (redrawn) "drawn again (${t.lastLoad})" else "no redraw")
        // A kept socket the server closed while the app was frozen shows up within moments of coming back.
        val dropped = withTimeoutOrNull((DROP_WATCH_MS - since()).coerceAtLeast(0)) {
            t.streamState.first { it != StreamState.LIVE }
            since()
        }
        if (dropped != null) {
            val again = withTimeoutOrNull(RETURN_WATCH_MS) {
                t.streamState.first { it == StreamState.LIVE }
                since()
            }
            parts += "dropped at $dropped ms, " + (again?.let { "live again at $it ms" } ?: "not live again")
        }
        t.keepMs?.let { parts += "server keeps ${seconds(it)}" }
        return parts.joinToString(", ")
    }

    /** By its title and the end of its id: every block id starts alike. */
    private fun terminalName(t: RemoteTerminal): String {
        val title = t.host.state.value.blocks[t.blockId]?.title.orEmpty().take(40)
        return "block …" + t.blockId.takeLast(4) + if (title.isNotBlank()) " \"$title\"" else ""
    }

    /** A terminal on a return to the app: how it was handled, and whether to watch how it comes back. */
    private class Watch(val terminal: RemoteTerminal, val how: String, val watch: Boolean = true)

    /** Tenths for a short trip, which is what the kept connections are for. */
    private fun seconds(ms: Long) = if (ms < 10_000) String.format(Locale.US, "%.1f s", ms / 1000.0) else "${(ms + 500) / 1000} s"

    private companion object {
        /** How long a return is watched before it is logged. */
        const val RETURN_WATCH_MS = 20_000L
        /** How long after a return a kept stream dropping counts as it not having survived the time away. */
        const val DROP_WATCH_MS = 5_000L
    }
}
