package com.jeffarese.rexmobile.bench

import android.os.Looper
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import com.jeffarese.rexmobile.rex.ControlConnection
import com.jeffarese.rexmobile.terminal.RemoteTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/**
 * The phone's network link, shared by every socket made through a [CountingLink] on it: reads take
 * their turn at [bytesPerSecond], so the data and control connections split one link's speed as on
 * a phone. 0 is unthrottled. Throttled reads are cut to 1 KB, as [SlowLink][com.jeffarese.rexmobile.rex.SlowLink] does.
 */
class Pipe {
    @Volatile var bytesPerSecond = 0
    private var freeAt = 0L // System.nanoTime() when the link is next free; guarded by this

    fun pass(bytes: Int) {
        val rate = bytesPerSecond
        if (rate <= 0 || bytes <= 0) return
        val until = synchronized(this) {
            freeAt = maxOf(System.nanoTime(), freeAt) + bytes * 1_000_000_000L / rate
            freeAt
        }
        val wait = until - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
    }
}

/**
 * Sockets that count the bytes they receive and send on the wire (TLS included), and read through
 * [pipe]. Like SlowLink, they keep a small receive buffer, as a phone does.
 */
class CountingLink(private val pipe: Pipe) : SocketFactory() {
    val received = AtomicLong()
    val sent = AtomicLong()

    private fun socket() = object : Socket() {
        init {
            receiveBufferSize = 64 * 1024
        }

        private val input by lazy {
            val inner = super.getInputStream()
            object : InputStream() {
                override fun read(): Int = inner.read().also {
                    if (it >= 0) {
                        received.incrementAndGet()
                        pipe.pass(1)
                    }
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = inner.read(b, off, if (pipe.bytesPerSecond > 0) minOf(len, 1024) else len)
                    if (n > 0) {
                        received.addAndGet(n.toLong())
                        pipe.pass(n)
                    }
                    return n
                }

                override fun available() = inner.available()
                override fun close() = inner.close()
            }
        }

        private val output by lazy {
            val inner = super.getOutputStream()
            object : OutputStream() {
                override fun write(b: Int) {
                    inner.write(b)
                    sent.incrementAndGet()
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    inner.write(b, off, len)
                    sent.addAndGet(len.toLong())
                }

                override fun flush() = inner.flush()
                override fun close() = inner.close()
            }
        }

        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
    }

    override fun createSocket(): Socket = socket()
    override fun createSocket(host: String?, port: Int): Socket = socket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = createSocket(host, port)
    override fun createSocket(host: InetAddress?, port: Int): Socket = socket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = createSocket(address, port)
}

/**
 * A live Rex server for the benchmarks: the host's control connection through a counting link,
 * and throwaway sessions on it. Close it to disconnect; destroy each session yourself.
 * With [sharedLink] off, only data connections are throttled, as in LiveEarlyScreenTest.
 */
class LiveHost(address: String, sharedLink: Boolean = true) : AutoCloseable {
    /** The data connections' link; the control connection's too when the link is shared. */
    val pipe = Pipe()
    val controlLink = CountingLink(if (sharedLink) pipe else Pipe())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).socketFactory(controlLink).build()
    val host = HostConnection(HostConfig(label = "bench", url = ControlConnection.normalizeHostUrl(address)), http, scope) {}

    init {
        runBlocking {
            host.connect()
            withTimeout(20_000) { host.state.first { it.status == ConnStatus.ONLINE } }
        }
    }

    /** A client for one data connection, counted on its own link through the shared [pipe]. */
    fun dataClient(): Pair<OkHttpClient, CountingLink> {
        val link = CountingLink(pipe)
        return OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).socketFactory(link).build() to link
    }

    /** A new session with one shell block: its session and block ids. Name it zz-bench-… */
    fun newSession(label: String): Pair<String, String> = runBlocking {
        require(label.startsWith("zz-bench-"))
        val sid = host.newSession(label)!!
        sid to withTimeout(10_000) {
            host.state.first { st -> st.views[sid]?.windows?.firstOrNull()?.blocks?.isNotEmpty() == true }.views[sid]!!
        }.windows!!.first().blocks.first().blockId
    }

    fun destroy(sid: String) = runBlocking {
        runCatching { host.call("session.destroy", buildJsonObject { put("session_id", sid) }) }
    }

    /** Take the block over and size it, as the phone does when it sizes a pane to its screen. */
    fun ownAndResize(sid: String, bid: String, columns: Int, rows: Int) = runBlocking {
        host.ownBlock(sid, bid)
        method(sid, bid, "resize", buildJsonObject {
            put("columns", columns)
            put("rows", rows)
        })
        withTimeout(10_000) {
            while (true) {
                val s = method(sid, bid, "size").jsonObject
                if (s.int("columns") == columns && s.int("rows") == rows) break
                kotlinx.coroutines.delay(100)
            }
        }
    }

    fun type(sid: String, bid: String, text: String) = runBlocking {
        method(sid, bid, "write", buildJsonObject { put("data", text) })
    }

    fun method(sid: String, bid: String, method: String, args: JsonObject? = null): JsonElement = runBlocking {
        host.blockMethod(sid, bid, method, args, timeoutMs = 60_000)
    }

    /** The server's own screen and scrollback as plain text, rows trimmed. */
    fun text(sid: String, bid: String): String =
        method(sid, bid, "format", buildJsonObject {
            put("format", "text")
            put("trim", true)
        }).jsonObject.str("content").orEmpty()

    override fun close() {
        runBlocking { host.disconnect() }
        scope.cancel()
    }
}

/**
 * A cell's style in engine-neutral terms: colors as #rrggbb or palette:N, null for the default; underline as
 * single, double, curly, dotted or dashed, null for none; hyperlink as its URL.
 */
data class CellStyle(
    val fg: String? = null,
    val bg: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val underline: String? = null,
    val underlineColor: String? = null,
    val hyperlink: String? = null,
)

/** Runs the main looper with real time, which Robolectric otherwise only moves when told. */
object MainLoop {
    private var last = System.nanoTime()

    /** Sleep [ms] and move the main looper's clock on by the real time passed since it was last moved. */
    fun step(ms: Long = 10) {
        Thread.sleep(ms)
        val now = System.nanoTime()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofNanos(maxOf(0, now - last)))
        last = now
    }

    /** Keep the main looper going until [done], failing after [timeoutMs] with [state]. */
    fun waitFor(what: String, timeoutMs: Long, state: () -> String = { "" }, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what; ${state()}")
            step()
        }
    }

    /** Keep the main looper going for [ms]. */
    fun run(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) step()
    }
}

/** Wait until [t] has drawn its first screen from a fresh stream and caught up. */
fun awaitLoaded(t: RemoteTerminal, timeoutMs: Long = 120_000) =
    MainLoop.waitFor("loaded", timeoutMs, { "syncing=${t.syncing.value} catchingUp=${t.catchingUp.value} load=${t.lastLoad}" }) {
        !t.syncing.value && !t.catchingUp.value
    }

/** Benchmarks' results go to app/build/bench as well as standard output. */
fun writeResult(name: String, json: JsonElement) {
    val text = prettyJson.encodeToString(JsonElement.serializer(), json)
    println("BENCH $name\n$text")
    val dir = File("build/bench").apply { mkdirs() }
    File(dir, "$name.json").writeText(text + "\n")
    println("wrote ${File(dir, "$name.json").absolutePath}")
}

private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

fun median(values: List<Long>): Long? {
    val s = values.sorted()
    return when {
        s.isEmpty() -> null
        s.size % 2 == 1 -> s[s.size / 2]
        else -> (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
