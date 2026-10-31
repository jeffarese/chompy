package com.jeffarese.rexmobile.terminal

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConnection
import com.jeffarese.rexmobile.rex.BlockStream
import com.jeffarese.rexmobile.rex.BlockStreamListener
import com.jeffarese.rexmobile.rex.FormatResult
import com.jeffarese.rexmobile.rex.RexCallException
import com.jeffarese.rexmobile.rex.RexJson
import com.jeffarese.rexmobile.rex.StreamState
import com.jeffarese.rexmobile.rex.int
import com.jeffarese.rexmobile.rex.obj
import com.jeffarese.rexmobile.rex.str
import com.jeffarese.rexmobile.terminal.emulator.SnapshotDecoder
import com.jeffarese.rexmobile.terminal.emulator.TerminalSession
import com.jeffarese.rexmobile.terminal.emulator.TerminalSessionClient
import com.jeffarese.rexmobile.terminal.emulator.TextStyle
import com.jeffarese.rexmobile.terminal.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

data class RemoteSize(val columns: Int, val rows: Int)

/**
 * A key for [RemoteTerminal.sendKey], named as Rex names physical keys. The server encodes it for
 * the program's current keyboard mode, such as the Kitty keyboard protocol Claude Code turns on.
 */
data class RemoteKey(
    /** The physical key, such as "escape", "enter", "arrow_up" or "key_c". */
    val key: String,
    /** The modifiers held, joined with +, such as "ctrl" or "shift". */
    val mods: String = "",
    /** The text the key types, if any. */
    val text: String = "",
    /** The code point the key types without shift, such as 99 for c; ctrl+c is encoded from it. */
    val codepoint: Int = 0,
) {
    companion object {
        val ESCAPE = RemoteKey("escape", codepoint = 27)
        val ENTER = RemoteKey("enter", text = "\r", codepoint = 13)
        val TAB = RemoteKey("tab", text = "\t", codepoint = 9)
        val SHIFT_TAB = RemoteKey("tab", "shift", codepoint = 9)
        val UP = RemoteKey("arrow_up")
        val DOWN = RemoteKey("arrow_down")
        val LEFT = RemoteKey("arrow_left")
        val RIGHT = RemoteKey("arrow_right")
        val CTRL_C = ctrl('c')

        /** Ctrl and a letter, such as ctrl('c'). */
        fun ctrl(letter: Char) = letter.lowercaseChar().let { RemoteKey("key_$it", "ctrl", codepoint = it.code) }

        /** A digit key from the main row, 0 to 9. */
        fun digit(n: Int): RemoteKey {
            require(n in 0..9)
            return RemoteKey("digit_$n", text = "$n", codepoint = '0'.code + n)
        }

        /** A letter key, a to z, without shift. */
        fun letter(c: Char): RemoteKey {
            require(c in 'a'..'z')
            return RemoteKey("key_$c", text = "$c", codepoint = c.code)
        }
    }
}

/**
 * One remote terminal block shown on this phone: its live stream, the emulator that draws it,
 * and the input that goes back to it.
 *
 * The stream's snapshot can't be loaded here, so after it the screen is redrawn from
 * `terminal.format` (escape sequences). To keep that redraw and the live output from overlapping,
 * the format call waits for the stream to go quiet, and output that arrived before the call is
 * dropped because the redraw already shows it.
 *
 * A long conversation's snapshot runs to megabytes, which a slow link takes many seconds over. Rex
 * servers before build 1531 don't compress data connections; later ones do, which OkHttp asks for,
 * but a slow enough link still waits. The control connection is always compressed, so meanwhile the
 * screen is read there and shown, and the redraw after the snapshot brings it up to date.
 */
class RemoteTerminal(
    val host: HostConnection,
    val sessionId: String,
    val blockId: String,
    http: OkHttpClient,
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _streamState = MutableStateFlow(StreamState.CONNECTING)
    val streamState: StateFlow<StreamState> = _streamState
    private val _remoteSize = MutableStateFlow<RemoteSize?>(null)
    val remoteSize: StateFlow<RemoteSize?> = _remoteSize
    private val _syncing = MutableStateFlow(true)
    /** True until the first screen has been drawn. */
    val syncing: StateFlow<Boolean> = _syncing
    private val _catchingUp = MutableStateFlow(false)
    /**
     * The screen was read while the stream's snapshot is still on its way: it can be read and typed
     * into, and output since shows once the stream catches up.
     */
    val catchingUp: StateFlow<Boolean> = _catchingUp
    private val _phoneSized = MutableStateFlow(false)
    val phoneSized: StateFlow<Boolean> = _phoneSized
    private val _screenInfo = MutableStateFlow(ScreenInfo())
    /**
     * What the screen is asking for, read again shortly after output settles. Its interruptible
     * flag is also set while the program reports to Rex that it is working.
     */
    val screenInfo: StateFlow<ScreenInfo> = _screenInfo

    var view: TerminalView? = null
        set(value) {
            field?.setWheelSink(null)
            field?.setWatcher(null)
            if (field != null && value == null) {
                // The rows read for the view go, and the history they unpacked is packed again.
                session.emulator?.screen?.dropRows()
                recompress()
            }
            field = value
            shown = value?.isWindowShown == true
            if (value != null) {
                viewed = true
                value.setWheelSink(wheelSink)
                value.setWatcher(watcher)
                colors()?.let { applyThemeGlobally(it) }
                // Output held back while nothing showed it.
                flush()
                main.post { value.onScreenUpdated() }
                if (unread) {
                    // Output came while it was hidden, so what was read last may be gone.
                    unread = false
                    if (lastRead.prompt != null || lastRead.fyi != null) publishScreenInfo(lastRead.copy(prompt = null, fyi = null))
                    if (phase == Phase.LIVE && streamLive) scheduleScreenRead()
                }
            }
        }

    /** A view has shown this terminal, unlike one only opened ahead of time. Main thread only. */
    var viewed = false
        private set

    /**
     * A view shows the screen and its window can be seen, so output is drawn as it comes. Set on the
     * main thread, read on OkHttp's.
     */
    @Volatile private var shown = false

    private val watcher = object : TerminalView.Watcher {
        override fun onWindowShown(shown: Boolean) {
            this@RemoteTerminal.shown = shown
            if (!shown) return
            flush()
            if (unread) {
                // Output came while the app was away: read the screen at once, so a prompt still on it stays shown.
                unread = false
                main.removeCallbacks(readScreenInfo)
                readScreenInfo.run()
            }
        }

        override fun onBackFromHistory() = recompress()
    }

    // Output handling. `pending` is filled on OkHttp's thread and drained on the main thread.
    private val pending = ByteArrayOutputStream()
    private var flushPosted = false
    /** The flush posted waits, for output nobody sees: see [HIDDEN_FLUSH_MS]. Guarded by `pending`. */
    private var flushDelayed = false
    private val flushOutput = Runnable { flush() }
    /** What `pending` holds is a fresh stream's, kept for [startFresh] to take: it hasn't run yet. */
    private var freshPending = false
    @Volatile private var lastByteAt = 0L

    private enum class Phase { WAITING, SETTLING, LIVE }
    private var phase = Phase.WAITING // main thread only
    private val held = ByteArrayOutputStream() // main thread only
    private var syncJob: Job? = null
    /** Reads the screen while a fresh stream's snapshot is on its way. Main thread only. */
    private var earlyJob: Job? = null
    /** The screen went live without its redraw, which the stream's next return to live asks for again. Main thread only. */
    private var redrawOwed = false
    private var theme: IntArray? = null

    // Reading the screen for prompts. Main thread only, like the emulator.
    private var readBy = 0L // when a pending read must run at the latest; 0 if none is pending
    private var lastRead = ScreenInfo()
    private var programWorking = false
    /** True while the stream is live, so the screen keeps up with the block. */
    private var streamLive = false
    /** The screen changed while no view showed it, so it is read once one does. */
    private var unread = false
    private val readScreenInfo = Runnable {
        readBy = 0L
        if (!shown) return@Runnable run { unread = true }
        // Not from a frame half drawn: it is whole within SYNC_UPDATE_TIMEOUT_MS.
        if (session.isUpdateHeld) return@Runnable scheduleScreenRead()
        val emulator = session.emulator
        if (phase == Phase.LIVE && streamLive && emulator != null) publishScreenInfo(withFyiSoFar(ScreenReader.read(emulator)))
    }

    private val sessionClient = SessionClient { view }

    val session = TerminalSession(TRANSCRIPT_ROWS, sessionClient, object : TerminalSession.RemoteInput {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            input.offer(InputQueue.Item.Bytes(data.copyOfRange(offset, offset + count)))
        }

        override fun onViewSize(columns: Int, rows: Int) {
            lastViewSize = RemoteSize(columns, rows)
            if (_phoneSized.value) requestPhoneSize()
        }
    })

    private var lastViewSize: RemoteSize? = null
    private var phoneResizeJob: Job? = null
    /** The size last asked of the server. */
    @Volatile private var requestedSize: RemoteSize? = null
    private var ownershipJob: Job? = null

    private val stream = BlockStream(http, host.config.url, scope, { host.dataTicket(sessionId, blockId) },
        object : BlockStreamListener {
            override fun onFresh() {
                synchronized(pending) {
                    // The stream before this one, and its output not drawn yet, is over; what follows is the fresh one's.
                    pending.reset()
                    freshPending = true
                }
                main.post { startFresh() }
            }
            override fun onSnapshot(bytes: ByteArray, offset: Int, length: Int) {
                val part = bytes.copyOfRange(offset, offset + length)
                main.post { feedSnapshot(part) }
            }
            override fun onReady(streamBytes: Long) = main.post {
                snapshotBytes = streamBytes
                // A snapshot shown is the screen, and its history follows; one that couldn't be read is redrawn instead.
                if (snapshotShown) goLive() else startSync()
            }.let { }
            override fun onOutput(bytes: ByteArray, offset: Int, length: Int) {
                lastByteAt = SystemClock.uptimeMillis()
                val wait: Boolean
                synchronized(pending) {
                    pending.write(bytes, offset, length)
                    wait = !shown && pending.size() < HIDDEN_FLUSH_BYTES
                    if (flushPosted && (wait || !flushDelayed)) return
                    flushPosted = true
                    flushDelayed = wait
                }
                if (wait) {
                    main.postDelayed(flushOutput, HIDDEN_FLUSH_MS)
                } else {
                    main.removeCallbacks(flushOutput)
                    main.post(flushOutput)
                }
            }

            override fun onMessage(msg: JsonObject) = main.post { handleMessage(msg) }.let { }
            override fun onOpened(compressed: Boolean) = host.noteDataCompression(compressed)
            override fun onState(state: StreamState, detail: String?) {
                _streamState.value = state
                main.post { onStreamState(state) }
            }
        })

    init {
        // The control connection dropping may mean the path is gone, which the stream can't tell.
        scope.launch { host.drops.drop(1).collect { stream.reconnectIfQuiet(QUIET_STREAM_MS) } }
        // Connected again, maybe as a new client, which owns nothing: the block may have gone to the desktop.
        scope.launch {
            host.state.map { it.status == ConnStatus.ONLINE }.distinctUntilChanged().drop(1).filter { it }.collect { reclaim() }
        }
        scope.launch {
            host.state.map { st -> st.blocks[blockId]?.statuses.orEmpty().any { it.state == "working" } }
                .distinctUntilChanged()
                .collect {
                    programWorking = it
                    publishScreenInfo(lastRead)
                }
        }
    }

    fun start() = stream.start()

    /**
     * How a fresh stream's first screen loaded: what took the time, for the return log. A screen
     * [restored] from the stream's snapshot showed once [screenBytes] of it had arrived; otherwise it
     * was redrawn from `terminal.format` once all of it had.
     */
    data class Load(
        val snapshotBytes: Long,
        val snapshotMs: Long,
        val quietMs: Long,
        val redrawChars: Int,
        val redrawMs: Long,
        val drawMs: Long,
        val shownEarlyIn: Long? = null,
        val restored: Boolean = false,
        val screenBytes: Long = 0,
        val snapshotUnreadable: Boolean = false,
    ) {
        override fun toString() = (shownEarlyIn?.let { "shown in $it ms" + (if (restored) " after ${kb(screenBytes)}" else "") + ", " } ?: "") +
            "snapshot ${kb(snapshotBytes)} in $snapshotMs ms, " +
            if (restored) "restored" else {
                (if (snapshotUnreadable) "unreadable, " else "") + "waited $quietMs ms for quiet, " +
                    "redraw ${(redrawChars + 500) / 1000}k characters in $redrawMs ms, drawn in $drawMs ms"
            }

        private fun kb(bytes: Long) = "${(bytes + 512) / 1024} KB"
    }

    /** The last fresh stream's load, once its first screen is drawn. */
    @Volatile var lastLoad: Load? = null
        private set
    /** How long after the last fresh stream began its screen was shown ahead of the snapshot, if it was. */
    @Volatile var shownEarlyIn: Long? = null
        private set
    private var freshAt = 0L // main thread only
    private var snapshotBytes = 0L // main thread only
    /** Restores the fresh stream's snapshot as it arrives, until its history is all in. Main thread only. */
    private var snapshot: SnapshotDecoder? = null
    /** The fresh stream's snapshot is on screen. Main thread only. */
    private var snapshotShown = false
    private var snapshotUnreadable = false // main thread only
    private var screenBytes = 0L // main thread only

    /** True between [pause] and [resume]. Main thread only. */
    var paused = false
        private set

    /** How long the server keeps this stream to resume after a drop, once it has said. */
    val keepMs: Long? get() = stream.keepMs

    /**
     * Stop streaming while the app is away, keeping the screen as it was to show on return until
     * the stream catches up. A phone-sized block goes back to the desktop meanwhile; the returned
     * job is that hand-back, to finish before the connection closes. Main thread only.
     */
    fun pause(): Job? {
        if (paused) return null
        paused = true
        // A redraw under way is asked for again once the stream is back.
        syncJob?.cancel()
        earlyJob?.cancel()
        stream.pause()
        return if (_phoneSized.value) inOrder { host.disownBlock(sessionId, blockId) } else null
    }

    /** Stream again after [pause], taking a phone-sized block back. Main thread only. */
    fun resume() {
        if (!paused) return
        paused = false
        reclaim()
        reconnectNow()
    }

    /**
     * Take a phone-sized block back and size it to this screen again. When the phone's connection
     * drops, Rex hands its blocks to the desktop and gives them back only to the same client
     * reconnecting, so a new connection, or a take sent on one that was dead, leaves the block at the
     * desktop's size, wider than this screen. Main thread only.
     */
    fun reclaim() {
        if (!_phoneSized.value || paused) return
        inOrder {
            runCatching { host.ownBlock(sessionId, blockId) }
            // The desktop may have resized it meanwhile, whatever the size last seen here.
            withContext(Dispatchers.Main) { requestPhoneSize(force = true) }
        }
    }

    fun reconnectNow() {
        // Drop the prompt at once rather than when the stream reports it is reconnecting.
        if (Looper.myLooper() == Looper.getMainLooper()) forgetPrompt() else main.post { forgetPrompt() }
        stream.reconnectNow()
    }

    /** Reconnect if nothing has arrived for [quietMs]: a stream still receiving, such as a large snapshot, is left to finish. */
    fun reconnectIfQuiet(quietMs: Long) = stream.reconnectIfQuiet(quietMs)

    /** Closed for good: a fresh stream reported after this starts nothing. */
    @Volatile
    private var closed = false

    fun close() {
        closed = true
        main.removeCallbacks(readScreenInfo)
        main.removeCallbacks(compressHistory)
        main.removeCallbacks(flushOutput)
        main.post {
            snapshot?.close()
            snapshot = null
            // The terminal's screen and history are native memory, which the garbage collector doesn't see.
            session.release()
        }
        if (_phoneSized.value) {
            // Hand the block back so the desktop gets its own size again.
            CoroutineScope(Dispatchers.Default).launch { host.disownBlock(sessionId, blockId) }
        }
        stream.stop()
        scope.cancel()
    }

    // ---- Output ----

    private fun onStreamState(state: StreamState) {
        if (state != StreamState.LIVE) return forgetPrompt()
        streamLive = true
        // A stream resumed while its redraw was stopped, as a pause does, is past its snapshot, so
        // that won't start it again; nor will it after the last redraw failed for good.
        if ((phase == Phase.SETTLING || redrawOwed) && syncJob?.isActive != true) return startSync()
        // A resumed stream replays what it missed, if anything, and the read waits for that to settle.
        // A fresh one reads once it has redrawn the screen.
        if (phase == Phase.LIVE) scheduleScreenRead()
    }

    /**
     * Stop offering the prompt on screen until the stream is live again and the screen has been
     * read since: until then the screen may be out of date, and a tap on an old card would answer
     * whatever is on screen by the time it arrives.
     */
    private fun forgetPrompt() {
        streamLive = false
        main.removeCallbacks(readScreenInfo)
        readBy = 0L
        if (lastRead.prompt != null || lastRead.fyi != null) publishScreenInfo(lastRead.copy(prompt = null, fyi = null))
    }

    private fun startFresh() {
        freshAt = SystemClock.uptimeMillis()
        lastLoad = null
        wheelPacer.clear()
        phase = Phase.WAITING
        // Its snapshot's end asks for the redraw.
        redrawOwed = false
        held.reset()
        synchronized(pending) { freshPending = false }
        syncJob?.cancel()
        _syncing.value = true
        main.removeCallbacks(readScreenInfo)
        readBy = 0L
        publishScreenInfo(ScreenInfo())
        earlyJob?.cancel()
        _catchingUp.value = false
        shownEarlyIn = null
        snapshot?.close()
        snapshot = null
        if (closed) return
        snapshot = SnapshotDecoder()
        snapshotShown = false
        snapshotUnreadable = false
        screenBytes = 0L
        // Output of the fresh stream that came before this, now held with the rest.
        flush()
    }

    /**
     * Restore the screen from the snapshot as it arrives. The screen comes first in it, so a long
     * conversation shows once a screenful has arrived; its history follows, and is added once all of
     * it has. A snapshot that can't be read, such as one from a Rex whose Ghostty writes a newer
     * format, is redrawn the old way once it is over.
     */
    private fun feedSnapshot(bytes: ByteArray) {
        val decoder = snapshot ?: return
        if (phase != Phase.WAITING || decoder.state() == SnapshotDecoder.FAILED) return
        val state = decoder.feed(bytes, 0, bytes.size)
        if (state == SnapshotDecoder.FAILED) return snapshotUnreadable()
        if (state >= SnapshotDecoder.READY && !snapshotShown) showSnapshot(decoder)
        if (state == SnapshotDecoder.COMPLETE && snapshotShown) restoreHistory(decoder)
    }

    private fun showSnapshot(decoder: SnapshotDecoder) {
        if (!session.showSnapshot(decoder)) return snapshotUnreadable()
        snapshotShown = true
        screenBytes = decoder.readyBytes()
        earlyJob?.cancel()
        main.removeCallbacks(releaseHeldFrame)
        colors()?.let { applyTheme(it) }
        shownEarlyIn = SystemClock.uptimeMillis() - freshAt
        // Output since the stream began follows the snapshot: the screen is catching up until it arrives.
        _catchingUp.value = true
        _syncing.value = false
    }

    /**
     * Add the snapshot's history above the screen, all of it now: what follows the snapshot in the
     * stream, output or a resize, is already on its way, and the history has to be in before either
     * reaches the terminal. Older pages can't go above a resized terminal, and would come back above
     * one whose program cleared its history. It stops at the rows the terminal keeps, a few
     * milliseconds' work.
     */
    private fun restoreHistory(decoder: SnapshotDecoder) {
        // Until the history is all there, or the rest of it can't be read and what came stays.
        while (session.restoreHistory(decoder, Int.MAX_VALUE) >= 0) Unit
        decoder.close()
        snapshot = null
        compressWhenQuiet()
    }

    /**
     * The old way: the screen read on the side once the snapshot has taken a while, then a redraw
     * once it is over. A readable snapshot shows the screen sooner than the side could, and on a
     * shared link the read would only slow it: Rex sends the snapshot in large messages, so on a slow
     * link nothing of it may have arrived for a second or more.
     */
    private fun snapshotUnreadable() {
        snapshotUnreadable = true
        snapshot?.close()
        snapshot = null
        earlyJob?.cancel()
        // A small snapshot is over before this, and its redraw needs no help.
        earlyJob = scope.launch {
            delay((EARLY_SCREEN_AFTER_MS - (SystemClock.uptimeMillis() - freshAt)).coerceAtLeast(0))
            if (phase == Phase.WAITING && !paused) showEarly()
        }
    }

    /** The snapshot shown is over: the output since, held meanwhile, is drawn over it, and the screen is live. */
    private fun goLive() {
        // The snapshot is over, and its history in if it had any to add.
        snapshot?.close()
        snapshot = null
        // Output that arrived since joins what was held, after it, before any of it is drawn.
        flush()
        val after = held.toByteArray()
        held.reset()
        if (after.isNotEmpty()) feed(after)
        phase = Phase.LIVE
        lastLoad = Load(snapshotBytes, SystemClock.uptimeMillis() - freshAt, 0, 0, 0, 0, shownEarlyIn, restored = true, screenBytes = screenBytes)
        _syncing.value = false
        _catchingUp.value = false
        scheduleScreenRead()
    }

    /**
     * Show the screen while the snapshot is still on its way, read on the control connection. Output
     * since the stream began follows the snapshot, and the redraw after it replaces this screen, so
     * nothing shown here is lost or repeated. A first open asks the size, which also follows it.
     */
    private suspend fun showEarly() {
        val content = try {
            if (_remoteSize.value == null) readSize()?.let { setRemoteSize(it.columns, it.rows) }
            readScreen(_remoteSize.value?.columns)
        } catch (e: Exception) {
            // Cancelled by the snapshot's end, or not to be had: the redraw after it comes as ever.
            return
        }
        if (phase != Phase.WAITING || paused || snapshotShown) return
        session.clearForFreshStream()
        main.removeCallbacks(releaseHeldFrame)
        colors()?.let { applyTheme(it) }
        feed(content.toByteArray())
        shownEarlyIn = SystemClock.uptimeMillis() - freshAt
        _catchingUp.value = true
        _syncing.value = false
    }

    private suspend fun readSize(): RemoteSize? {
        val r = host.blockMethod(sessionId, blockId, "size", timeoutMs = READ_TIMEOUT_MS) as? JsonObject ?: return null
        return RemoteSize(r.int("columns") ?: return null, r.int("rows") ?: return null)
    }

    private fun flush() {
        // Whatever was posted is done here.
        main.removeCallbacks(flushOutput)
        val bytes: ByteArray
        synchronized(pending) {
            flushPosted = false
            // A fresh stream's output waits for startFresh, which takes it.
            if (freshPending || pending.size() == 0) return
            bytes = pending.toByteArray()
            pending.reset()
        }
        when (phase) {
            Phase.LIVE -> {
                feed(bytes)
                scheduleScreenRead()
            }
            else -> held.write(bytes)
        }
    }

    /** The terminal's compression activity when compressing was last scheduled. Main thread only. */
    private var compressionActivity = 0L

    /** Compress the history a short step at a time while the stream is quiet, which holds a long conversation in a fraction of the memory. */
    private val compressHistory = object : Runnable {
        override fun run() {
            if (session.emulator?.compressHistory(false) == true) main.post(this)
        }
    }

    private fun compressWhenQuiet() {
        val activity = session.emulator?.compressionActivity ?: return
        if (activity == compressionActivity) return
        compressionActivity = activity
        recompress()
    }

    /**
     * Compress the history after a quiet spell, whatever the terminal's activity: reading old rows,
     * such as scrolling back through them, unpacks their pages without counting as activity.
     */
    private fun recompress() {
        main.removeCallbacks(compressHistory)
        main.postDelayed(compressHistory, COMPRESS_AFTER_QUIET_MS)
    }

    /**
     * Let go of what reading the terminal took: the rows copied out of it, and the history unpacked,
     * which is compressed again at once. For when memory runs short. Main thread only.
     */
    fun trimMemory() {
        val emulator = session.emulator ?: return
        emulator.screen.dropRows()
        main.removeCallbacks(compressHistory)
        emulator.compressHistory(true)
    }

    /** Shows a frame whose synchronized update never ended, as a program stopped partway through leaves. */
    private val releaseHeldFrame = Runnable { session.releaseHeldUpdate() }

    /** Feed output to the emulator, giving a frame that stops partway through a synchronized update a deadline. */
    private fun feed(bytes: ByteArray) {
        val wasHeld = session.isUpdateHeld
        session.feed(bytes, bytes.size)
        compressWhenQuiet()
        when {
            !session.isUpdateHeld -> main.removeCallbacks(releaseHeldFrame)
            !wasHeld -> main.postDelayed(releaseHeldFrame, SYNC_UPDATE_TIMEOUT_MS)
        }
    }

    /**
     * Redraw the screen from `terminal.format`, then go live. Until a redraw arrives the screen stays
     * as it was and loading: one that fails, such as on a link too slow for the call's time, is asked
     * for again a few times. A fresh stream, a pause or closing cancels it.
     */
    private fun startSync() {
        phase = Phase.SETTLING
        syncJob?.cancel()
        // The snapshot is over, so this redraw is the one to wait for.
        earlyJob?.cancel()
        // The stream is on its way out: the one resumed after the pause starts the redraw once it is live.
        if (paused) return
        redrawOwed = false
        // Output waits for the redraw meanwhile, so the screen is loading again, as after a fresh stream.
        // One shown early stays in use while it catches up.
        if (!_catchingUp.value) _syncing.value = true
        syncJob = scope.launch {
            val started = SystemClock.uptimeMillis()
            var retryIn = SYNC_RETRY_MS
            var attempts = 0
            while (true) {
                awaitQuiet()
                flush()
                // Output so far is in the redraw; kept only in case it never comes.
                val mark = held.size()
                val asked = SystemClock.uptimeMillis()
                val content = try {
                    readScreen(_remoteSize.value?.columns)
                } catch (e: Exception) {
                    // Cancelled, which must not draw or go live for the next stream; a call that failed or timed out is tried again.
                    ensureActive()
                    // Refused, such as a block or method not found, or a reply this can't read, comes the same way again:
                    // without a redraw, input mustn't wait forever.
                    val refused = e is RexCallException && e.error.status in 400..499 && e.error.status !in RETRYABLE_STATUSES
                    val lasting = refused || e is SerializationException || e is IllegalArgumentException
                    if (lasting || ++attempts >= SYNC_ATTEMPTS) return@launch goLiveWithoutRedraw()
                    null
                }
                if (content != null) return@launch draw(content, started, asked, mark)
                delay(retryIn)
                retryIn = minOf(retryIn * 2, SYNC_RETRY_MAX_MS)
                if (stream.state == StreamState.ENDED || stream.state == StreamState.FAILED) return@launch
            }
        }
    }

    /** Wait for a quiet moment, so the redraw and the stream agree on what is on screen. A working agent is seldom quiet, so not for long. */
    private suspend fun awaitQuiet() {
        val from = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - lastByteAt < 150 && SystemClock.uptimeMillis() - from < QUIET_WAIT_MS) delay(30)
    }

    /** Draw the redraw asked for at [asked] and the output since (held from [mark]), in place of what was shown, and go live. */
    private fun draw(content: String, started: Long, asked: Long, mark: Int) {
        val read = SystemClock.uptimeMillis()
        flush()
        val all = held.toByteArray()
        val after = all.copyOfRange(mark, all.size)
        held.reset()
        session.clearForFreshStream()
        main.removeCallbacks(releaseHeldFrame)
        colors()?.let { applyTheme(it) }
        feed(content.toByteArray())
        if (after.isNotEmpty()) feed(after)
        phase = Phase.LIVE
        lastLoad = Load(
            snapshotBytes, started - freshAt, asked - started, content.length, read - asked, SystemClock.uptimeMillis() - read, shownEarlyIn,
            snapshotUnreadable = snapshotUnreadable,
        )
        _syncing.value = false
        _catchingUp.value = false
        scheduleScreenRead()
    }

    /**
     * Go live without a redraw, after asking for one failed for good: the output since is drawn over
     * the screen kept from before, so the pane can be used, and reconnecting asks for one again.
     */
    private fun goLiveWithoutRedraw() {
        flush()
        val all = held.toByteArray()
        held.reset()
        if (all.isNotEmpty()) feed(all)
        phase = Phase.LIVE
        redrawOwed = true
        _syncing.value = false
        _catchingUp.value = false
        scheduleScreenRead()
    }

    /**
     * Claude Code hides its spinner while a reply streams in, but reports "working" to Rex for the
     * whole turn, so either one makes the program interruptible.
     */
    private fun publishScreenInfo(read: ScreenInfo) {
        if (read.survey != null && read.survey != lastRead.survey) surveySince = SystemClock.uptimeMillis()
        lastRead = read
        _screenInfo.value = if (programWorking && !read.interruptible) read.copy(interruptible = true) else read
    }

    /** Read the screen 120 ms after output settles, and at least every 400 ms while it keeps coming. */
    private fun scheduleScreenRead() {
        // Nobody sees it while hidden: it is read when a view shows it.
        if (!shown) return run { unread = true }
        val now = SystemClock.uptimeMillis()
        if (readBy == 0L) readBy = now + 400
        main.removeCallbacks(readScreenInfo)
        main.postAtTime(readScreenInfo, minOf(now + 120, readBy))
    }

    /**
     * The screen and scrollback as escape sequences. A large one is read in parts, and only as far
     * back as this emulator keeps rows: a long conversation's scrollback runs to megabytes. The
     * block's [columns] tell about how many bytes those rows take.
     */
    private suspend fun readScreen(columns: Int?): String = withContext(Dispatchers.Default) {
        val first = RexJson.decodeFromJsonElement<FormatResult>(
            host.blockMethod(sessionId, blockId, "format", buildJsonObject {
                put("format", "vt")
                put("extra_cursor", true)
                put("extra_modes", true)
                put("extra_scrolling_region", true)
                put("extra_style", true)
                put("extra_charsets", true)
                put("extra_tabstops", true)
                put("max_bytes", FORMAT_HEAD_BYTES)
            }, timeoutMs = READ_TIMEOUT_MS),
        )
        val id = first.id ?: return@withContext first.content
        // An older server doesn't say how long the whole is, so it is read to its end.
        val total = first.total ?: return@withContext first.content + readBlob(id, first.content.toByteArray().size.toLong(), Long.MAX_VALUE, cut = false)
        val tail = columns?.let { KEPT_LINES.toLong() * (it + LINE_OVERHEAD_BYTES) } ?: FORMAT_TAIL_BYTES
        FormatTail.load(first.content, total, KEPT_LINES, tail) { from, until, cut -> readBlob(id, from, until, cut) }
    }

    /** A format result from byte [from] to at least [until], or to its end. [cut]: [from] may fall inside a character. */
    private suspend fun readBlob(id: Long, from: Long, until: Long, cut: Boolean): String {
        val sb = StringBuilder()
        var at = from
        while (at < until) {
            val left = until - at
            val part = host.blockMethod(sessionId, blockId, "blob_read", buildJsonObject {
                put("id", id)
                put("offset", at)
                // Not much past what is wanted, allowing for the escaping the limit counts.
                put("max_bytes", if (left < BLOB_PART_BYTES / 2) left + left / 4 + 8 * 1024 else BLOB_PART_BYTES.toLong())
            }, timeoutMs = READ_TIMEOUT_MS).jsonObject
            val data = part.str("data").orEmpty()
            at += FormatTail.bytesRead(data, fromAnyOffset = cut && sb.isEmpty())
            sb.append(data)
            if ((part["eof"] as? JsonPrimitive)?.content == "true" || data.isEmpty()) break
        }
        return sb.toString()
    }

    private fun handleMessage(msg: JsonObject) {
        when (msg.str("type")) {
            "resize" -> setRemoteSize(msg.int("cols") ?: return, msg.int("rows") ?: return)
            "theme" -> {
                theme = parseTheme(msg)
                val t = colors()!!
                applyTheme(t)
                // The scheme is shared, and a terminal out of sight mustn't recolor the one on screen: attaching applies it.
                if (view != null) applyThemeGlobally(t)
                view?.onScreenUpdated()
            }
            "child_exited" -> Unit
        }
    }

    private fun setRemoteSize(cols: Int, rows: Int) {
        session.setRemoteSize(cols, rows)
        _remoteSize.value = RemoteSize(cols, rows)
        view?.onScreenUpdated()
        if (phase == Phase.LIVE) scheduleScreenRead()
    }

    /** The colors to show: the app theme's, else the Mac's, else null for the emulator's own. Main thread only. */
    private fun colors(): IntArray? = themeColors ?: theme

    /** Show the colors again, as after the app's theme changed. Main thread only. */
    fun refreshColors() {
        val c = colors() ?: DEFAULT_COLORS
        applyTheme(c)
        if (view != null) applyThemeGlobally(c)
        view?.onScreenUpdated()
    }

    private fun applyTheme(colors: IntArray) {
        val emu = session.emulator ?: return
        System.arraycopy(colors, 0, emu.mColors.mCurrentColors, 0, colors.size)
        // The colors the program set stay over the theme, as a restored snapshot's do.
        emu.applyProgramColors()
    }

    /** A reset in the stream falls back to the global scheme, so keep it on this terminal's theme. */
    private fun applyThemeGlobally(colors: IntArray) {
        System.arraycopy(colors, 0, com.jeffarese.rexmobile.terminal.emulator.TerminalColors.COLOR_SCHEME.mDefaultColors, 0, colors.size)
    }

    // ---- Input ----

    private val input: InputQueue = InputQueue(object : InputQueue.Link {
        override suspend fun event(type: String, payload: JsonObject): Job {
            val conn = host.ready()
            val call = eventPayload(type, payload)
            // Undispatched, so the request is on the connection before this returns.
            return scope.launch(start = CoroutineStart.UNDISPATCHED) { runCatching { conn.call("block.event", call) } }
        }

        override suspend fun write(text: String) {
            host.blockMethod(sessionId, blockId, "write", buildJsonObject { put("data", text) })
        }

        override suspend fun awaitOthers() {
            lastWheelCall?.join()
        }

        override suspend fun canonical(): Boolean? {
            val mode = host.blockMethod(sessionId, blockId, "input_mode", timeoutMs = 5_000) as? JsonObject
            return (mode?.get("canonical") as? JsonPrimitive)?.booleanOrNull
        }

        override suspend fun cursorLine(): CursorLine? = withContext(Dispatchers.Main) {
            session.emulator?.let { e ->
                val row = e.cursorRow
                CursorLine(row, e.cursorCol, e.screen.getSelectedText(0, row, e.mColumns - 1, row).trimEnd())
            }
        }

        override suspend fun relaysInput(): Boolean? {
            val fg = (host.blockMethod(sessionId, blockId, "process", timeoutMs = 5_000) as? JsonObject)?.obj("foreground") ?: return null
            val name = (fg.str("argv0") ?: fg.str("name"))?.substringAfterLast('/')?.removePrefix("-")?.lowercase() ?: return null
            return name in RELAYS
        }
    })

    init {
        scope.launch(Dispatchers.Default) { input.run() }
    }

    private fun eventPayload(type: String, payload: JsonObject) = buildJsonObject {
        put("session_id", sessionId)
        put("block_id", blockId)
        putJsonObject("event") {
            put("type", type)
            put("payload", payload)
        }
    }

    /** Type raw bytes, such as a key's escape sequence. */
    fun typeBytes(bytes: ByteArray) {
        stopWheel()
        input.offer(InputQueue.Item.Bytes(bytes))
    }

    fun typeText(text: String) = typeBytes(text.toByteArray())

    /** Paste text the way the program expects pastes, such as with bracketed paste. */
    fun paste(text: String) {
        stopWheel()
        input.offer(InputQueue.Item.Paste(text))
    }

    /**
     * Press a key, in order with typed text and pastes. Unlike raw bytes, a lone Escape can't merge
     * with the keys after it, and keys reach programs that turned on the Kitty keyboard protocol in
     * the form they asked for.
     */
    fun sendKey(key: RemoteKey) {
        stopWheel()
        input.offer(InputQueue.Item.Key(key))
    }

    // ---- Wheel ----

    // Scrolling a program that reads the mouse, such as Claude Code in fullscreen mode. Main thread.
    private val wheelPacer = WheelPacer()
    private var wheelColumn = 0
    private var wheelRow = 0
    private var wheelLoop: Job? = null
    /** The last wheel event sent; once it is answered, so are the ones before it, as block.event runs in order. */
    @Volatile private var lastWheelCall: Job? = null
    /** The direction of the last wheel event sent, 1 down or -1 up; 0 before any. */
    private var lastWheelDirection = 0
    private val scrollLag = ScrollLag()
    private val _scrollLagged = MutableStateFlow(false)
    /** Wheel scrolling here has waited long on the network (see [ScrollLag]). Stays true once seen. */
    val scrollLagged: StateFlow<Boolean> = _scrollLagged

    private val wheelSink = object : TerminalView.WheelSink {
        override fun scrollLines(lines: Int, column: Int, row: Int) = scrollWheel(lines, column, row)
        override fun stopScrolling(): Boolean = stopWheel()
    }

    /**
     * Scroll the program by [lines], positive towards the end, as wheel events over cell [column],
     * [row] (from 0), sent in batches that it scrolls by about that much. See [WheelPacer].
     */
    fun scrollWheel(lines: Int, column: Int, row: Int) {
        if (lines == 0) return
        wheelColumn = column
        wheelRow = row
        wheelPacer.claude = _screenInfo.value.claudeMode != null
        // A screen or so of lines can wait to be sent; beyond that the finger is far ahead anyway.
        wheelPacer.add(lines, max(20, session.emulator?.mRows ?: 24))
        if (wheelLoop?.isActive != true) wheelLoop = scope.launch { pumpWheel() }
    }

    /** Drop wheel lines not sent yet. True if a scroll was still under way. */
    private fun stopWheel(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stopWheel() }
            return false
        }
        val active = wheelPacer.active(SystemClock.uptimeMillis())
        wheelPacer.clear()
        return active
    }

    private suspend fun pumpWheel() {
        while (true) {
            val now = SystemClock.uptimeMillis()
            val last = lastWheelCall
            val busy = last?.isActive == true
            val events = wheelPacer.next(now, busy)
            if (events != 0) {
                sendWheel(events)
                continue
            }
            if (busy) {
                last!!.join()
                continue
            }
            val wait = wheelPacer.waitMs(now)
            if (wait < 0) return
            delay(max(1L, wait))
        }
    }

    /**
     * Send [events] wheel events, negative to scroll up, as block.event calls without waiting for
     * each answer: they run in order, so they reach the program as quickly as they were sent,
     * where awaited writes would bunch them up behind each round trip.
     */
    private fun sendWheel(events: Int) {
        val payload = buildJsonObject {
            put("x", wheelColumn)
            put("y", wheelRow)
            put("delta_y", if (events < 0) -1 else 1)
            put("delta_mode", "line")
        }
        val call = eventPayload("wheel", payload)
        lastWheelDirection = events.sign
        val sentAt = SystemClock.uptimeMillis()
        val count = abs(events)
        repeat(count) { i ->
            // Undispatched, so each request is queued on the connection before the next one.
            lastWheelCall = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val answered = runCatching { host.call("block.event", call) }.isSuccess
                // The batch's last answer says how long the batch took there and back.
                if (answered && i == count - 1 && scrollLag.add(SystemClock.uptimeMillis() - sentAt)) _scrollLagged.value = true
            }
        }
    }

    /**
     * Answer the prompt on screen with one of its options, with the keys [ScreenPrompt.menuKeys]
     * gives for a menu. A yes/no answer is typed, then entered only if the program reads a whole
     * line (see [InputQueue]).
     *
     * Does nothing unless [prompt] is still the one on screen, as read from a live stream: a card
     * drawn from an older screen could otherwise answer a different question. Main thread.
     */
    fun choose(prompt: ScreenPrompt, option: PromptOption) {
        val current = _screenInfo.value.prompt
        if (!streamLive || phase != Phase.LIVE || current == null || !current.sameQuestion(prompt)) return
        val to = prompt.options.indexOf(option)
        val chosen = current.options.getOrNull(to) ?: return
        when (current.kind) {
            ScreenPrompt.Kind.YES_NO -> {
                stopWheel()
                input.offer(InputQueue.Item.Answer(chosen.answer ?: chosen.label.take(1).lowercase()))
            }
            ScreenPrompt.Kind.MENU -> current.menuKeys(to)?.forEach(::sendKey)
        }
    }

    /**
     * Moves one of Claude's questions to tab [index], from the tab on screen now, with the arrow keys
     * that page through its questions.
     */
    fun goToTab(prompt: ScreenPrompt, index: Int) {
        val current = _screenInfo.value.prompt
        if (!streamLive || phase != Phase.LIVE || current == null || !current.sameQuestion(prompt)) return
        val from = current.currentTab ?: return
        if (index !in current.tabs.indices) return
        repeat(abs(index - from)) { sendKey(if (index > from) RemoteKey.RIGHT else RemoteKey.LEFT) }
    }

    /**
     * Answers one of Claude's questions in the user's own words, through its "Type something" option:
     * Claude takes what is typed while that option is highlighted, in place of any text already in
     * it, and ticks it in a multi-select question. A single choice is then sent with Enter; a
     * multi-select one waits for Submit, like the other ticks.
     */
    fun answerOwn(prompt: ScreenPrompt, text: String) {
        val current = _screenInfo.value.prompt
        if (!streamLive || phase != Phase.LIVE || current == null || !current.sameQuestion(prompt)) return
        val own = current.options.indexOfFirst { it.role == PromptOption.Role.OWN }
        val from = current.options.indexOfFirst { it.selected }
        val line = text.replace(Regex("""\s*\n\s*"""), " ").trim()
        if (own < 0 || from < 0 || line.isEmpty()) return
        repeat(abs(own - from)) { sendKey(if (own > from) RemoteKey.DOWN else RemoteKey.UP) }
        current.options[own].typed?.let { old -> typeBytes(ByteArray(old.codePointCount(0, old.length)) { 0x7f }) }
        typeText(line)
        if (!current.multiSelect) sendKey(RemoteKey.ENTER)
    }

    /** The same question with the same options, whichever is highlighted or ticked now. */
    private fun ScreenPrompt.sameQuestion(other: ScreenPrompt) =
        kind == other.kind && question == other.question && options.map { it.label } == other.options.map { it.label } &&
            tabs.map { it.label } == other.tabs.map { it.label }

    // ---- Claude Code's "You should know" notes ----

    /** When the note on screen first showed: Claude ignores digits typed in the first moments its buttons show. */
    private var fyiSince = 0L
    private var fyiJob: Job? = null

    /** The read with what earlier reads saw of the same note's panel, which may have scrolled since. */
    private fun withFyiSoFar(read: ScreenInfo): ScreenInfo {
        val before = lastRead.fyi
        val fyi = read.fyi?.mergedWith(before) ?: return read
        if (before == null || !fyi.sameNote(before)) fyiSince = SystemClock.uptimeMillis()
        return read.copy(fyi = fyi)
    }

    /**
     * Press a button of the "You should know" note on screen by typing its digit into Claude's empty
     * input box, which is how Claude takes them. Claude only takes the digits of the buttons its
     * panel shows, so one scrolled out of sight is scrolled back first.
     *
     * Does nothing unless [note] is still on screen, read from a live stream, and the input box is
     * empty: with text in it, the digit would only add to the text. Main thread.
     */
    fun pressFyi(note: FyiNote, action: FyiAction) {
        val info = _screenInfo.value
        val current = info.fyi
        if (!streamLive || phase != Phase.LIVE || current == null || !current.sameNote(note) || action !in current.actions) return
        if (!info.claudeInput.isNullOrEmpty()) return
        val reading = fyiJob
        reading?.cancel()
        fyiJob = scope.launch {
            // A read cut short leaves the panel first, so its keys and Esc don't land among these.
            reading?.join()
            fun ready(s: ScreenInfo) = s.fyi?.let { it.sameNote(note) && it.shows(action) } == true && s.claudeInput.isNullOrEmpty()
            val now = _screenInfo.value
            val panel = now.fyi?.takeIf { it.sameNote(note) } ?: return@launch
            if (!panel.shows(action)) {
                if (!now.fullscreen && now.interruptible) return@launch
                scrollFyiToEnd(panel, now.fullscreen)
                awaitScreen(FYI_SCROLL_WAIT_MS, ::ready) ?: return@launch
            }
            val wait = fyiSince + FYI_ARM_MS - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            if (streamLive && phase == Phase.LIVE && ready(_screenInfo.value)) sendKey(RemoteKey.digit(action.key))
        }
    }

    /**
     * Scroll the "You should know" panel on screen through the rows the phone hasn't read, so it can
     * show the whole note, and leave it at its end, where the buttons are. In fullscreen that is the
     * wheel over the panel. The classic renderer takes no mouse, so there the panel is focused with
     * ctrl+x tab, paged through and left with Esc; only while Claude is idle, where an Esc that
     * missed the panel interrupts nothing. Main thread.
     */
    fun readFyi() {
        val info = _screenInfo.value
        val start = info.fyi ?: return
        if (!streamLive || phase != Phase.LIVE || start.complete || fyiJob?.isActive == true) return
        if (!info.fullscreen && info.interruptible) return
        fyiJob = scope.launch { if (info.fullscreen) readFyiByWheel(start) else readFyiByKeys(start) }
    }

    private suspend fun readFyiByWheel(start: FyiNote) {
        repeat(FYI_MAX_STEPS) {
            val note = _screenInfo.value.fyi?.takeIf { it.sameNote(start) } ?: return
            // The user's own scrolling wins.
            if (wheelPacer.active(SystemClock.uptimeMillis())) return
            val unseen = note.rows.indexOfFirst { it == null }
            if (unseen < 0) return scrollFyiToEnd(note, fullscreen = true)
            // Bring the first row not read yet into the window, a couple of rows below its top in
            // case Claude scrolls a line more or less than asked.
            val window = note.shown.last - note.shown.first + 1
            val lines = (unseen - FYI_WHEEL_MARGIN).coerceIn(0, maxOf(0, note.rows.size - window)) - note.shown.first
            if (lines == 0) return
            wheelFyi(lines, note.screenRow + 1)
            awaitScreen(FYI_SCROLL_WAIT_MS) { s -> s.fyi?.let { it.shown != note.shown || !it.sameNote(start) } != false } ?: return
        }
    }

    private suspend fun readFyiByKeys(start: FyiNote) {
        try {
            fyiAfter(start) { focusFyi() } ?: return
            var note = fyiAfter(start) { typeBytes(HOME_KEY) } ?: return
            repeat(FYI_MAX_STEPS) {
                if (note.complete) return
                val next = fyiAfter(start) { typeBytes(PAGE_DOWN_KEY) } ?: return
                if (next.shown == note.shown) return
                note = next
            }
        } finally {
            withContext(NonCancellable) { leaveFyi() }
        }
    }

    /**
     * Scroll the panel by [lines] over screen [row], one wheel event at a time, each answered and
     * then a pause before the next, so that Claude scrolls a line for each rather than speeding up.
     * Claude ignores the first event after a change of direction, so that gets one more.
     */
    private suspend fun wheelFyi(lines: Int, row: Int) {
        wheelColumn = FYI_WHEEL_COLUMN
        wheelRow = row
        val direction = lines.sign
        repeat(abs(lines) + if (direction != lastWheelDirection) 1 else 0) {
            sendWheel(direction)
            lastWheelCall?.join()
            delay(FYI_WHEEL_GAP_MS)
        }
    }

    /** Scroll the panel to its end, where the buttons are. */
    private suspend fun scrollFyiToEnd(note: FyiNote, fullscreen: Boolean) {
        val lines = note.rows.lastIndex - note.shown.last
        if (lines <= 0) return
        if (fullscreen) {
            // A little further than needed: the panel stops at its end.
            scrollWheel(lines + 2, FYI_WHEEL_COLUMN, note.screenRow + 1)
        } else {
            focusFyi()
            delay(FYI_KEY_GAP_MS)
            leaveFyi()
        }
    }

    /** Claude's keys for the panel: ctrl+x tab moves into it, at its first button. */
    private fun focusFyi() {
        sendKey(RemoteKey.ctrl('x'))
        sendKey(RemoteKey.TAB)
    }

    /** End shows the panel's end, then Esc leaves it, unless the panel has gone and Esc would reach Claude. */
    private suspend fun leaveFyi() {
        typeBytes(END_KEY)
        delay(FYI_KEY_GAP_MS)
        if (_screenInfo.value.fyi != null) sendKey(RemoteKey.ESCAPE)
    }

    /** Send something, then the panel once its window has moved, or as it is if it doesn't; null once it has gone. */
    private suspend fun fyiAfter(start: FyiNote, send: () -> Unit): FyiNote? {
        val before = _screenInfo.value.fyi?.shown
        send()
        val read = awaitScreen(FYI_SCROLL_WAIT_MS) { s -> s.fyi?.let { it.shown != before || !it.sameNote(start) } != false }
        return (read ?: _screenInfo.value).fyi?.takeIf { it.sameNote(start) }
    }

    private suspend fun awaitScreen(timeoutMs: Long, done: (ScreenInfo) -> Boolean): ScreenInfo? =
        withTimeoutOrNull(timeoutMs) { _screenInfo.first(done) }

    // ---- Claude Code's surveys ----

    /** When the survey on screen first showed: Claude ignores keys typed in its first moments, as it does a note's digits. */
    private var surveySince = 0L
    private var surveyJob: Job? = null

    /**
     * Answer the survey Claude Code shows over its input box by typing [answer]'s key into the
     * empty box, which is how Claude takes it. Does nothing unless [survey] is still on screen, read
     * from a live stream, and the input box is empty: with text in it, the key would only add to
     * the text. Main thread.
     */
    fun answerSurvey(survey: ClaudeSurvey, answer: SurveyAnswer) {
        val info = _screenInfo.value
        if (!streamLive || phase != Phase.LIVE || info.survey != survey || answer !in survey.answers) return
        if (!info.claudeInput.isNullOrEmpty()) return
        val reading = fyiJob
        reading?.cancel()
        surveyJob?.cancel()
        surveyJob = scope.launch {
            // A note's read cut short leaves its panel first, so its keys don't land among these.
            reading?.join()
            val wait = surveySince + FYI_ARM_MS - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            val now = _screenInfo.value
            if (!streamLive || phase != Phase.LIVE || now.survey != survey || !now.claudeInput.isNullOrEmpty()) return@launch
            sendKey(if (answer.key.isDigit()) RemoteKey.digit(answer.key.digitToInt()) else RemoteKey.letter(answer.key))
        }
    }

    // ---- Phone sizing ----

    /** Take the block over and size it to this screen, or hand it back. */
    fun setPhoneSized(on: Boolean) {
        if (_phoneSized.value == on) return
        _phoneSized.value = on
        inOrder {
            if (on) {
                runCatching { host.ownBlock(sessionId, blockId) }
                withContext(Dispatchers.Main) { requestPhoneSize() }
            } else {
                host.disownBlock(sessionId, blockId)
            }
        }
    }

    /** Takes and give-ups in order: leaving and coming straight back must not reach the server as a take, then a give-up. */
    private fun inOrder(change: suspend () -> Unit): Job {
        val before = ownershipJob
        return scope.launch(Dispatchers.Default) {
            before?.join()
            change()
        }.also { ownershipJob = it }
    }

    /** Main thread only, as the view's size changes there. */
    private fun requestPhoneSize(force: Boolean = false) {
        val size = lastViewSize ?: return
        phoneResizeJob?.cancel()
        phoneResizeJob = scope.launch(Dispatchers.Default) {
            delay(120)
            // The remote may still show this size while a request for another is on its way.
            if (!force && _remoteSize.value == size && requestedSize.let { it == null || it == size }) return@launch
            requestedSize = size
            runCatching {
                host.blockMethod(sessionId, blockId, "resize", buildJsonObject {
                    put("columns", size.columns)
                    put("rows", size.rows)
                })
            }
        }
    }

    companion object {
        /** Rows the emulator keeps, the screen's among them. */
        const val TRANSCRIPT_ROWS = 4000
        /** Lines of a redraw worth reading: what the emulator keeps, with room for the tallest screen. */
        const val KEPT_LINES = TRANSCRIPT_ROWS + 300
        /** The first part of a redraw, which holds a small one whole; of a large one only the opening modes are kept. */
        const val FORMAT_HEAD_BYTES = 32 * 1024
        /** How far back from its end a large redraw is read first when its width isn't known: about KEPT_LINES of Claude Code's lines. */
        const val FORMAT_TAIL_BYTES = 640L * 1024
        /**
         * The bytes a redraw's line takes beyond one per column, for its styles, when sizing how far
         * back to read: at 100 columns that comes to FORMAT_TAIL_BYTES.
         */
        const val LINE_OVERHEAD_BYTES = 48
        /** How long a call reading the redraw may take: a 192 KB part takes about 19 s at 10 KB/s. */
        const val READ_TIMEOUT_MS = 60_000L
        /** The longest wait for the stream to go quiet before a redraw. */
        const val QUIET_WAIT_MS = 500L
        /** How long a fresh stream's unreadable snapshot may take before the screen is read on the side and shown. */
        const val EARLY_SCREEN_AFTER_MS = 1_000L
        /** How long the stream stays quiet before its history is compressed. */
        const val COMPRESS_AFTER_QUIET_MS = 2_000L
        /**
         * How long output nobody sees waits to be fed to the terminal, so a chatty program in another
         * tab costs a few large writes a second rather than one a message. Showing it feeds it at once.
         */
        const val HIDDEN_FLUSH_MS = 300L
        /** Output nobody sees that is fed at once, whatever the wait. */
        const val HIDDEN_FLUSH_BYTES = 256 * 1024
        /** The first wait before asking again for a redraw that failed, doubling each time up to the most. */
        const val SYNC_RETRY_MS = 1_000L
        const val SYNC_RETRY_MAX_MS = 8_000L
        /** How many times a redraw is asked for before the screen goes live without one. */
        const val SYNC_ATTEMPTS = 5
        /** Refusals that may pass: a timeout, a conflict such as a session not yet attached, a client busy or reconnecting, too many requests. */
        val RETRYABLE_STATUSES = setOf(408, 409, 429)
        /**
         * One reply of a large redraw. The control connection's pong waits behind it, so it must cross
         * a slow link (10 KB/s) well within the 30 s the connection waits for a pong.
         */
        const val BLOB_PART_BYTES = 192 * 1024
        /** A live stream quiet this long when its host's connection drops is reconnected. */
        const val QUIET_STREAM_MS = 15_000L

        /** How long a synchronized update may hold the screen before it is drawn anyway. */
        const val SYNC_UPDATE_TIMEOUT_MS = 250L

        /**
         * Claude Code ignores digits typed within 600 ms of a note's buttons showing, or keys within
         * 600 ms of a survey's, and leaves them in the input box.
         */
        private const val FYI_ARM_MS = 650L
        /** How long to wait for the panel to redraw after scrolling it. */
        private const val FYI_SCROLL_WAIT_MS = 1500L
        /** Between keys for the panel, so Claude has moved into it before the next arrives. */
        private const val FYI_KEY_GAP_MS = 200L
        private const val FYI_MAX_STEPS = 8
        /** Where the wheel goes over the panel: in its text, clear of the "[-]" at the right. */
        private const val FYI_WHEEL_COLUMN = 4
        /** Rows short of where the wheel should bring the first row not read yet, in case it goes further. */
        private const val FYI_WHEEL_MARGIN = 2
        /** Claude scrolls a line per wheel event when they come more than 40 ms apart. */
        private const val FYI_WHEEL_GAP_MS = 45L
        private val HOME_KEY = "\u001b[H".toByteArray()
        private val END_KEY = "\u001b[F".toByteArray()
        private val PAGE_DOWN_KEY = "\u001b[6~".toByteArray()

        /** The length of the longest prefix that doesn't end partway through a UTF-8 character. */
        fun completeUtf8Length(b: ByteArray): Int {
            var i = b.size
            var back = 0
            while (i > 0 && back < 4) {
                val c = b[i - 1].toInt() and 0xff
                if (c and 0xc0 != 0x80) {
                    val need = when {
                        c < 0x80 -> 1
                        c and 0xe0 == 0xc0 -> 2
                        c and 0xf0 == 0xe0 -> 3
                        c and 0xf8 == 0xf0 -> 4
                        else -> 1
                    }
                    return if (back + 1 >= need) b.size else i - 1
                }
                i--
                back++
            }
            return b.size
        }

        /**
         * The app theme's terminal colors, shown in place of the ones the Mac sends; null for the Mac's.
         * Main thread only: set it with [refreshColors] for each terminal.
         */
        var themeColors: IntArray? = null

        /** Parse a `theme` message into the emulator's 259 colors, starting from the defaults. */
        fun parseTheme(msg: JsonObject): IntArray {
            val colors = DEFAULT_COLORS.copyOf()
            fun rgb(s: String?): Int? {
                val hex = s?.removePrefix("#") ?: return null
                return hex.toIntOrNull(16)?.let { 0xff000000.toInt() or it }
            }
            rgb(msg.str("foreground"))?.let { colors[TextStyle.COLOR_INDEX_FOREGROUND] = it }
            rgb(msg.str("background"))?.let { colors[TextStyle.COLOR_INDEX_BACKGROUND] = it }
            rgb(msg.str("cursor"))?.let { colors[TextStyle.COLOR_INDEX_CURSOR] = it }
            (msg["palette"] as? JsonArray)?.forEach { e ->
                val entry = (e as? JsonPrimitive)?.content ?: return@forEach
                val idx = entry.substringBefore('=').toIntOrNull() ?: return@forEach
                val c = rgb(entry.substringAfter('=')) ?: return@forEach
                if (idx in 0..255) colors[idx] = c
            }
            return colors
        }

        /** The emulator's built-in colors, with a background that matches the app. */
        val DEFAULT_COLORS: IntArray = com.jeffarese.rexmobile.terminal.emulator.TerminalColorScheme().mDefaultColors.copyOf().also {
            it[TextStyle.COLOR_INDEX_BACKGROUND] = 0xff0e0e12.toInt()
            it[TextStyle.COLOR_INDEX_FOREGROUND] = 0xffe6e6ef.toInt()
            it[TextStyle.COLOR_INDEX_CURSOR] = 0xffff5a8a.toInt()
        }
    }
}

/** The cursor's row and column, and the text of its row. */
internal data class CursorLine(val row: Int, val column: Int, val text: String)

/**
 * A block's input, sent one item at a time in the order it was given.
 *
 * Keys and pastes go as block.event calls, which the server runs one at a time in the order they
 * arrive on a connection, so a key goes out without waiting for the answer to the one before it: a
 * menu choice of a few arrow keys and Enter takes about one round trip rather than two per key.
 * Typed text goes by terminal.write, which the server may run alongside them, so it waits until
 * every event sent before it has been answered.
 */
internal class InputQueue(private val link: Link) {
    interface Link {
        /** Sends a block.event and returns once the request is on the connection; the job ends with its answer. */
        suspend fun event(type: String, payload: JsonObject): Job

        /** Types text with terminal.write, returning once that is answered. */
        suspend fun write(text: String)

        /** Waits for the ordered calls sent outside the queue, such as wheel events, to be answered. */
        suspend fun awaitOthers()

        /** Whether the terminal hands input to the program a line at a time; null if the server can't tell. */
        suspend fun canonical(): Boolean?

        /** Where the cursor is, or null if the screen isn't known. */
        suspend fun cursorLine(): CursorLine?

        /** Whether the program in front passes input on to another terminal, as ssh and tmux do; null if unknown. */
        suspend fun relaysInput(): Boolean?
    }

    sealed interface Item {
        class Bytes(val data: ByteArray) : Item
        class Paste(val text: String) : Item
        class Key(val key: RemoteKey) : Item
        /** A yes/no question's answer, such as "y", entered only if the program reads a whole line. */
        class Answer(val text: String) : Item
    }

    private val items = Channel<Item>(Channel.UNLIMITED)
    /** The last event sent; once it is answered, so are the ones before it. */
    private var lastEvent: Job? = null

    fun offer(item: Item) {
        items.trySend(item)
    }

    /** Sends the items as they come, until cancelled. */
    suspend fun run() {
        val leftover = ByteArrayOutputStream()
        for (first in items) {
            var next: Item? = first
            // Coalesce typed bytes that are already waiting, sending them before any item behind them.
            while (next != null) {
                when (val item: Item = next) {
                    is Item.Bytes -> leftover.write(item.data)
                    is Item.Paste -> {
                        sendText(leftover)
                        event("paste", buildJsonObject { put("text", item.text) })
                        // Let the program finish taking the paste before keys that follow it, such as Enter.
                        lastEvent?.join()
                        delay(60)
                    }
                    is Item.Key -> {
                        sendText(leftover)
                        key(item.key)
                    }
                    is Item.Answer -> {
                        sendText(leftover)
                        answer(item.text)
                    }
                }
                next = items.tryReceive().getOrNull()
            }
            sendText(leftover)
        }
    }

    /**
     * Press, then release without waiting for either answer. Claude Code's keyboard mode doesn't
     * ask for releases, so the server drops them; a program that asks gets them in order.
     */
    private suspend fun key(key: RemoteKey) {
        event("key", keyPayload(key, "press"))
        event("key", keyPayload(key, "release"))
    }

    private suspend fun event(type: String, payload: JsonObject) {
        runCatching { link.event(type, payload) }.onSuccess { lastEvent = it }
    }

    /** Waits until every event sent so far, here or elsewhere, has been answered. */
    private suspend fun awaitEvents() {
        link.awaitOthers()
        lastEvent?.join()
    }

    private suspend fun sendText(buf: ByteArrayOutputStream) {
        if (buf.size() == 0) return
        val bytes = buf.toByteArray()
        val complete = RemoteTerminal.completeUtf8Length(bytes)
        buf.reset()
        if (complete < bytes.size) buf.write(bytes, complete, bytes.size - complete)
        if (complete == 0) return
        write(String(bytes, 0, complete, Charsets.UTF_8))
    }

    private suspend fun write(text: String) {
        awaitEvents()
        runCatching { link.write(text) }
    }

    /**
     * Types a yes/no answer, then Enter only if the program reads a whole line. A program that reads
     * a single key, as zsh's `read -q` or bash's `read -n 1`, takes the answer alone, and an Enter
     * after it would answer whatever reads next, such as the next question.
     *
     * The terminal's mode tells them apart: line by line (canonical) or key by key. It is read
     * before the answer, while the program waits on it, because once a single-key read has its
     * answer the shell puts the terminal back in line mode for the commands after it. The mode after
     * the answer must agree. When the server can't tell, as on Windows, the screen decides.
     */
    private suspend fun answer(text: String) {
        awaitEvents()
        val before = mode()
        // Behind ssh, tmux and the like the question is read on the far side, so the terminal here
        // is raw whatever that side does: only the screen can tell.
        val relayed = before == false && runCatching { link.relaysInput() }.getOrNull() == true
        val lineBefore = if (before == null || relayed) cursorLine() else null
        write(text)
        val after = if (before == true) mode() else null
        val enter = (if (relayed) null else AnswerEnter.fromMode(before, after)) ?: run {
            delay(ECHO_WAIT_MS)
            AnswerEnter.fromScreen(lineBefore, cursorLine(), text)
        }
        if (enter) key(RemoteKey.ENTER)
    }

    private suspend fun mode() = runCatching { link.canonical() }.getOrNull()

    private suspend fun cursorLine() = runCatching { link.cursorLine() }.getOrNull()

    companion object {
        /** How long a program has to echo an answer, when the screen must tell whether it waits for Enter. */
        const val ECHO_WAIT_MS = 250L

        fun keyPayload(key: RemoteKey, action: String) = buildJsonObject {
            put("key", key.key)
            put("mods", key.mods)
            put("consumed_mods", "")
            put("text", key.text)
            put("unshifted_codepoint", key.codepoint)
            put("composing", false)
            put("action", action)
        }
    }
}

/** Programs that pass what is typed on to another terminal, where the question is really read. */
private val RELAYS = setOf(
    "ssh", "mosh-client", "tmux", "screen", "zellij", "et", "telnet",
    "docker", "podman", "kubectl", "lxc", "incus", "multipass", "vagrant",
)

/** Whether a yes/no answer that was just typed still needs Enter. See [InputQueue]. */
internal object AnswerEnter {
    /**
     * From whether the terminal read input a line at a time before the answer and, for a line read,
     * after it, each null if the server couldn't tell: only a line read needs Enter. Null when the
     * mode before is unknown, since the mode after alone can't tell a line read from a single-key
     * read whose shell has gone back to line mode.
     */
    fun fromMode(before: Boolean?, after: Boolean?): Boolean? = when (before) {
        null -> null
        false -> false
        true -> after != false
    }

    /**
     * From the screen before and after the answer: a line read echoes it and keeps the cursor just
     * after it on the question's row, where a single-key read takes it and moves on, or doesn't
     * echo it. Bash's `read -n 1` echoes it and waits for the script to go on, so it looks like a
     * line read for a while; only the terminal's mode tells those apart.
     */
    fun fromScreen(before: CursorLine?, after: CursorLine?, answer: String): Boolean {
        if (before == null || after == null) return false
        return after.row == before.row && after.column == before.column + answer.length &&
            after.text.endsWith(answer) && after.text.removeSuffix(answer).trimEnd() == before.text.trimEnd()
    }
}

/** Forwards emulator callbacks to the attached view. */
private class SessionClient(private val view: () -> TerminalView?) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        view()?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        val v = view() ?: return
        val cm = v.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text ?: ""))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val v = view() ?: return
        val cm = v.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(v.context)?.toString() ?: return
        session?.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
