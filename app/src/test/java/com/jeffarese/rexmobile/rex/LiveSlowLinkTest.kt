package com.jeffarese.rexmobile.rex

import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * A large stream over a slow link, as a phone on mobile data opens a long conversation: its snapshot
 * takes minutes. With pings of the client's own, the server couldn't write the answer while its
 * writes waited on the reader, and dropped the connection 20 seconds in, every time. The stream must
 * stay live. Skipped unless REX_LIVE_HOST is set; it fills a throwaway shell's scrollback with
 * random text and destroys its session at the end.
 */
class LiveSlowLinkTest {
    @Test
    fun largeStreamOverSlowLinkStaysConnected() = runBlocking {
        val address = System.getenv("REX_LIVE_HOST")
        assumeTrue("set REX_LIVE_HOST to run", !address.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // The app's control client: pings every 30 s. The data client below asks for pings every 15 s,
        // as the app did, to check BlockStream turns them off whatever it is given.
        val base = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val host = HostConnection(HostConfig(label = "live", url = ControlConnection.normalizeHostUrl(address!!)), base.newBuilder().pingInterval(30, TimeUnit.SECONDS).build(), scope) {}
        host.connect()
        withTimeout(20_000) { host.state.first { it.status == ConnStatus.ONLINE } }
        val sid = host.newSession("rex-mobile-slowlink-itest")!!
        val bid = withTimeout(10_000) {
            host.state.first { st -> st.views[sid]?.windows?.firstOrNull()?.blocks?.isNotEmpty() == true }.views[sid]!!
        }.windows!!.first().blocks.first().blockId
        var stream: BlockStream? = null
        try {
            // Long lines, as a conversation's are: the scrollback keeps a number of lines. Random text, so
            // the stream stays large on a server that compresses data connections.
            host.blockMethod(sid, bid, "write", buildJsonObject { put("data", "head -c 2250000 /dev/urandom | base64 | fold -w 150\r") })
            // Wait for them to be in the scrollback: the snapshot then runs to megabytes.
            var total = 0L
            withTimeout(30_000) {
                while (total < 1_000_000) {
                    delay(500)
                    val r = host.blockMethod(sid, bid, "format", buildJsonObject { put("format", "vt"); put("max_bytes", 1024) }).jsonObject
                    total = (r["total"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
                }
            }
            println("scrollback: $total bytes as vt")

            val states = mutableListOf<String>()
            val started = System.currentTimeMillis()
            val slow = base.newBuilder().socketFactory(SlowLink(BYTES_PER_SECOND)).pingInterval(15, TimeUnit.SECONDS).build()
            stream = BlockStream(slow, host.config.url, scope, { host.dataTicket(sid, bid) }, object : BlockStreamListener {
                override fun onFresh() = Unit
                override fun onReady(streamBytes: Long) = Unit
                override fun onOutput(bytes: ByteArray, offset: Int, length: Int) = Unit
                override fun onMessage(msg: JsonObject) = Unit
                override fun onState(state: StreamState, detail: String?) {
                    synchronized(states) { states += "$state${detail?.let { " ($it)" } ?: ""} at ${System.currentTimeMillis() - started} ms" }
                }
            })
            stream.start()
            // Past the 20 s the server used to allow, and the 30 s at which the client gave up on its pong.
            delay(WATCH_MS)
            val seen = synchronized(states) { states.toList() }
            println("states: $seen")
            assertTrue("went live: $seen", seen.any { it.startsWith("LIVE") })
            assertEquals("stayed live for ${WATCH_MS / 1000} s: $seen", StreamState.LIVE, stream.state)
            assertTrue("never dropped: $seen", seen.none { it.startsWith("RECONNECTING") })
        } finally {
            stream?.stop()
            runCatching { host.call("session.destroy", buildJsonObject { put("session_id", sid) }) }
            host.disconnect()
            scope.cancel()
        }
    }

    private companion object {
        const val BYTES_PER_SECOND = 10 * 1024
        const val WATCH_MS = 45_000L
    }
}
