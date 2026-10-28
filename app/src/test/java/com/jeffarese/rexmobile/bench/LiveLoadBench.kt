package com.jeffarese.rexmobile.bench

import com.jeffarese.rexmobile.terminal.RemoteTerminal
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How long a fresh stream takes to load, over links of a few speeds, for shells with scrollbacks
 * of a few sizes. Each scrollback is numbered, 150-column lines of random base64, so compression
 * can't hide its size and the oldest line the phone holds can be read back.
 *
 * Skipped unless REX_LIVE_HOST is set. It makes one throwaway session per scrollback size, named
 * zz-bench-load-…, and destroys each when done. BENCH_SIZES (lines, 0 for a fresh shell),
 * BENCH_LINKS (KB/s, 0 unthrottled) and BENCH_RUNS narrow the matrix. The link's speed is shared by
 * the stream and the control connection, as on a phone; BENCH_THROTTLE=data throttles the stream alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveLoadBench {
    @Test
    fun loadOfAFreshStream() {
        val address = System.getenv("REX_LIVE_HOST")
        assumeTrue("set REX_LIVE_HOST to run", !address.isNullOrBlank())
        val sizes = envList("BENCH_SIZES", listOf(0, 2_000, 14_000))
        val links = envList("BENCH_LINKS", listOf(0, 512, 48))
        val runs = envList("BENCH_RUNS", listOf(3)).first()
        // On a phone the stream and the control connection share one link; "data" throttles the stream alone.
        val shared = System.getenv("BENCH_THROTTLE") != "data"

        val cells = mutableListOf<JsonObject>()
        LiveHost(address!!, sharedLink = shared).use { live ->
            for (size in sizes) {
                val (sid, bid) = live.newSession("zz-bench-load-$size")
                try {
                    live.ownAndResize(sid, bid, COLUMNS, ROWS)
                    val server = fill(live, sid, bid, size)
                    for (kbps in links) {
                        val results = mutableListOf<JsonObject>()
                        for (run in 1..runs) {
                            // Another client, such as the desktop app, may have sized the block meanwhile.
                            live.ownAndResize(sid, bid, COLUMNS, ROWS)
                            val r = measure(live, sid, bid, kbps)
                            println("size=$size link=${kbps}KB/s run=$run: $r")
                            results += r
                            // A run this slow says enough once.
                            if (run == 1 && (r.long("loadedMs") ?: 0) > SLOW_RUN_MS) break
                        }
                        cells += cell(size, kbps, server, results)
                    }
                } finally {
                    live.destroy(sid)
                }
            }
        }
        writeResult("live-load-${BenchEngine.NAME}" + (if (shared) "" else "-data-only"), buildJsonObject {
            put("engine", BenchEngine.NAME)
            put("throttle", if (shared) "shared" else "data")
            put("columns", COLUMNS)
            put("rows", ROWS)
            put("transcriptRows", RemoteTerminal.TRANSCRIPT_ROWS)
            put("cells", buildJsonArray { cells.forEach { add(it) } })
        })
    }

    /** Fill a fresh shell with [lines] numbered lines and a last one to look for; what the server then holds. */
    private fun fill(live: LiveHost, sid: String, bid: String, lines: Int): JsonObject {
        // The marker is computed by the shell, so the command echoed doesn't show it.
        val command = if (lines == 0) "echo $MARKER_COMMAND\r" else
            "clear; head -c ${lines * 107L} /dev/urandom | LC_ALL=C base64 | tr -d '\\n' | fold -w 142 | head -n $lines | " +
                "awk '{printf \"L%06d %s\\n\", NR, \$0}'; echo $MARKER_COMMAND\r"
        live.type(sid, bid, command)
        var total = -1L
        runBlocking {
            withTimeout(120_000) {
                var stable = 0
                while (stable < 3) {
                    delay(500)
                    val t = vtTotal(live, sid, bid)
                    stable = if (t == total && t > 0) stable + 1 else 0
                    total = t
                }
            }
        }
        // The whole text can be larger than one reply may be, so read its first and last parts.
        val head = live.method(sid, bid, "format", buildJsonObject {
            put("format", "text")
            put("trim", true)
            put("max_bytes", 8 * 1024)
        }).jsonObject
        val textBytes = head.long("total") ?: head.str("content").orEmpty().toByteArray().size.toLong()
        val tail = head.long("id")?.let { id ->
            live.method(sid, bid, "blob_read", buildJsonObject {
                put("id", id)
                put("offset", maxOf(0, textBytes - 8 * 1024))
                put("max_bytes", 16 * 1024)
            }).jsonObject.str("data").orEmpty()
        } ?: head.str("content").orEmpty()
        check(MARKER in tail) { "the marker never showed on the server" }
        val numbers = (LINES.findAll(head.str("content").orEmpty()) + LINES.findAll(tail)).map { it.groupValues[1].toInt() }.toList()
        return buildJsonObject {
            put("lines", lines)
            put("vtBytes", total)
            put("textBytes", textBytes)
            numbers.minOrNull()?.let { put("oldestLine", it) }
            numbers.maxOrNull()?.let { put("newestLine", it) }
        }
    }

    private fun vtTotal(live: LiveHost, sid: String, bid: String): Long =
        (live.method(sid, bid, "format", buildJsonObject {
            put("format", "vt")
            put("max_bytes", 1024)
        }).jsonObject["total"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0

    /** Open a fresh stream on the block over a link of [kbps] and time its load. */
    private fun measure(live: LiveHost, sid: String, bid: String, kbps: Int): JsonObject {
        live.pipe.bytesPerSecond = kbps * 1024
        val (http, dataLink) = live.dataClient()
        val controlAt = live.controlLink.received.get()
        val controlSentAt = live.controlLink.sent.get()
        val started = System.nanoTime()
        fun ms() = (System.nanoTime() - started) / 1_000_000
        val t = RemoteTerminal(live.host, sid, bid, http)
        try {
            t.start()
            var firstScreen: Long? = null
            var inputOpen: Long? = null
            var loaded: Long? = null
            MainLoop.waitFor("a fresh stream loaded at $kbps KB/s", RUN_TIMEOUT_MS, { "syncing=${t.syncing.value} catchingUp=${t.catchingUp.value}" }) {
                val now = ms()
                if (firstScreen == null && BenchEngine.visibleLines(t.session).any { MARKER in it }) firstScreen = now
                if (inputOpen == null && !t.syncing.value) inputOpen = now
                if (!t.syncing.value && !t.catchingUp.value && !BenchEngine.historyLoading(t)) loaded = now
                loaded != null
            }
            check(BenchEngine.visibleLines(t.session).any { MARKER in it }) { "loaded without the marker on screen" }
            val dataBytes = dataLink.received.get()
            val controlBytes = live.controlLink.received.get() - controlAt
            val rows = BenchEngine.allRows(t.session)
            val numbers = rows.mapNotNull { LINE.find(it)?.groupValues?.get(1)?.toInt() }
            val load = t.lastLoad
            return buildJsonObject {
                put("firstScreenMs", firstScreen)
                put("inputOpenMs", inputOpen)
                put("loadedMs", loaded)
                put("dataBytes", dataBytes)
                put("controlBytes", controlBytes)
                put("controlSentBytes", live.controlLink.sent.get() - controlSentAt)
                put("historyRows", BenchEngine.historyRows(t.session))
                numbers.minOrNull()?.let { put("oldestLine", it) }
                numbers.maxOrNull()?.let { put("newestLine", it) }
                BenchEngine.size(t.session)?.let { (c, r) -> put("size", "${c}x$r") }
                put("lastLoad", load?.toString())
                if (load != null) putJsonObject("load") {
                    put("snapshotBytes", load.snapshotBytes)
                    put("snapshotMs", load.snapshotMs)
                    put("quietMs", load.quietMs)
                    put("redrawChars", load.redrawChars)
                    put("redrawMs", load.redrawMs)
                    put("drawMs", load.drawMs)
                    put("shownEarlyIn", load.shownEarlyIn)
                }
            }
        } finally {
            t.close()
            live.pipe.bytesPerSecond = 0
            // Let the stream's close reach the server before the next one opens.
            MainLoop.run(500)
        }
    }

    private fun cell(size: Int, kbps: Int, server: JsonObject, runs: List<JsonObject>) = buildJsonObject {
        put("scrollbackLines", size)
        put("linkKBps", kbps)
        put("server", server)
        put("runCount", runs.size)
        putJsonObject("median") {
            for (key in listOf("firstScreenMs", "inputOpenMs", "loadedMs", "dataBytes", "controlBytes", "historyRows", "oldestLine")) {
                put(key, median(runs.mapNotNull { it.long(key) }))
            }
        }
        putJsonArray("runs") { runs.forEach { add(it) } }
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun envList(name: String, default: List<Int>): List<Int> =
        System.getenv(name)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() } ?: default

    private companion object {
        /** Wider than the lines, so each line is one row on both sides. */
        const val COLUMNS = 160
        const val ROWS = 48
        const val MARKER = "BENCH-END-42"
        const val MARKER_COMMAND = "BENCH-END-\$((40+2))"
        val LINE = Regex("""^L(\d{6}) """)
        val LINES = Regex("""^L(\d{6}) """, RegexOption.MULTILINE)
        const val RUN_TIMEOUT_MS = 300_000L
        const val SLOW_RUN_MS = 60_000L
    }
}
