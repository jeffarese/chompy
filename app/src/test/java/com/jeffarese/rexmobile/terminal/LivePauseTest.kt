package com.jeffarese.rexmobile.terminal

import android.os.Looper
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import com.jeffarese.rexmobile.rex.ControlConnection
import com.jeffarese.rexmobile.rex.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
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
 * Pauses a shell's stream on a real Rex server, as leaving the app for a while does: the screen
 * stays as it was, output meanwhile doesn't arrive, and resuming catches up without redrawing.
 * Skipped unless REX_LIVE_HOST is set. It runs no agent, and destroys its session at the end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LivePauseTest {
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    private var terminal: RemoteTerminal? = null

    @Test
    fun pausedTerminalKeepsItsScreenAndCatchesUp() {
        val address = System.getenv("REX_LIVE_HOST")
        assumeTrue("set REX_LIVE_HOST to run", !address.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = HostConnection(HostConfig(label = "live", url = ControlConnection.normalizeHostUrl(address!!)), http, scope) {}
        val (sid, bid) = runBlocking {
            host.connect()
            withTimeout(20_000) { host.state.first { it.status == ConnStatus.ONLINE } }
            val sid = host.newSession("rex-mobile-pause-itest")!!
            val view = withTimeout(10_000) {
                host.state.first { st -> st.views[sid]?.windows?.firstOrNull()?.blocks?.isNotEmpty() == true }.views[sid]!!
            }
            sid to view.windows!!.first().blocks.first().blockId
        }
        try {
            val t = RemoteTerminal(host, sid, bid, http).also { terminal = it }
            t.start()
            waitFor("the first screen") { !t.syncing.value && screen().any { it.isNotBlank() } }
            pump(800) // let the shell draw its prompt
            t.typeText("echo before-$PAUSE\r")
            waitFor("the first echo") { screen().any { it == "before-$PAUSE" } }
            println("keeps: ${t.keepMs} ms")

            t.pause()
            assertTrue(t.paused)
            assertEquals(StreamState.RECONNECTING, t.streamState.value)
            // Output while paused stays on the server: the screen is the one left behind.
            runBlocking { host.blockMethod(sid, bid, "write", buildJsonObject { put("data", "echo during-$PAUSE\r") }) }
            pump(1500)
            assertTrue(screen().any { it == "before-$PAUSE" })
            assertFalse(screen().any { it == "during-$PAUSE" })
            assertEquals(StreamState.RECONNECTING, t.streamState.value)

            val resumed = System.currentTimeMillis()
            var redrawn = false
            t.resume()
            waitFor("the stream after resuming") {
                if (t.syncing.value) redrawn = true
                t.streamState.value == StreamState.LIVE && !t.syncing.value
            }
            println("live again in ${System.currentTimeMillis() - resumed} ms, redrawn: $redrawn")
            waitFor("what was written while paused") { screen().any { it == "during-$PAUSE" } }
            assertFalse("a resume within the server's keep time replays, without a redraw", redrawn)
            assertFalse(t.paused)
        } finally {
            terminal?.close()
            runBlocking {
                runCatching { host.call("session.destroy", buildJsonObject { put("session_id", sid) }) }
                host.disconnect()
            }
            scope.cancel()
        }
    }

    private fun screen(): List<String> = terminal!!.session.emulator?.let { ScreenReader.visibleLines(it) }.orEmpty().map { it.trimEnd() }

    /** Robolectric runs the main looper only when told, so keep it going while real time passes. */
    private fun pump(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            Thread.sleep(25)
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what; screen: ${screen()}")
            pump(50)
        }
    }

    private companion object {
        /** Echoed text that can't be mistaken for the command line that typed it. */
        const val PAUSE = "pause"
    }
}
