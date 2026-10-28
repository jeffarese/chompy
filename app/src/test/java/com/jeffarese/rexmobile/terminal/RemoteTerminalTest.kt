package com.jeffarese.rexmobile.terminal

import android.os.Looper
import com.jeffarese.rexmobile.FakeRex
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import com.jeffarese.rexmobile.rex.StreamState
import com.jeffarese.rexmobile.rex.TERMINAL_CREATOR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CompletableFuture

/** How a terminal's screen is redrawn after a fresh stream, and read for prompts, against a fake server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteTerminalTest {
    private val rex = FakeRex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = HostConnection(HostConfig(label = "fake", url = "https://fake.test"), rex, scope) {}
    private var terminal: RemoteTerminal? = null
    private val format = "$TERMINAL_CREATOR.format"

    @After
    fun tearDown() {
        terminal?.close()
        host.disconnect()
        scope.cancel()
    }

    private fun start(): RemoteTerminal {
        host.connect()
        waitFor("online") { host.state.value.status == ConnStatus.ONLINE }
        return RemoteTerminal(host, "s1", "b1", rex).also {
            terminal = it
            it.start()
        }
    }

    @Test
    fun aFailedRedrawKeepsTheScreenAndIsAskedForAgain() {
        rex.block("b1").screen = "\u001b[Hold screen"
        val t = start()
        waitFor("the first screen") { !t.syncing.value && screen().contains("old screen") }

        // The next stream's redraw fails once, as a call too slow for its time does.
        rex.block("b1").screen = "\u001b[Hnew screen"
        val answer = rex.handle
        var failures = 1
        rex.handle = { method, payload ->
            if (method == format && failures-- > 0) CompletableFuture.failedFuture(FakeRex.Fail("timeout", "too slow")) else answer(method, payload)
        }
        rex.datas.last().serverClose(1013, "reconnect required")
        waitFor("the redraw that fails") { rex.count(format) == 2 }
        pump(300)
        assertTrue("still loading", t.syncing.value)
        assertTrue("the old screen kept: ${screen()}", screen().contains("old screen"))

        waitFor("the redraw asked for again") { !t.syncing.value }
        assertEquals(3, rex.count(format))
        assertTrue(screen().contains("new screen"))
    }

    @Test
    fun aRedrawTheServerRefusesGoesLiveWithoutItAndReconnectingAsksAgain() {
        rex.block("b1").screen = "\u001b[Hold screen"
        val t = start()
        waitFor("the first screen") { !t.syncing.value && screen().contains("old screen") }

        // The next stream's redraw is refused, which asking again won't change.
        rex.block("b1").screen = "\u001b[Hnew screen"
        val answer = rex.handle
        var refusals = 1
        rex.handle = { method, payload ->
            if (method == format && refusals-- > 0) CompletableFuture.failedFuture(FakeRex.Fail("method_not_found", "no such method"))
            else answer(method, payload)
        }
        rex.datas.last().serverClose(1013, "reconnect required")
        waitFor("the screen live without its redraw") { rex.count(format) == 2 && !t.syncing.value }
        assertEquals("not asked again by itself", 2, rex.count(format))
        assertTrue("the old screen kept: ${screen()}", screen().contains("old screen"))

        t.reconnectNow()
        waitFor("the redraw asked for on reconnecting") { rex.count(format) == 3 && !t.syncing.value }
        assertTrue(screen().contains("new screen"))
    }

    @Test
    fun aLongSnapshotShowsTheScreenWhileItComes() {
        rex.slowSnapshots = true
        rex.columns = 100
        rex.block("b1").screen = "\u001b[Hthe conversation"
        val t = start()
        waitFor("the screen shown early") { !t.syncing.value && screen().contains("the conversation") }
        assertTrue("catching up", t.catchingUp.value)
        // A first open hasn't had the size from the stream yet, so it asks.
        assertEquals(1, rex.count("$TERMINAL_CREATOR.size"))
        assertEquals(100, t.session.emulator!!.mColumns)
        assertEquals(1, rex.count(format))

        // Output since the stream began follows the snapshot, and the redraw after it shows it.
        rex.block("b1").screen = "\u001b[Hthe conversation\r\nand its reply"
        rex.datas.last().endSnapshot()
        rex.datas.last().output("\r\nand its reply".toByteArray())
        waitFor("caught up") { !t.catchingUp.value }
        assertFalse(t.syncing.value)
        assertEquals(2, rex.count(format))
        assertTrue(screen().contains("and its reply"))
    }

    @Test
    fun aSnapshotThatComesAtOnceIsNotReadOnTheSide() {
        rex.block("b1").screen = "\u001b[Hthe screen"
        val t = start()
        waitFor("the screen") { !t.syncing.value && screen().contains("the screen") }
        pump(RemoteTerminal.EARLY_SCREEN_AFTER_MS + 500)
        assertEquals(1, rex.count(format))
        assertEquals(0, rex.count("$TERMINAL_CREATOR.size"))
        assertFalse(t.catchingUp.value)
    }

    @Test
    fun aSnapshotIsTheScreenWithoutARedraw() {
        rex.snapshot = snapshotFixture()
        val t = start()
        waitFor("the screen restored") { !t.syncing.value && screen().contains("$ the prompt") }
        waitFor("its history restored") { t.session.emulator!!.screen.activeTranscriptRows > 300 }
        assertEquals(0, rex.count(format))
        assertFalse(t.catchingUp.value)
        assertTrue(t.lastLoad.toString(), t.lastLoad.toString().endsWith("restored"))
    }

    @Test
    fun aLongSnapshotShowsItsScreenBeforeItEnds() {
        rex.slowSnapshots = true
        rex.snapshot = snapshotFixture()
        val t = start()
        waitFor("the screen restored") { !t.syncing.value && screen().contains("$ the prompt") }
        assertTrue("catching up", t.catchingUp.value)
        // Nothing is read on the side, nor redrawn.
        pump(RemoteTerminal.EARLY_SCREEN_AFTER_MS + 500)
        assertEquals(0, rex.count(format))
        assertEquals(0, rex.count("$TERMINAL_CREATOR.size"))

        // Output since the stream began follows the snapshot.
        rex.datas.last().endSnapshot()
        rex.datas.last().output("\r\nand its reply".toByteArray())
        waitFor("caught up") { !t.catchingUp.value }
        assertTrue(screen().contains("and its reply"))
        assertEquals(0, rex.count(format))
    }

    @Test
    fun historyAProgramClearsRightAfterTheSnapshotStaysCleared() {
        rex.snapshot = snapshotFixture()
        rex.afterSnapshot = { output("\u001b[3J\r\nafter the clear".toByteArray()) }
        val t = start()
        waitFor("caught up") { !t.syncing.value && !t.catchingUp.value && screen().contains("after the clear") }
        pump(200)
        // Only the row the new line pushed up since: the snapshot's 991 rows don't come back.
        assertEquals(1, t.session.emulator!!.screen.activeTranscriptRows)
    }

    @Test
    fun aResizeRightAfterTheSnapshotKeepsItsHistory() {
        rex.snapshot = snapshotFixture()
        rex.afterSnapshot = { message(buildJsonObject { put("type", "resize"); put("cols", 100); put("rows", 30) }) }
        val t = start()
        waitFor("resized") { !t.catchingUp.value && t.session.emulator?.mColumns == 100 }
        pump(200)
        val text = t.session.emulator!!.screen.transcriptText
        assertTrue(text, text.contains("line 0001"))
        assertTrue(text.contains("line 0999"))
    }

    /** The snapshot of a 120 by 30 terminal that the server says is that size. */
    private fun snapshotFixture(): ByteArray {
        rex.columns = 120
        rex.rows = 30
        return javaClass.classLoader!!.getResource("snapshots/history.snap")!!.readBytes()
    }

    @Test
    fun aPaneClosedOnTheHostStopsAskingForAStream() {
        val answer = rex.handle
        rex.handle = { method, payload ->
            if (method == "data.connect") CompletableFuture.failedFuture(FakeRex.Fail("not_found", "block not found")) else answer(method, payload)
        }
        val t = start()
        waitFor("the stream ended") { t.streamState.value == StreamState.ENDED }
        pump(2_000)
        t.reconnectNow()
        pump(1_000)
        assertEquals(1, rex.count("data.connect"))
    }

    @Test
    fun aRedrawStoppedByAPauseHappensOnResuming() {
        rex.block("b1").screen = "\u001b[Hthe screen"
        val held = CompletableFuture<JsonElement>()
        val answer = rex.handle
        rex.handle = { method, payload -> if (method == format && !held.isDone) held else answer(method, payload) }
        val t = start()
        waitFor("the redraw asked for") { rex.count(format) == 1 }

        t.pause()
        pump(200)
        assertTrue(t.syncing.value)
        // Its answer comes too late for the call, which the pause ended.
        held.complete(JsonObject(emptyMap()))

        t.resume()
        waitFor("the redraw after resuming") { !t.syncing.value }
        assertTrue("the stream resumed rather than started again", rex.datas.last().resumed)
        assertEquals(2, rex.count(format))
        assertTrue(screen().contains("the screen"))
    }

    @Test
    fun aWideTerminalReadsOnlyTheEndOfALongRedraw() {
        rex.columns = 200
        // A long conversation with rules across the width, in three-byte characters.
        val screen = "\u001b[H" + (1..9000).joinToString("\r\n") { n -> if (n % 3 == 0) "─".repeat(200) else "line $n" }
        rex.block("b1").screen = screen
        val t = start()
        waitFor("the screen", timeoutMs = 30_000) { !t.syncing.value }

        val total = screen.toByteArray().size
        val read = rex.blobReads.sumOf { it.last - it.first + 1 }
        assertTrue("read $read of $total", read < total * 0.7)
        assertTrue(screen().contains("line 8999"))
    }

    @Test
    fun aHiddenTerminalReadsItsScreenOnceShown() {
        rex.block("b1").screen = "\u001b[Hshell"
        val t = start()
        val shown = showOnScreen(t)
        waitFor("the first screen") { !t.syncing.value && screen().contains("shell") }
        pump(300)
        assertFalse(t.screenInfo.value.fullscreen)

        // A program takes the alternate screen and the mouse while nothing shows the terminal.
        t.view = null
        rex.datas.last().output("\u001b[?1049h\u001b[?1000hfull".toByteArray())
        waitFor("the output") { screen().contains("full") }
        pump(1000)
        assertFalse("not read while hidden", t.screenInfo.value.fullscreen)

        t.view = shown.terminalView
        waitFor("the read once shown") { t.screenInfo.value.fullscreen }
    }

    private fun screen(): String = terminal!!.session.emulator?.let { ScreenReader.visibleLines(it) }.orEmpty().joinToString("\n")

    /** Robolectric runs the main looper only when told, so keep it going while real time passes. */
    private fun pump(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            Thread.sleep(25)
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what; screen: ${terminal?.let { screen() }}")
            pump(25)
        }
    }
}
