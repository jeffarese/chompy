package com.jeffarese.rexmobile.terminal

import android.os.Looper
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import com.jeffarese.rexmobile.rex.ControlConnection
import com.jeffarese.rexmobile.rex.SlowLink
import com.jeffarese.rexmobile.rex.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * A long conversation over a slow link: its stream's snapshot takes many seconds, and the screen
 * shows well before then, read on the control connection. Skipped unless REX_LIVE_HOST is set; it
 * fills a throwaway shell's scrollback and destroys its session at the end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveEarlyScreenTest {
    @Test
    fun aLongScrollbackShowsBeforeItsSnapshotEnds() {
        val address = System.getenv("REX_LIVE_HOST")
        assumeTrue("set REX_LIVE_HOST to run", !address.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val host = HostConnection(HostConfig(label = "live", url = ControlConnection.normalizeHostUrl(address!!)), http, scope) {}
        val (sid, bid) = runBlocking {
            host.connect()
            withTimeout(20_000) { host.state.first { it.status == ConnStatus.ONLINE } }
            val sid = host.newSession("rex-mobile-early-itest")!!
            sid to withTimeout(10_000) {
                host.state.first { st -> st.views[sid]?.windows?.firstOrNull()?.blocks?.isNotEmpty() == true }.views[sid]!!
            }.windows!!.first().blocks.first().blockId
        }
        var terminal: RemoteTerminal? = null
        try {
            runBlocking {
                // Long lines, as a conversation's are, then a last line to look for: the command echoed doesn't match it.
                // Random text, so the snapshot stays slow on a server that compresses data connections.
                host.blockMethod(sid, bid, "write", buildJsonObject {
                    put("data", "head -c ${LINES * 112} /dev/urandom | base64 | fold -w 150 | head -n $LINES; echo EARLY-$((6*7))\r")
                })
                var total = 0L
                withTimeout(30_000) {
                    while (total < LINES * 150L) {
                        delay(500)
                        val r = host.blockMethod(sid, bid, "format", buildJsonObject { put("format", "vt"); put("max_bytes", 1024) }).jsonObject
                        total = (r["total"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
                    }
                }
                delay(1_000)
                println("scrollback: $total bytes as vt")
            }

            val started = System.currentTimeMillis()
            val slow = http.newBuilder().socketFactory(SlowLink(BYTES_PER_SECOND)).build()
            val t = RemoteTerminal(host, sid, bid, slow).also { terminal = it }
            t.start()
            waitFor("the screen shown early", 15_000, { state(t) }) { t.catchingUp.value && screen(t).contains("EARLY-42") }
            val shown = System.currentTimeMillis() - started
            assertFalse("input open while it catches up", t.syncing.value)
            assertTrue(t.streamState.value == StreamState.LIVE)

            waitFor("caught up", 120_000, { state(t) }) { !t.catchingUp.value && !t.syncing.value }
            val caughtUp = System.currentTimeMillis() - started
            println("shown in $shown ms, caught up in $caughtUp ms: ${t.lastLoad}")
            assertTrue("shown well before the snapshot ended: $shown ms against ${t.lastLoad}", shown * 3 < (t.lastLoad?.snapshotMs ?: 0))
            assertTrue(screen(t).contains("EARLY-42"))
        } finally {
            terminal?.close()
            runBlocking {
                runCatching { host.call("session.destroy", buildJsonObject { put("session_id", sid) }) }
                host.disconnect()
            }
            scope.cancel()
        }
    }

    private fun screen(t: RemoteTerminal): String = t.session.emulator?.let { ScreenReader.visibleLines(it) }.orEmpty().joinToString("\n")

    private fun state(t: RemoteTerminal): String =
        "catchingUp=${t.catchingUp.value} syncing=${t.syncing.value} stream=${t.streamState.value} " +
            "load=${t.lastLoad} screen ends: ${screen(t).lines().filter { it.isNotBlank() }.takeLast(2)}"

    /** Robolectric runs the main looper only when told, so keep it going while real time passes. */
    private fun waitFor(what: String, timeoutMs: Long, state: () -> String = { "" }, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what; ${state()}")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(50)
        }
    }

    private companion object {
        const val LINES = 8_000
        const val BYTES_PER_SECOND = 48 * 1024
    }
}
