package com.jeffarese.rexmobile

import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import com.jeffarese.rexmobile.rex.ControlConnection
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Drives the real UI against a Rex server: Home lists a throwaway session, and opening its tab
 * shows the terminal. Skipped unless REX_LIVE_HOST is set. Also connects to the app's default hosts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class LiveUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeThenTerminal() {
        val address = System.getenv("REX_LIVE_HOST")
        assumeTrue("set REX_LIVE_HOST to run", !address.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = HostConnection(HostConfig(label = "itest", url = ControlConnection.normalizeHostUrl(address!!)), OkHttpClient(), scope) {}
        val (sid, windowId) = runBlocking {
            host.connect()
            withTimeout(20_000) { host.state.first { it.status == ConnStatus.ONLINE } }
            val sid = host.newSession("rex-mobile-ui")!!
            val view = withTimeout(10_000) { host.state.first { it.views[sid]?.windows?.isNotEmpty() == true }.views[sid]!! }
            sid to view.windows!!.first().windowId
        }
        try {
            // Robolectric doesn't report the process coming to the foreground, which is what connects.
            rule.runOnUiThread { (rule.activity.application as RexMobileApp).model.onForeground() }
            try {
                rule.waitUntil(30_000) { rule.onAllNodesWithTag("window:$windowId").fetchSemanticsNodes().isNotEmpty() }
            } catch (e: Throwable) {
                println(rule.onRoot().printToString(maxDepth = 30))
                throw e
            }
            rule.onNodeWithTag("window:$windowId").performClick()
            // The message box holds input until the stream is live and the screen drawn. A fresh shell
            // over a compressing server (Rex 1531 on) is drawn before "Waiting for the connection…" can
            // be seen, so only the end is waited for.
            val live = System.currentTimeMillis() + 20_000
            while (rule.onAllNodesWithText("Run a command…").fetchSemanticsNodes().isEmpty()) {
                check(System.currentTimeMillis() < live) {
                    val m = (rule.activity.application as RexMobileApp).model
                    val c = m.connections.value.first { it.state.value.views.containsKey(sid) }
                    val bid = c.state.value.views[sid]!!.windows!!.first { it.windowId == windowId }.blocks.first().blockId
                    var d = ""
                    rule.runOnUiThread { val t = m.terminal(c, sid, bid); d = "host ${c.state.value.status}, stream ${t.streamState.value}, syncing ${t.syncing.value}" }
                    "the terminal never went live: $d"
                }
                tick()
            }
            // Let the stream settle and resize.
            val until = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < until) tick()
            rule.activityRule.scenario.onActivity { check(!it.isFinishing) }
        } finally {
            runBlocking {
                runCatching { host.call("session.destroy", buildJsonObject { put("session_id", sid) }) }
                host.disconnect()
            }
            scope.cancel()
        }
    }

    /** Let 100 ms pass for Compose and for the terminal, whose waits run on the main looper's clock, which only moves when told to. */
    private fun tick() {
        rule.mainClock.advanceTimeBy(100)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(100)
    }
}
