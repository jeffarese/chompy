package com.jeffarese.rexmobile.rex

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

enum class StreamState { CONNECTING, LIVE, RECONNECTING, ENDED, FAILED }

interface BlockStreamListener {
    /** A new stream began: what is shown is stale until its snapshot restores the screen, or it is redrawn. */
    fun onFresh()
    /** Bytes of the snapshot a fresh stream starts with. The array is only valid during the call. */
    fun onSnapshot(bytes: ByteArray, offset: Int, length: Int) = Unit
    /** The snapshot, [streamBytes] into the stream, is over and program output follows. */
    fun onReady(streamBytes: Long)
    /** Program output. The array is only valid during the call. */
    fun onOutput(bytes: ByteArray, offset: Int, length: Int)
    fun onMessage(msg: JsonObject)
    fun onState(state: StreamState, detail: String?)
    /** A connection opened, [compressed] if the server agreed to compress it, as servers from Rex build 1531 do. */
    fun onOpened(compressed: Boolean) = Unit
}

/**
 * Streams one terminal block's output over a data connection. After a drop it resumes the stream
 * from the exact byte it stopped at, and starts a fresh stream when a resume isn't possible.
 * Listener calls arrive on OkHttp's threads, one at a time.
 *
 * The socket, the count of bytes received, the resume token and the retry are shared between
 * OkHttp's threads and the caller's, so they change under [lock]: a byte from a socket already
 * replaced must not count towards the resume of the next one.
 *
 * The connection sends no pings of its own. The server answers a ping only once it can write, and
 * while a slow link is still taking a large stream, such as a long conversation's snapshot, its
 * writes wait on the phone: the answer misses the server's deadline and it drops the connection,
 * 20 seconds in. A stream dropped partway can't be resumed, so the next one starts the snapshot
 * over, and the pane never loaded. The server pings data connections itself; a dead path shows as
 * the host's control connection dropping, which [reconnectIfQuiet] answers.
 */
class BlockStream(
    http: OkHttpClient,
    private val baseUrl: String,
    private val scope: CoroutineScope,
    private val getTicket: suspend () -> String,
    private val listener: BlockStreamListener,
) {
    // One that sends none already, such as a test's, is used as it is.
    private val client = if (http.pingIntervalMillis == 0) http else http.newBuilder().pingInterval(0, TimeUnit.SECONDS).build()
    private val lock = Any()
    private var ws: WebSocket? = null
    private var parser: TerminalStreamParser? = null
    private var token: ByteArray? = null
    private var received = 0L
    private var attempts = 0
    private var retryJob: Job? = null
    @Volatile private var stopped = false
    @Volatile private var paused = false
    @Volatile var state = StreamState.CONNECTING
        private set

    /** How long the server keeps the stream to resume after a drop, from its last welcome. */
    @Volatile var keepMs: Long? = null
        private set

    /** When the last message arrived, on the clock that counts while the phone sleeps. */
    @Volatile private var lastMessageAt = 0L

    fun start() = fresh()

    /** End the stream for good. */
    fun stop() {
        stopped = true
        synchronized(lock) {
            retryJob?.cancel()
            ws?.close(1000, null)
            ws = null
        }
    }

    /**
     * Drop the socket and wait for [reconnectNow], such as while the app is away. The server keeps
     * the stream a while, so coming back soon resumes it.
     */
    fun pause() {
        if (stopped || state == StreamState.ENDED) return
        paused = true
        synchronized(lock) { retryJob?.cancel() }
        dropSocket()
        setState(StreamState.RECONNECTING, "paused")
    }

    /**
     * Reconnect if nothing has arrived for [quietMs], such as after the host's control connection
     * dropped: a quiet stream may be on the same dead path, while one still receiving is alive, and
     * dropping it partway would lose what is on its way.
     */
    fun reconnectIfQuiet(quietMs: Long) {
        if (stopped || paused || state != StreamState.LIVE) return
        if (SystemClock.elapsedRealtime() - lastMessageAt < quietMs) return
        reconnectNow()
    }

    /** Reconnect now, such as when the app returns to the foreground. */
    fun reconnectNow() {
        if (stopped || state == StreamState.ENDED) return
        paused = false
        synchronized(lock) {
            retryJob?.cancel()
            attempts = 0
        }
        dropSocket()
        resumeOrFresh()
    }

    private fun setState(s: StreamState, detail: String? = null) {
        state = s
        listener.onState(s, detail)
    }

    private fun fresh() {
        if (stopped || paused) return
        setState(if (synchronized(lock) { received } > 0) StreamState.RECONNECTING else StreamState.CONNECTING)
        // Started once it is the retry, so a retry it schedules at once isn't replaced by it.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ticket = try {
                getTicket()
            } catch (e: Exception) {
                // Cancelled by a reconnect, a pause or the end, which has its own plan; a call that timed out is a failure.
                ensureActive()
                // Its session or block is gone, which asking again won't change.
                if (e is RexCallException && e.error.status == 404) return@launch setState(StreamState.ENDED, "closed on the host")
                scheduleRetry(e.message ?: "no ticket") { fresh() }
                return@launch
            }
            // A ticket of another shape is a server this app can't speak to, which asking again won't change.
            val hello = runCatching { DataHandshake.hello(ticket) }.getOrElse { return@launch setState(StreamState.FAILED, "unexpected ticket") }
            if (!stopped && !paused) open(isFresh = true) { it.send(hello.toByteString()) }
        }
        synchronized(lock) { retryJob = job }
        job.start()
    }

    private fun resumeOrFresh() {
        val (t, count) = synchronized(lock) { token to received }
        if (t == null) return fresh()
        setState(StreamState.RECONNECTING)
        open(isFresh = false) { it.send(DataHandshake.resume(t, count).toByteString()) }
    }

    private fun open(isFresh: Boolean, handshake: (WebSocket) -> Unit) {
        dropSocket()
        val request = Request.Builder()
            .url("${ControlConnection.toWs(baseUrl)}/api/data")
            .header("Sec-WebSocket-Protocol", TERMINAL_SUBPROTOCOL)
            .build()
        var welcomed = false
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpened(response.header("Sec-WebSocket-Extensions")?.contains("permessage-deflate", ignoreCase = true) == true)
                handshake(webSocket)
            }

            // Under the lock, so a socket being replaced can't count bytes the next one asks for again.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = synchronized(lock) { receive(webSocket, bytes) }

            private fun receive(webSocket: WebSocket, bytes: ByteString) {
                if (ws !== webSocket) return
                lastMessageAt = SystemClock.elapsedRealtime()
                val data = bytes.toByteArray()
                if (!welcomed) {
                    when (val reply = DataHandshake.decode(data)) {
                        null -> return
                        is DataHandshake.Reject -> {
                            welcomed = true
                            val detail = DataHandshake.rejectReasons[reply.reason] ?: "rejected (${reply.reason})"
                            if (reply.reason != 2) token = null
                            forget(webSocket)
                            // A handshake the server can't read fails the same way every time.
                            if (reply.reason in DataHandshake.PERMANENT_REJECTS) setState(StreamState.FAILED, detail)
                            else scheduleRetry(detail) { resumeOrFresh() }
                        }
                        is DataHandshake.Welcome -> {
                            welcomed = true
                            token = reply.token
                            keepMs = reply.keepMs
                            if (isFresh) {
                                received = 0
                                parser = TerminalStreamParser(reply.nonce, object : StreamSink {
                                    override fun snapshot(bytes: ByteArray, offset: Int, length: Int) =
                                        listener.onSnapshot(bytes, offset, length)

                                    override fun output(bytes: ByteArray, offset: Int, length: Int) =
                                        listener.onOutput(bytes, offset, length)

                                    override fun message(msg: JsonObject) = listener.onMessage(msg)
                                })
                                listener.onFresh()
                            } else {
                                // Resumed. A fresh stream works once its screen has come: one can be welcomed and dropped each time.
                                attempts = 0
                            }
                            setState(StreamState.LIVE)
                        }
                    }
                    return
                }
                received += data.size
                val p = parser ?: return
                val wasPast = p.pastSnapshot
                p.push(data)
                if (!wasPast && p.pastSnapshot) {
                    attempts = 0
                    listener.onReady(received)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (forget(webSocket)) handleClose(code, reason, welcomed)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (forget(webSocket)) handleClose(code, reason, welcomed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (forget(webSocket)) handleClose(1006, t.message ?: "connection lost", welcomed)
            }
        })
        synchronized(lock) { ws = socket }
    }

    /** Stop tracking a socket. Returns false if it was already replaced. */
    private fun forget(s: WebSocket): Boolean = synchronized(lock) {
        if (ws !== s) return false
        ws = null
        true
    }

    private fun handleClose(code: Int, reason: String, welcomed: Boolean) {
        if (stopped) return
        when (code) {
            1000, 4000 -> setState(StreamState.ENDED, if (code == 4000) "block closed" else "stream ended")
            1008 -> if (reason == "data provider unavailable") setState(StreamState.FAILED, reason)
            else {
                synchronized(lock) { token = null }
                scheduleRetry(reason) { fresh() }
            }
            1013 -> {
                synchronized(lock) { token = null }
                scheduleRetry(reason.ifBlank { "reconnect required" }) { fresh() }
            }
            // A drop: the server keeps the stream for a while, so resume it.
            else -> if (!welcomed && synchronized(lock) { token } == null) scheduleRetry(reason) { fresh() }
            else scheduleRetry(reason) { resumeOrFresh() }
        }
    }

    private fun scheduleRetry(detail: String, action: () -> Unit) {
        if (stopped || paused) return
        setState(StreamState.RECONNECTING, detail)
        synchronized(lock) {
            val wait = minOf(8000L, 250L shl minOf(attempts, 5))
            attempts++
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(wait)
                if (!stopped && !paused) action()
            }
        }
    }

    private fun dropSocket() {
        val s = synchronized(lock) { ws.also { ws = null } } ?: return
        // Any status but 1000 leaves the stream waiting for a resume.
        s.close(4001, "replaced")
    }
}
