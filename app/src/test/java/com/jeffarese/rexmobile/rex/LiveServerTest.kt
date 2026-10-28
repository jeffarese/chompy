package com.jeffarese.rexmobile.rex

import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import com.jeffarese.rexmobile.terminal.emulator.TerminalEmulator
import com.jeffarese.rexmobile.terminal.emulator.TerminalOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Runs the app's protocol code against a real Rex server. Skipped unless REX_LIVE_HOST names a
 * server this machine can reach, such as REX_LIVE_HOST=studio-rex.tail1234.ts.net. It creates a
 * session named rex-mobile-itest and destroys it at the end.
 */
class LiveServerTest {
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()

    @Test
    fun sessionStreamInputAndResize() = runBlocking {
        val address = System.getenv("REX_LIVE_HOST")
        assumeTrue("set REX_LIVE_HOST to run", !address.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = HostConnection(HostConfig(label = "live", url = ControlConnection.normalizeHostUrl(address!!)), http, scope) {}
        host.connect()
        withTimeout(20_000) { host.state.first { it.status == ConnStatus.ONLINE } }

        val sid = host.newSession("rex-mobile-itest")!!
        var stream: BlockStream? = null
        try {
            val view = withTimeout(10_000) {
                host.state.first { st -> st.views[sid]?.windows?.firstOrNull()?.blocks?.isNotEmpty() == true }.views[sid]!!
            }
            val bid = view.windows!!.first().blocks.first().blockId

            // Stream the block.
            val out = ByteArrayOutputStream()
            val messages = mutableListOf<JsonObject>()
            var ready = false
            stream = BlockStream(http, host.config.url, scope, { host.dataTicket(sid, bid) }, object : BlockStreamListener {
                override fun onFresh() = Unit
                override fun onReady(streamBytes: Long) {
                    ready = true
                }
                override fun onOutput(bytes: ByteArray, offset: Int, length: Int) = synchronized(out) { out.write(bytes, offset, length) }
                override fun onMessage(msg: JsonObject) {
                    synchronized(messages) { messages += msg }
                }
                override fun onState(state: StreamState, detail: String?) = Unit
            })
            stream.start()
            withTimeout(10_000) { while (!ready) delay(20) }
            assertEquals("resize", synchronized(messages) { messages.first() }.str("type"))

            // Type a command and see its output arrive on the stream.
            delay(800) // let the shell draw its prompt
            host.blockMethod(sid, bid, "write", buildJsonObject { put("data", "echo rex-mobile-$((6*7))\r") })
            withTimeout(10_000) {
                while (!synchronized(out) { out.toString(Charsets.UTF_8.name()) }.contains("rex-mobile-42")) delay(50)
            }

            // Redraw from format vt the way the app does, and check the emulator shows the same text.
            val vt = RexJson.decodeFromJsonElement<FormatResult>(
                host.blockMethod(sid, bid, "format", buildJsonObject {
                    put("format", "vt")
                    put("extra_cursor", true)
                    put("extra_modes", true)
                }),
            ).content
            val size = RexJson.decodeFromJsonElement<TerminalSizeResult>(host.blockMethod(sid, bid, "size"))
            val emu = TerminalEmulator(NullOutput, size.columns, size.rows, 1000, null)
            val bytes = vt.toByteArray()
            emu.append(bytes, bytes.size)
            val shown = emu.screen.transcriptText
            assertTrue("emulator shows the command output:\n$shown", shown.contains("rex-mobile-42"))

            // Take the block and size it for a phone; the stream reports the new size.
            host.ownBlock(sid, bid)
            host.blockMethod(sid, bid, "resize", buildJsonObject {
                put("columns", 44)
                put("rows", 30)
            })
            withTimeout(5_000) {
                while (synchronized(messages) { messages.none { it.str("type") == "resize" && it.int("cols") == 44 } }) delay(30)
            }
            host.disownBlock(sid, bid)

            // A paste goes through block.event.
            host.call("block.event", buildJsonObject {
                put("session_id", sid)
                put("block_id", bid)
                put("event", buildJsonObject {
                    put("type", "paste")
                    put("payload", buildJsonObject { put("text", "echo pasted-ok") })
                })
            })
            host.blockMethod(sid, bid, "write", buildJsonObject { put("data", "\r") })
            withTimeout(10_000) {
                while (!synchronized(out) { out.toString(Charsets.UTF_8.name()) }.contains("pasted-ok\r\n")) delay(50)
            }

            // Drop the data connection and resume it from the byte count.
            stream.reconnectNow()
            withTimeout(10_000) { while (stream.state != StreamState.LIVE) delay(30) }
            host.blockMethod(sid, bid, "write", buildJsonObject { put("data", "echo after-$((1+1))-resume\r") })
            withTimeout(10_000) {
                while (!synchronized(out) { out.toString(Charsets.UTF_8.name()) }.contains("after-2-resume")) delay(50)
            }
        } finally {
            stream?.stop()
            runCatching { host.call("session.destroy", buildJsonObject { put("session_id", sid) }) }
            host.disconnect()
            delay(300)
            scope.cancel()
        }
    }

    /** Read-only: connect, read every session and block, and report what was found. */
    @Test
    fun readsAnExistingServer() = runBlocking {
        val address = System.getenv("REX_LIVE_READONLY_HOST")
        assumeTrue("set REX_LIVE_READONLY_HOST to run", !address.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = HostConnection(HostConfig(label = "ro", url = ControlConnection.normalizeHostUrl(address!!)), http, scope) {}
        try {
            host.connect()
            withTimeout(20_000) { host.state.first { it.status == ConnStatus.ONLINE } }
            delay(2500) // block details arrive after the views
            val st = host.state.value
            val blocks = st.views.values.flatMap { v -> v.windows.orEmpty().flatMap { it.blocks } }
            println("sessions=${st.sessions.size} windows=${st.views.values.sumOf { it.windows.orEmpty().size }} blocks=${blocks.size}")
            blocks.forEach { b ->
                val m = st.blocks[b.blockId]
                println("  ${m?.process}/${m?.argv0} status=${m?.status?.state} task=${m?.taskTitle != null} title=${m?.title?.isNotBlank()}")
            }
            assertTrue(st.sessions.isNotEmpty())
            assertTrue(blocks.all { st.blocks[it.blockId]?.process != null })
        } finally {
            host.disconnect()
            delay(300)
            scope.cancel()
        }
    }

    private object NullOutput : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
}
