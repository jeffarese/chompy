package com.jeffarese.rexmobile.rex

import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import kotlinx.coroutines.CompletableDeferred
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
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * How much a server that compresses data connections saves on a scrollback of prose, as a
 * conversation's is: the snapshot is timed over a slow link, and the bytes that crossed it worked out
 * from the time. Rex servers from build 1531 (October 2026) agree to permessage-deflate on data
 * connections, which OkHttp asks for on every WebSocket. Skipped unless REX_LIVE_HOST is set; it
 * fills a throwaway shell's scrollback with the zsh manual and destroys its session at the end.
 */
class LiveCompressionTest {
    @Test
    fun aProseSnapshotCrossesCompressed() = runBlocking {
        val address = System.getenv("REX_LIVE_HOST")
        assumeTrue("set REX_LIVE_HOST to run", !address.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val base = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val host = HostConnection(HostConfig(label = "live", url = ControlConnection.normalizeHostUrl(address!!)), base, scope) {}
        host.connect()
        withTimeout(20_000) { host.state.first { it.status == ConnStatus.ONLINE } }
        val extensions = dataExtensions(base, host.config.url)
        println("data connection extensions: ${extensions ?: "none"}")
        val sid = host.newSession("rex-mobile-compress-itest")!!
        val bid = withTimeout(10_000) {
            host.state.first { st -> st.views[sid]?.windows?.firstOrNull()?.blocks?.isNotEmpty() == true }.views[sid]!!
        }.windows!!.first().blocks.first().blockId
        var stream: BlockStream? = null
        try {
            host.blockMethod(sid, bid, "write", buildJsonObject { put("data", "man zshall | col -b\r") })
            var total = 0L
            var last = -1L
            withTimeout(30_000) {
                // Until the manual has all printed: the total stops growing.
                while (total < 500_000 || total != last) {
                    last = total
                    delay(1_000)
                    val r = host.blockMethod(sid, bid, "format", buildJsonObject { put("format", "vt"); put("max_bytes", 1024) }).jsonObject
                    total = (r["total"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
                }
            }

            val ready = CompletableDeferred<Long>()
            val opened = CompletableDeferred<Boolean>()
            val started = System.currentTimeMillis()
            val slow = base.newBuilder().socketFactory(SlowLink(BYTES_PER_SECOND)).build()
            stream = BlockStream(slow, host.config.url, scope, { host.dataTicket(sid, bid) }, object : BlockStreamListener {
                override fun onFresh() = Unit
                override fun onReady(streamBytes: Long) { ready.complete(streamBytes) }
                override fun onOutput(bytes: ByteArray, offset: Int, length: Int) = Unit
                override fun onMessage(msg: JsonObject) = Unit
                override fun onState(state: StreamState, detail: String?) = Unit
                override fun onOpened(compressed: Boolean) { opened.complete(compressed) }
            })
            stream.start()
            val snapshotBytes = withTimeout(120_000) { ready.await() }
            val ms = System.currentTimeMillis() - started
            val wireBytes = BYTES_PER_SECOND * ms / 1000
            val ratio = snapshotBytes.toDouble() / wireBytes
            println("scrollback: $total bytes as vt; snapshot ${snapshotBytes / 1024} KB in $ms ms at ${BYTES_PER_SECOND / 1024} KB/s, about ${wireBytes / 1024} KB on the wire, ${"%.1f".format(ratio)}x")
            // The stream says what the server agreed to, which decides whether the app opens neighbouring tabs early.
            assertEquals(extensions?.contains("permessage-deflate") == true, opened.await())
            if (extensions?.contains("permessage-deflate") == true) assertTrue("compressed at least 2x: ${"%.1f".format(ratio)}x", ratio >= 2)
        } finally {
            stream?.stop()
            runCatching { host.call("session.destroy", buildJsonObject { put("session_id", sid) }) }
            host.disconnect()
            scope.cancel()
        }
    }

    /** The extensions the server agrees to on a data connection, closed before its handshake. */
    private suspend fun dataExtensions(http: OkHttpClient, baseUrl: String): String? {
        val answer = CompletableDeferred<String?>()
        val request = Request.Builder()
            .url("${ControlConnection.toWs(baseUrl)}/api/data")
            .header("Sec-WebSocket-Protocol", TERMINAL_SUBPROTOCOL)
            .build()
        http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                answer.complete(response.header("Sec-WebSocket-Extensions"))
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                answer.complete(null)
            }
        })
        return withTimeout(10_000) { answer.await() }
    }

    private companion object {
        const val BYTES_PER_SECOND = 48 * 1024
    }
}
