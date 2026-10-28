package com.jeffarese.rexmobile.terminal

import android.os.Looper
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
import org.junit.Assert.assertEquals
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
 * Presses a "You should know" note's buttons through [RemoteTerminal] on a real Rex server, and
 * reads an explanation taller than Claude's panel: with the wheel in fullscreen, and with the keys
 * in the classic renderer.
 *
 * Claude Code can't be asked for a note, so one must be staged in the plugin's store first: an
 * entry in "sessions" with a new session id, a tag and an "offer" view holding a line and an
 * explanation long enough to overflow 40 rows at 50 columns. Set REX_LIVE_HOST, and
 * REX_LIVE_FYI_SESSION to that id (REX_LIVE_FYI_CLASSIC_SESSION to a second one for the classic
 * renderer). Claude starts with that id in a new temporary directory, and the session is destroyed
 * at the end; the store entries are the caller's to remove.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveFyiTest {
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    private var terminal: RemoteTerminal? = null

    @Test
    fun claudeCodeNotes() {
        val address = System.getenv("REX_LIVE_HOST")
        val fullscreenSession = System.getenv("REX_LIVE_FYI_SESSION")
        assumeTrue("set REX_LIVE_HOST and REX_LIVE_FYI_SESSION to run", !address.isNullOrBlank() && !fullscreenSession.isNullOrBlank())
        val classicSession = System.getenv("REX_LIVE_FYI_CLASSIC_SESSION")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = HostConnection(HostConfig(label = "live", url = ControlConnection.normalizeHostUrl(address!!)), http, scope) {}
        val (sid, bid) = runBlocking {
            host.connect()
            withTimeout(20_000) { host.state.first { it.status == ConnStatus.ONLINE } }
            val sid = host.newSession("rex-mobile-fyi-itest")!!
            val view = withTimeout(10_000) {
                host.state.first { st -> st.views[sid]?.windows?.firstOrNull()?.blocks?.isNotEmpty() == true }.views[sid]!!
            }
            sid to view.windows!!.first().blocks.first().blockId
        }
        try {
            runBlocking { host.ownBlock(sid, bid) }
            resize(host, sid, bid, 50, 40)
            val t = RemoteTerminal(host, sid, bid, http).also { terminal = it }
            showOnScreen(t)
            t.start()
            waitFor("the first screen") { !t.syncing.value && screen().any { it.isNotBlank() } }
            pump(800)

            t.typeText("cd \"$(mktemp -d)\" && command claude --session-id $fullscreenSession --model claude-haiku-5-5\r")
            walkThrough(t, fullscreen = true)
            exitClaude(t)

            if (!classicSession.isNullOrBlank()) {
                resize(host, sid, bid, 50, 24)
                pump(500)
                t.typeText("clear; CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1 command claude --session-id $classicSession --model claude-haiku-5-5\r")
                walkThrough(t, fullscreen = false)
                exitClaude(t)
            }
        } catch (e: Throwable) {
            println("screen at failure:\n${runCatching { text() }.getOrNull()}")
            throw e
        } finally {
            terminal?.let { t ->
                if (info().claudeMode != null) exitClaude(t)
                t.close()
            }
            runBlocking {
                runCatching { host.call("session.destroy", buildJsonObject { put("session_id", sid) }) }
                host.disconnect()
            }
            scope.cancel()
        }
    }

    /** Open the staged note, read all of it, dismiss it, and dismiss the thanks. */
    private fun walkThrough(t: RemoteTerminal, fullscreen: Boolean) {
        val mode = if (fullscreen) "fullscreen" else "classic"
        waitFor("the note ($mode)", 60_000) { info().fyi?.actions?.isNotEmpty() == true && info().claudeInput == "" }
        assertEquals(fullscreen, info().fullscreen)
        val offer = info().fyi!!
        println("$mode offer: ${offer.tag} · ${offer.headline} ${offer.actions}")
        val learn = offer.actions.first { it.label == "Learn more" }

        // Pressed at once: the press waits out the moment Claude ignores digits in, rather than typing one into the box.
        var at = System.currentTimeMillis()
        t.pressFyi(offer, learn)
        waitFor("the explanation ($mode)", 30_000) { info().fyi?.body?.isNotEmpty() == true }
        println("$mode opened in ${System.currentTimeMillis() - at} ms; box: '${info().claudeInput}'")
        assertEquals("", info().claudeInput)

        val open = info().fyi!!
        println("$mode window ${open.shown} of ${open.rows.size} rows, complete: ${open.complete}")
        at = System.currentTimeMillis()
        t.readFyi()
        waitFor("the whole explanation ($mode)", 30_000) { info().fyi?.complete == true }
        waitFor("the panel's end ($mode)") { info().fyi!!.let { n -> n.actions.isNotEmpty() && n.actions.all(n::shows) } }
        val whole = info().fyi!!
        println("$mode read in ${System.currentTimeMillis() - at} ms: ${whole.body.map { "${it.kind} ${it.text.take(40)}" }}")
        assertTrue(whole.body.any { it.kind == FyiLine.Kind.TITLE })
        assertTrue(whole.body.any { it.kind == FyiLine.Kind.SKETCH })
        assertTrue(whole.body.none { it.kind == FyiLine.Kind.MISSING })
        // The keys left the panel: the input box takes typing again.
        pump(800)
        t.typeText("x")
        waitFor("typing in the box ($mode)") { info().claudeInput == "x" }
        t.typeBytes(byteArrayOf(0x7f))
        waitFor("an empty box ($mode)") { info().claudeInput == "" }

        val dismiss = whole.actions.first { it.key == 0 }
        t.pressFyi(whole, dismiss)
        waitFor("the thanks ($mode)", 10_000) { info().fyi?.headline == "Dismissed." }
        val thanks = info().fyi!!
        println("$mode after dismissing: ${thanks.headline} ${thanks.actions}")
        t.pressFyi(thanks, thanks.actions.first { it.key == 0 })
        waitFor("the note to go ($mode)", 10_000) { info().fyi == null }
        assertEquals("", info().claudeInput)
    }

    private fun exitClaude(t: RemoteTerminal) {
        t.paste("/exit")
        t.sendKey(RemoteKey.ENTER)
        waitFor("Claude to exit", 15_000) { info().claudeMode == null && info().claudeInput == null }
        pump(500)
    }

    private fun resize(host: HostConnection, sid: String, bid: String, columns: Int, rows: Int) = runBlocking {
        host.blockMethod(sid, bid, "resize", buildJsonObject {
            put("columns", columns)
            put("rows", rows)
        })
    }

    private fun info() = terminal!!.screenInfo.value

    private fun screen(): List<String> = terminal!!.session.emulator?.let { ScreenReader.visibleLines(it) }.orEmpty()

    private fun text() = screen().joinToString("\n")

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
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what; last read: ${info()}")
            pump(50)
        }
    }
}
