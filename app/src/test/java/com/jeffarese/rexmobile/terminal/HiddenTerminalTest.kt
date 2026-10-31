package com.jeffarese.rexmobile.terminal

import android.os.Looper
import android.view.View
import com.jeffarese.rexmobile.FakeRex
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import com.jeffarese.rexmobile.terminal.emulator.TerminalEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/** What a terminal does while nobody sees it, and what it gives back afterwards, against a fake server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenTerminalTest {
    private val rex = FakeRex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = HostConnection(HostConfig(label = "fake", url = "https://fake.test"), rex, scope) {}
    private var terminal: RemoteTerminal? = null

    @After
    fun tearDown() {
        terminal?.close()
        host.disconnect()
        scope.cancel()
    }

    private fun start(): RemoteTerminal {
        rex.block("b1").screen = "\u001b[Hshell"
        host.connect()
        waitFor("online") { host.state.value.status == ConnStatus.ONLINE }
        val t = RemoteTerminal(host, "s1", "b1", rex).also {
            terminal = it
            it.start()
        }
        return t
    }

    @Test
    fun outputNobodySeesWaitsAndShowingItFeedsItAtOnce() {
        val t = start()
        val shown = showOnScreen(t)
        waitFor("the first screen") { !t.syncing.value && screen().contains("shell") }

        t.view = null
        rex.datas.last().output("\r\nhidden-1".toByteArray())
        // Arrived, but not fed until the wait is over.
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(screen().contains("hidden-1"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RemoteTerminal.HIDDEN_FLUSH_MS))
        assertTrue(screen().contains("hidden-1"))

        rex.datas.last().output("\r\nhidden-2".toByteArray())
        Thread.sleep(300)
        assertFalse(screen().contains("hidden-2"))
        t.view = shown.terminalView
        assertTrue("fed as soon as it shows", screen().contains("hidden-2"))
    }

    @Test
    fun aLotOfOutputNobodySeesIsFedWithoutWaiting() {
        val t = start()
        showOnScreen(t)
        waitFor("the first screen") { !t.syncing.value && screen().contains("shell") }

        t.view = null
        val lines = (0 until 10_000).joinToString("") { "\r\nline $it of output nobody sees yet" }
        assertTrue(lines.length > RemoteTerminal.HIDDEN_FLUSH_BYTES)
        rex.datas.last().output(lines.toByteArray())
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(screen().contains("line 9999 of output"))
    }

    @Test
    fun aScreenTheAppHidesIsReadOnlyOnReturn() {
        val t = start()
        val shown = showOnScreen(t)
        waitFor("the first screen") { !t.syncing.value && screen().contains("shell") }
        pump(300)
        assertFalse(t.screenInfo.value.fullscreen)

        // The app goes to the background with the terminal on screen, and a program takes the alternate screen.
        shown.terminalView.dispatchWindowVisibilityChanged(View.GONE)
        rex.datas.last().output("\u001b[?1049h\u001b[?1000hfull".toByteArray())
        waitFor("the output") { screen().contains("full") }
        pump(1000)
        assertFalse("not read while away", t.screenInfo.value.fullscreen)

        shown.terminalView.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertTrue("read at once on return", t.screenInfo.value.fullscreen)
    }

    @Test
    fun historyReadForAViewIsCompressedAgainOnceItGoes() {
        val t = start()
        showOnScreen(t)
        waitFor("the first screen") { !t.syncing.value && screen().contains("shell") }
        rex.datas.last().output((0 until 3_000).joinToString("") { "\r\nline $it " + "x".repeat(100) }.toByteArray())
        waitFor("the output") { screen().contains("line 2999") }
        val emulator = t.session.emulator!!
        quiet()
        val compressed = emulator.residentBytes

        // Reading it all, as copying it does, unpacks it without counting as activity.
        readAll(emulator)
        val unpacked = emulator.residentBytes
        assertTrue("$unpacked after reading, $compressed before", unpacked > compressed * 2)
        quiet()
        assertEquals("nothing compresses it while the view is there", unpacked, emulator.residentBytes)

        t.view = null
        quiet()
        val again = emulator.residentBytes
        println("history of 3000 rows: $compressed bytes compressed, $unpacked read, $again once the view went")
        assertTrue("$again once the view went, $unpacked read", again < unpacked / 2)
    }

    @Test
    fun trimmingMemoryCompressesAtOnce() {
        val t = start()
        showOnScreen(t)
        waitFor("the first screen") { !t.syncing.value && screen().contains("shell") }
        rex.datas.last().output((0 until 3_000).joinToString("") { "\r\nline $it " + "x".repeat(100) }.toByteArray())
        waitFor("the output") { screen().contains("line 2999") }
        val emulator = t.session.emulator!!
        readAll(emulator)
        val unpacked = emulator.residentBytes

        t.trimMemory()
        assertTrue("${emulator.residentBytes} after, $unpacked before", emulator.residentBytes < unpacked / 2)
        // Rows let go are read again when wanted.
        assertTrue(screen().contains("line 2999"))
    }

    /** Every row of history and screen, which unpacks what the terminal compressed. */
    private fun readAll(emulator: TerminalEmulator) =
        (-emulator.screen.activeTranscriptRows until emulator.mRows).map { emulator.screen.getSelectedText(0, it, emulator.mColumns - 1, it) }

    private fun screen(): String = terminal!!.session.emulator?.let { ScreenReader.visibleLines(it) }.orEmpty().joinToString("\n")

    /** Let the stream stay quiet long enough for history to be compressed. */
    private fun quiet() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RemoteTerminal.COMPRESS_AFTER_QUIET_MS + 500))

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
