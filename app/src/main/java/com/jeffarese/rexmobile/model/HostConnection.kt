package com.jeffarese.rexmobile.model

import android.os.SystemClock
import android.util.Log
import com.jeffarese.rexmobile.rex.AttachResult
import com.jeffarese.rexmobile.rex.BootstrapResult
import com.jeffarese.rexmobile.rex.ControlConnection
import com.jeffarese.rexmobile.rex.ControlListener
import com.jeffarese.rexmobile.rex.ProcessRef
import com.jeffarese.rexmobile.rex.ProcessResult
import com.jeffarese.rexmobile.rex.ProgramStatus
import com.jeffarese.rexmobile.rex.ProgramStatusList
import com.jeffarese.rexmobile.rex.PwdResult
import com.jeffarese.rexmobile.rex.RemoteHostList
import com.jeffarese.rexmobile.rex.RexCallException
import com.jeffarese.rexmobile.rex.RexJson
import com.jeffarese.rexmobile.rex.SHELL_FLAVOR
import com.jeffarese.rexmobile.rex.SessionList
import com.jeffarese.rexmobile.rex.SessionSummary
import com.jeffarese.rexmobile.rex.SessionView
import com.jeffarese.rexmobile.rex.TERMINAL_CREATOR
import com.jeffarese.rexmobile.rex.TERMINAL_SUBPROTOCOL
import com.jeffarese.rexmobile.rex.TicketResult
import com.jeffarese.rexmobile.rex.TitleResult
import com.jeffarese.rexmobile.rex.Welcome
import com.jeffarese.rexmobile.rex.long
import com.jeffarese.rexmobile.rex.obj
import com.jeffarese.rexmobile.rex.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

enum class ConnStatus { IDLE, CONNECTING, ONLINE, OFFLINE }

/** What the app knows about one terminal block beyond the session view. */
data class BlockMeta(
    val sessionId: String,
    val title: String = "",
    val pwd: String? = null,
    val process: String? = null,
    val argv0: String? = null,
    val statuses: List<ProgramStatus> = emptyList(),
    val exited: Boolean = false,
    /** Something happened that the user hasn't looked at: a bell, a notification, a finished or blocked agent. */
    val unseen: Boolean = false,
    val notification: String? = null,
) {
    /** The status that matters most: blocked, then error, working, done. */
    val status: ProgramStatus?
        get() = statuses.maxByOrNull { statusRank(it.state) }?.takeIf { statusRank(it.state) > 0 }

    /** The task title an agent reported, if any. */
    val taskTitle: String? get() = statuses.firstNotNullOfOrNull { it.title?.takeIf(String::isNotBlank) }

    val app: String? get() = statuses.firstNotNullOfOrNull { it.effectiveApp ?: it.app }
}

fun statusRank(state: String) = when (state) {
    "blocked" -> 4
    "error" -> 3
    "working" -> 2
    "done" -> 1
    else -> 0
}

data class HostState(
    val status: ConnStatus = ConnStatus.IDLE,
    val error: String? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val views: Map<String, SessionView> = emptyMap(),
    val blocks: Map<String, BlockMeta> = emptyMap(),
    val clientId: String? = null,
)

/** How a check that a host's connection is alive went. */
enum class Liveness {
    /** The connection answered. */
    ANSWERED,
    /** None was open, so a new one is on its way. */
    NOT_OPEN,
    /** No answer yet, but the connection is still receiving, or owes other answers that the check's waits behind. */
    BUSY,
    /** Silent, so it was given up and a new one is on its way. */
    SILENT,
}

/** Something worth telling the user about right away. */
data class Alert(
    val hostId: String,
    val sessionId: String,
    val blockId: String,
    val kind: Kind,
    val title: String,
    val body: String?,
) {
    enum class Kind { BLOCKED, DONE, ERROR, BELL, NOTIFICATION }
}

/**
 * One Rex server: the control connection, every session on it, and what each block is doing.
 * State changes happen on one thread at a time, so events apply in order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostConnection(
    val config: HostConfig,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    /** The host answered on another of [ControlConnection.urlsToTry] than its own URL, which should be kept. */
    private val onMoved: (String) -> Unit = {},
    /** Which finished and failed agents the person has seen, so one that ended while away still shows as new. */
    private val reviewed: Reviewed = Reviewed(),
    /** Whether the app is away, where a host that can't be reached is tried less and less often: see [retryWait]. */
    private val away: () -> Boolean = { false },
    /**
     * Whether the phone has a network at all. Without one no attempt is made: the owner checks the
     * host with [ensureAlive] once one is back.
     */
    private val hasNetwork: () -> Boolean = { true },
    private val onAlert: (Alert) -> Unit,
) {
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val _state = MutableStateFlow(HostState())
    val state: StateFlow<HostState> = _state

    private val _dataCompressed = MutableStateFlow<Boolean?>(null)
    /**
     * Whether the server compresses terminal streams, as Rex servers from build 1531 do, once a stream
     * has opened; null until then. A compressed stream is cheap enough to open before it is needed.
     */
    val dataCompressed: StateFlow<Boolean?> = _dataCompressed

    fun noteDataCompression(compressed: Boolean) {
        _dataCompressed.value = compressed
    }

    private val _drops = MutableStateFlow(0)
    /** How many times the control connection was lost while wanted, such as when the network changed. */
    val drops: StateFlow<Int> = _drops

    private var conn: ControlConnection? = null
    private var reconnectToken: String? = null
    private var reconnectJob: Job? = null
    /** Tried in turn until one answers, then kept. Serial only. */
    private val urls = ControlConnection.urlsToTry(config.url)
    private var urlIndex = 0
    private var answered = false
    private var attempts = 0
    private var wanted = false
    private val viewFetches = mutableMapOf<String, Job>()
    /** The newest revision announced for each session's view. */
    private val viewWanted = mutableMapOf<String, Long>()

    /** How many events have changed each part of a block's meta. */
    private data class Changes(val title: Int = 0, val pwd: Int = 0, val process: Int = 0, val statuses: Int = 0)
    /** Per block, so a read that began before an event doesn't undo it. Serial only. */
    private val changes = HashMap<String, Changes>()
    /** Blocks whose statuses have been read or reported, so a read after a reconnect can tell what changed. Serial only. */
    private val statusesKnown = HashSet<String>()
    /** Blocks read at once, so re-reading every one after a reconnect leaves room on a slow link for the pane on screen. */
    private val metaReads = Semaphore(4)

    val id get() = config.id

    fun connect() {
        wanted = true
        scope.launch(serial) { open() }
    }

    fun disconnect() {
        wanted = false
        scope.launch(serial) {
            reconnectJob?.cancel()
            conn?.close()
            conn = null
            reconnectToken = null
            _state.update { it.copy(status = ConnStatus.IDLE) }
        }
    }

    /**
     * Check the connection is alive, reconnecting at once if it isn't, with the wait between attempts
     * back to its shortest. [answered] hears how the check went and how long it took.
     *
     * On a slow link the answer can wait behind a large reply still on its way, such as part of a
     * redraw, so a connection that is still receiving, or still owes answers, is kept: its pings
     * find a dead one within a minute. When the user [force]s it, as with Reconnect, one that
     * doesn't answer is replaced: pending calls are also what a dead connection leaves behind.
     */
    fun ensureAlive(force: Boolean = false, answered: ((Liveness, Long) -> Unit)? = null) {
        if (!wanted) return
        scope.launch(serial) {
            val c = conn
            if (c == null || !c.isOpen) {
                reconnectJob?.cancel()
                attempts = 0
                open()
                answered?.invoke(Liveness.NOT_OPEN, 0)
                return@launch
            }
            val started = SystemClock.elapsedRealtime()
            val heard = c.messagesReceived
            // An error is an answer too.
            val ok = runCatching { c.call("server.version", timeoutMs = 4000) }.let { it.isSuccess || it.exceptionOrNull() is RexCallException }
            val result = when {
                ok -> Liveness.ANSWERED
                !force && c.isOpen && (c.messagesReceived != heard || c.callsPending > 0) -> Liveness.BUSY
                else -> Liveness.SILENT
            }
            answered?.invoke(result, SystemClock.elapsedRealtime() - started)
            if (result == Liveness.SILENT && conn === c) {
                c.abandon()
            }
        }
    }

    private fun open() {
        if (!wanted) return
        if (conn?.let { !it.isOpen && _state.value.status == ConnStatus.CONNECTING } == true) return
        if (conn?.isOpen == true) return
        if (!hasNetwork()) {
            _state.update { it.copy(status = ConnStatus.OFFLINE, error = "No network") }
            return
        }
        _state.update { it.copy(status = ConnStatus.CONNECTING) }
        lateinit var c: ControlConnection
        c = ControlConnection(client, urls[urlIndex], object : ControlListener {
            override fun onWelcome(welcome: Welcome) {
                scope.launch(serial) { if (conn === c) onWelcome(c, welcome) }
            }

            override fun onEvent(seq: Long, event: JsonObject) {
                scope.launch(serial) {
                    if (conn !== c) return@launch
                    // An event of a shape this app doesn't expect is skipped, rather than ending the app.
                    try {
                        handleEvent(c, event)
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        Log.w(TAG, "skipped an event of type ${event["type"]}", e)
                    }
                }
            }

            override fun onClosed(code: Int, reason: String) {
                scope.launch(serial) { if (conn === c) onClosed(reason) }
            }
        }, reconnectToken)
        conn = c
        scope.launch(serial) {
            delay(12_000)
            if (conn === c && c.welcome == null) c.abandon()
        }
    }

    private suspend fun onWelcome(c: ControlConnection, welcome: Welcome) {
        reconnectToken = welcome.reconnectToken
        if (!answered && urlIndex != 0) onMoved(urls[urlIndex])
        answered = true
        try {
            val clientId = welcome.clientId
            // A resumed client is still attached, but events sent while away are lost: read again.
            val list: SessionList = c.callAs("session.list")
            val ids = list.sessions.orEmpty().map { it.sessionId }
            val boot: BootstrapResult = c.callAs("session.bootstrap", buildJsonObject {
                // Every session is attached, here and as it is created, so "session" still brings their
                // blocks' events, without the pair of client events each `rex` command sends.
                put("event_scope", "session")
                putJsonArray("attach_session_ids") { ids.forEach { add(JsonPrimitive(it)) } }
            })
            val attached = boot.attachedSessions.orEmpty()
            // Only now: a server that welcomes but fails what follows is tried less and less often.
            attempts = 0
            _state.update { s ->
                s.copy(
                    status = ConnStatus.ONLINE,
                    error = null,
                    clientId = boot.clientId.ifBlank { clientId },
                    sessions = attached.map { it.session },
                    views = attached.associate { it.session.sessionId to it.view },
                    blocks = s.blocks.filterValues { m -> attached.any { it.session.sessionId == m.sessionId } },
                )
            }
            // Blocks that closed while away were never reported closed.
            val present = attached.flatMap { a -> a.view.windows.orEmpty().flatMap { w -> w.blocks.map { it.blockId } } }.toSet()
            changes.keys.retainAll(present)
            statusesKnown.retainAll(present)
            reviewed.retain(present)
            // Every block again, not only new ones: what changed while away, such as an agent now
            // blocked, came as events that are lost.
            attached.forEach { refreshMetaFor(c, it.view, all = true) }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (conn === c) {
                _state.update { it.copy(error = e.message) }
                c.abandon()
            }
        }
    }

    private fun onClosed(reason: String) {
        // Never answered there, so the next attempt tries the next address.
        if (!answered) urlIndex = (urlIndex + 1) % urls.size
        conn = null
        if (wanted) _drops.update { it + 1 }
        _state.update { it.copy(status = if (wanted) ConnStatus.OFFLINE else ConnStatus.IDLE, error = reason.takeIf { r -> r.isNotBlank() && wanted }) }
        if (!wanted) return
        val wait = retryWait(attempts, away())
        attempts++
        reconnectJob?.cancel()
        reconnectJob = scope.launch(serial) {
            delay(wait)
            open()
        }
    }

    private suspend fun handleEvent(c: ControlConnection, ev: JsonObject) {
        val type = ev.str("type")
        val p = ev.obj("payload") ?: JsonObject(emptyMap())
        when (type) {
            "session_created" -> {
                val s = p.obj("session")?.let { RexJson.decodeFromJsonElement<SessionSummary>(it) } ?: return
                _state.update { st -> if (st.sessions.any { it.sessionId == s.sessionId }) st else st.copy(sessions = st.sessions + s) }
                scope.launch(serial) { attach(c, s.sessionId) }
            }
            "session_destroyed" -> {
                val sid = p.str("session_id") ?: return
                val gone = _state.value.blocks.filterValues { it.sessionId == sid }.keys
                changes -= gone
                statusesKnown -= gone
                reviewed.forget(gone)
                _state.update { st ->
                    st.copy(
                        sessions = st.sessions.filterNot { it.sessionId == sid },
                        views = st.views - sid,
                        blocks = st.blocks.filterValues { it.sessionId != sid },
                    )
                }
            }
            "session_moved" -> scope.launch(serial) {
                val list: SessionList = runCatching { c.callAs<SessionList>("session.list") }.getOrNull() ?: return@launch
                _state.update { st -> st.copy(sessions = list.sessions.orEmpty()) }
            }
            "session_event" -> {
                val sid = p.str("session_id") ?: return
                val inner = p.obj("event") ?: return
                handleSessionEvent(c, sid, inner.str("type"), inner.obj("payload") ?: JsonObject(emptyMap()))
            }
        }
    }

    private fun handleSessionEvent(c: ControlConnection, sid: String, type: String?, p: JsonObject) {
        when (type) {
            "session_view_changed" -> {
                val rev = p.long("revision") ?: return
                val held = _state.value.views[sid]?.revision ?: -1
                if (rev > held) fetchView(c, sid, rev)
            }
            "session_label_changed" -> {
                val label = p.str("label") ?: return
                _state.update { st -> st.copy(sessions = st.sessions.map { if (it.sessionId == sid) it.copy(label = label) else it }) }
            }
            "block_closed" -> {
                val bid = p.str("block_id") ?: return
                _state.update { st -> st.copy(blocks = st.blocks - bid) }
                changes -= bid
                statusesKnown -= bid
                reviewed.forget(listOf(bid))
            }
            "block_event" -> handleBlockEvent(sid, p)
        }
    }

    private fun handleBlockEvent(sid: String, p: JsonObject) {
        val bid = p.str("block_id") ?: return
        if (p.str("creator_name") != TERMINAL_CREATOR) return
        val data = p.obj("payload") ?: JsonObject(emptyMap())
        when (p.str("name")) {
            "title_changed" -> {
                changed(bid) { it.copy(title = it.title + 1) }
                patch(sid, bid) { it.copy(title = data.str("title").orEmpty()) }
            }
            "pwd_changed" -> {
                changed(bid) { it.copy(pwd = it.pwd + 1) }
                patch(sid, bid) { it.copy(pwd = data.str("pwd")) }
            }
            "process_changed" -> {
                val fg = data.obj("foreground")
                changed(bid) { it.copy(process = it.process + 1) }
                patch(sid, bid) { it.copy(process = fg?.str("name"), argv0 = fg?.str("argv0"), exited = false) }
            }
            "child_exited" -> patch(sid, bid) { it.copy(exited = true) }
            "program_status_changed" -> {
                val rec = data.obj("record")?.let { runCatching { RexJson.decodeFromJsonElement<ProgramStatus>(it) }.getOrNull() } ?: return
                val before = _state.value.blocks[bid]
                val old = before?.statuses?.firstOrNull { it.id == rec.id }
                changed(bid) { it.copy(statuses = it.statuses + 1) }
                statusesKnown += bid
                patch(sid, bid) { m -> m.copy(statuses = (m.statuses.filterNot { it.id == rec.id } + rec).sortedBy { it.id.orEmpty() }) }
                alertIfChanged(sid, bid, old, rec)
            }
            "program_status_removed" -> {
                val ids = (data["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                changed(bid) { it.copy(statuses = it.statuses + 1) }
                patch(sid, bid) { m ->
                    m.copy(statuses = m.statuses.filterNot { r -> ids.contains(r.id.orEmpty()) || (r.id == null && ids.contains("")) })
                }
            }
            "bell" -> {
                patch(sid, bid) { it.copy(unseen = true) }
                val meta = _state.value.blocks[bid]
                onAlert(Alert(id, sid, bid, Alert.Kind.BELL, alertTitle(meta?.taskTitle, meta?.title), null))
            }
            "desktop_notification" -> {
                val title = data.str("title").orEmpty()
                val body = data.str("body")
                patch(sid, bid) { it.copy(unseen = true, notification = listOfNotNull(title.ifBlank { null }, body).joinToString(" · ")) }
                onAlert(Alert(id, sid, bid, Alert.Kind.NOTIFICATION, title.ifBlank { "Notification" }, body))
            }
        }
    }

    /**
     * Tell the user an agent became blocked, finished or failed, and mark its tab unseen. Only a whole
     * agent's change matters, not every subtask's.
     */
    private fun alertIfChanged(sid: String, bid: String, old: ProgramStatus?, rec: ProgramStatus) {
        if (old?.state == rec.state) return
        val kind = when (rec.state) {
            "blocked" -> Alert.Kind.BLOCKED
            "done" -> Alert.Kind.DONE
            "error" -> Alert.Kind.ERROR
            else -> return
        }
        val meta = _state.value.blocks[bid]
        if (!isAgentRecord(rec, meta?.statuses.orEmpty())) return
        patch(sid, bid) { it.copy(unseen = true) }
        onAlert(Alert(id, sid, bid, kind, alertTitle(rec.title, meta?.taskTitle, meta?.title), rec.msg))
    }

    /** The first of [titles] that says something, as a program can set an empty one. */
    private fun alertTitle(vararg titles: String?) = titles.firstOrNull { !it.isNullOrBlank() } ?: "Terminal"

    private fun patch(sid: String, bid: String, fn: (BlockMeta) -> BlockMeta) {
        _state.update { st -> st.copy(blocks = st.blocks + (bid to fn(st.blocks[bid] ?: BlockMeta(sid)))) }
    }

    private fun changed(bid: String, fn: (Changes) -> Changes) {
        changes[bid] = fn(changes[bid] ?: Changes())
    }

    /**
     * Read a session's view again until it is at [revision] or later, as a change announced while a
     * read is on its way may have come too late for it.
     */
    private fun fetchView(c: ControlConnection, sid: String, revision: Long) {
        viewWanted[sid] = maxOf(viewWanted[sid] ?: -1, revision)
        if (viewFetches[sid]?.isActive == true) return
        viewFetches[sid] = scope.launch(serial) {
            var failures = 0
            while (conn === c && failures < 3) {
                // Coalesce bursts of changes, and wait longer after a failure.
                delay(60L shl (2 * failures))
                val view = runCatching {
                    c.callAs<SessionView>("session.view", buildJsonObject { put("session_id", sid) })
                }.getOrNull()
                if (view == null) {
                    failures++
                    continue
                }
                _state.update { st ->
                    if ((st.views[sid]?.revision ?: -1) >= view.revision) st
                    else st.copy(views = st.views + (sid to view))
                }
                refreshMetaFor(c, view)
                if (view.revision >= (viewWanted[sid] ?: -1)) break
            }
        }
    }

    /**
     * Attach to a session created after the connection began. Its blocks' events, agents' statuses
     * among them, only come to a connection attached to it, so a failed attach is tried twice more
     * before settling for the session's view.
     */
    private suspend fun attach(c: ControlConnection, sid: String) {
        var attached: SessionView? = null
        for (wait in longArrayOf(0, 1_000, 3_000)) {
            delay(wait)
            if (conn !== c) return
            attached = runCatching {
                c.callAs<AttachResult>("session.attach", buildJsonObject { put("session_id", sid) }).view
            }.getOrNull()
            if (attached != null) break
        }
        val view: SessionView = attached ?: runCatching {
            c.callAs<SessionView>("session.view", buildJsonObject { put("session_id", sid) })
        }.getOrNull() ?: return
        _state.update { st -> st.copy(views = st.views + (sid to view)) }
        refreshMetaFor(c, view)
    }

    /**
     * Read title, directory, process and status for the view's blocks the app hasn't seen yet, or
     * with [all] for every one, replacing what was known.
     */
    private fun refreshMetaFor(c: ControlConnection, view: SessionView, all: Boolean = false) {
        val sid = view.sessionId ?: return
        val known = _state.value.blocks
        val bids = view.windows.orEmpty().flatMap { it.terminalBlocks }
            .map { it.blockId }
            .filter { all || it !in known }
        if (bids.isEmpty()) return
        bids.forEach { bid -> if (bid !in known) patch(sid, bid) { it } }
        val before = bids.associateWith { changes[it] ?: Changes() }
        scope.launch {
            bids.map { bid -> async { metaReads.withPermit { bid to readMeta(c, sid, bid) } } }.awaitAll().forEach { (bid, read) ->
                // What a connection since replaced read may be older than what the next one has.
                withContext(serial) { if (conn === c) applyMeta(sid, bid, read, before.getValue(bid)) }
            }
        }
    }

    /** A block's meta as read, each part failed or not. */
    private class MetaRead(
        val title: Result<String>,
        val pwd: Result<String?>,
        val process: Result<ProcessRef?>,
        val statuses: Result<List<ProgramStatus>>,
    )

    private suspend fun readMeta(c: ControlConnection, sid: String, bid: String): MetaRead = coroutineScope {
        val title = async { runCatching { blockCall<TitleResult>(c, sid, bid, "title").title } }
        val pwd = async { runCatching { blockCall<PwdResult>(c, sid, bid, "pwd").pwd } }
        val proc = async { runCatching { blockCall<ProcessResult>(c, sid, bid, "process").foreground } }
        val status = async { runCatching { blockCall<ProgramStatusList>(c, sid, bid, "program_status").records } }
        MetaRead(title.await(), pwd.await(), proc.await(), status.await())
    }

    /**
     * Put what was read in place of what was known, except where the read failed or an event has
     * come since it began, which is newer. An agent whose state changed meanwhile, such as one now
     * blocked, alerts as the event that was missed would have.
     */
    private fun applyMeta(sid: String, bid: String, read: MetaRead, before: Changes) {
        val m = _state.value.blocks[bid] ?: return // closed meanwhile
        val now = changes[bid] ?: Changes()
        var next = m
        if (now.title == before.title) read.title.onSuccess { next = next.copy(title = it) }
        if (now.pwd == before.pwd) read.pwd.onSuccess { next = next.copy(pwd = it) }
        if (now.process == before.process) read.process.onSuccess { next = next.copy(process = it?.name, argv0 = it?.argv0) }
        val statuses = read.statuses.getOrNull()?.takeIf { now.statuses == before.statuses }?.sortedBy { it.id.orEmpty() }
        if (statuses != null) next = next.copy(statuses = statuses)
        if (next != m) patch(sid, bid) { next }
        if (statuses != null) {
            if (bid in statusesKnown) statuses.forEach { rec -> alertIfChanged(sid, bid, m.statuses.firstOrNull { it.id == rec.id }, rec) }
            // The first read since the app started: an agent that finished or failed since the person last
            // looked is new to them, though not news to alert about.
            else if (reviewed.hasNew(bid, statuses)) patch(sid, bid) { it.copy(unseen = true) }
            statusesKnown += bid
        }
    }

    private suspend inline fun <reified T> blockCall(c: ControlConnection, sid: String, bid: String, method: String, args: JsonObject? = null): T =
        RexJson.decodeFromJsonElement(c.call("$TERMINAL_CREATOR.$method", blockPayload(sid, bid, args)))

    private fun blockPayload(sid: String, bid: String, args: JsonObject?) = buildJsonObject {
        put("session_id", sid)
        put("block_id", bid)
        if (args != null) put("args", args)
    }

    // ---- Calls the UI makes ----

    /** Wait until the connection is up, then return it. */
    suspend fun ready(timeoutMs: Long = 15_000): ControlConnection = withTimeout(timeoutMs) {
        _state.first { it.status == ConnStatus.ONLINE }
        conn?.takeIf { it.isOpen } ?: throw IllegalStateException("not connected")
    }

    suspend fun call(method: String, payload: JsonObject? = null, timeoutMs: Long = 15_000) =
        ready().call(method, payload, timeoutMs)

    suspend fun blockMethod(sid: String, bid: String, method: String, args: JsonObject? = null, timeoutMs: Long = 15_000) =
        call("$TERMINAL_CREATOR.$method", blockPayload(sid, bid, args), timeoutMs)

    suspend fun dataTicket(sid: String, bid: String): String {
        val r = call("data.connect", buildJsonObject {
            put("session_id", sid)
            put("block_id", bid)
            put("data_ref_id", "terminal")
            put("subprotocol", TERMINAL_SUBPROTOCOL)
        })
        return RexJson.decodeFromJsonElement<TicketResult>(r).ticket
    }

    fun markSeen(bid: String) {
        scope.launch(serial) {
            val m = _state.value.blocks[bid] ?: return@launch
            reviewed.seen(bid, m.statuses)
            if (m.unseen || m.notification != null) patch(m.sessionId, bid) { it.copy(unseen = false, notification = null) }
        }
    }

    suspend fun newWindow(sid: String): String? {
        val r = call("session.new_window", buildJsonObject {
            put("session_id", sid)
            put("focus", false)
            putJsonObject("layout") { putJsonObject("block") { put("flavor", SHELL_FLAVOR) } }
        }) as? JsonObject
        return r?.str("window_id")
    }

    suspend fun splitBlock(sid: String, anchorBlockId: String, direction: String) {
        call("session.new_split", buildJsonObject {
            put("session_id", sid)
            put("anchor_id", anchorBlockId)
            put("direction", direction)
            put("side", "after")
            putJsonObject("layout") { putJsonObject("block") { put("flavor", SHELL_FLAVOR) } }
        })
    }

    suspend fun closeWindow(sid: String, windowId: String) {
        call("session.close_window", buildJsonObject {
            put("session_id", sid)
            put("window_id", windowId)
        })
    }

    suspend fun closeBlock(sid: String, bid: String) {
        call("block.close", buildJsonObject {
            put("session_id", sid)
            put("block_id", bid)
        })
    }

    suspend fun newSession(label: String?): String? {
        val r = call("session.create", buildJsonObject {
            if (!label.isNullOrBlank()) put("label", label)
            putJsonArray("initial_windows") {
                add(buildJsonObject { putJsonObject("layout") { putJsonObject("block") { put("flavor", SHELL_FLAVOR) } } })
            }
        }) as? JsonObject
        return r?.str("session_id")
    }

    suspend fun remoteHosts(): RemoteHostList =
        RexJson.decodeFromJsonElement(call("hosts.list"))

    suspend fun ownBlock(sid: String, bid: String) {
        call("session.own_block", buildJsonObject {
            put("session_id", sid)
            putJsonArray("block_ids") { add(JsonPrimitive(bid)) }
            put("mode", "take")
        })
    }

    suspend fun disownBlock(sid: String, bid: String) {
        withTimeoutOrNull(5000) {
            runCatching {
                call("session.disown_block", buildJsonObject {
                    put("session_id", sid)
                    putJsonArray("block_ids") { add(JsonPrimitive(bid)) }
                })
            }
        }
    }

    companion object {
        private const val TAG = "HostConnection"

        /**
         * How long to wait before the next attempt after [attempts] failed ones: up to 15 s in the app.
         * While away, such as overnight beside a laptop asleep, it keeps growing to 5 minutes, as each
         * attempt keeps the radio up and can spend the whole connect timeout. Coming back, or a network
         * coming back, tries at once and starts again from the shortest wait.
         */
        fun retryWait(attempts: Int, away: Boolean): Long =
            minOf(if (away) 300_000L else 15_000L, 500L shl minOf(attempts, 10))
    }
}
