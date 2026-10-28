package com.jeffarese.rexmobile.bench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jeffarese.rexmobile.ui.RexFonts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The terminal engine on its own, on a device: how fast it parses output, how long it takes to
 * draw a screen, and how much memory its history holds. Each test writes its results as JSON to
 * the app's external files, under bench/, and logs them under the tag RexBench.
 *
 * Run it against the benchmark build type, which isn't debuggable, so ART compiles the engine as
 * it does in a release: scripts/bench-engine.sh does that and copies the results to app/build/bench.
 */
@RunWith(AndroidJUnit4::class)
class EngineBench {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun parseThroughput() {
        val results = JSONObject()
        for (name in Corpora.names) {
            val corpus = Corpora.build(name, COLUMNS, ROWS)
            val chunks = Corpora.chunks(corpus)
            val session = BenchEngine.newSession(COLUMNS, ROWS, HISTORY_ROWS)
            repeat(WARMUP_PASSES) { chunks.forEach { BenchEngine.feed(session, it) } }
            val passes = (1..PARSE_PASSES).map {
                val start = System.nanoTime()
                for (chunk in chunks) BenchEngine.feed(session, chunk)
                System.nanoTime() - start
            }
            val mbps = passes.map { corpus.size / (it / 1e9) / 1e6 }.sorted()
            results.put(name, JSONObject()
                .put("bytes", corpus.size)
                .put("passes", passes.size)
                .put("medianMBps", round(mbps[mbps.size / 2]))
                .put("minMBps", round(mbps.first()))
                .put("maxMBps", round(mbps.last()))
                .put("medianPassMs", round(passes.sorted()[passes.size / 2] / 1e6)))
            log("parse $name: ${results.getJSONObject(name)}")
        }
        write("engine-parse", JSONObject().put("columns", COLUMNS).put("rows", ROWS).put("historyRows", HISTORY_ROWS)
            .put("chunkBytes", 16 * 1024).put("warmupPasses", WARMUP_PASSES).put("corpora", results))
    }

    @Test
    fun renderTime() {
        val typeface = RexFonts.terminal(context)
        val results = JSONObject()
        for (screen in listOf("styled", "plain")) {
            val session = BenchEngine.newSession(RENDER_COLUMNS, RENDER_ROWS, 100)
            BenchEngine.feed(session, if (screen == "styled") styledScreen() else plainScreen())
            val renderer = BenchEngine.Renderer(TEXT_SIZE_PX, typeface)
            val bitmap = Bitmap.createBitmap(SCREEN_WIDTH_PX, SCREEN_HEIGHT_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            repeat(RENDER_WARMUP) { BenchEngine.render(renderer, session, canvas) }
            val frames = LongArray(RENDER_FRAMES) {
                val start = System.nanoTime()
                BenchEngine.render(renderer, session, canvas)
                System.nanoTime() - start
            }.sorted()
            results.put(screen, JSONObject()
                .put("frames", frames.size)
                .put("medianMs", round(frames[frames.size / 2] / 1e6))
                .put("p95Ms", round(frames[(frames.size * 95) / 100] / 1e6))
                .put("maxMs", round(frames.last() / 1e6)))
            log("render $screen: ${results.getJSONObject(screen)}")
            bitmap.recycle()
        }
        val renderer = BenchEngine.Renderer(TEXT_SIZE_PX, typeface)
        write("engine-render", JSONObject()
            .put("columns", RENDER_COLUMNS).put("rows", RENDER_ROWS)
            .put("canvasPx", "${SCREEN_WIDTH_PX}x$SCREEN_HEIGHT_PX software ARGB_8888")
            .put("textSizePx", TEXT_SIZE_PX).put("cellPx", "${renderer.cellWidthPx}x${renderer.cellHeightPx}")
            .put("screens", results))
    }

    @Test
    fun historyMemory() {
        val results = JSONArray()
        for (rows in listOf(4_000, 14_000)) {
            for (styled in listOf(false, true)) {
                // The lines are made first, so only the terminal's own memory is counted.
                val lines = Corpora.chunks(Corpora.base64Lines(rows + ROWS, MEMORY_COLUMNS, styled))
                val (heapBefore, nativeBefore) = settledMemory()
                val pssBefore = Debug.getPss()
                var session = BenchEngine.newSession(MEMORY_COLUMNS, ROWS, rows)
                lines.forEach { BenchEngine.feed(session, it) }
                val (heapAfter, nativeAfter) = settledMemory()
                // PSS counts memory the engine maps itself, which neither heap does.
                val pssAfter = Debug.getPss()
                val engineBytes = BenchEngine.engineBytes(session)
                BenchEngine.compact(session)
                settledMemory()
                val pssCompacted = Debug.getPss()
                // An engine that drops its oldest history a page at a time, as libghostty-vt does, holds about as many.
                val held = BenchEngine.historyRows(session)
                assertTrue("$held history rows held of $rows", abs(held - rows) <= rows / 20)
                val result = JSONObject()
                    .put("historyRows", rows)
                    .put("historyRowsHeld", held)
                    .put("columns", MEMORY_COLUMNS)
                    .put("styled", styled)
                    .put("javaHeapBytes", heapAfter - heapBefore)
                    .put("nativeHeapBytes", nativeAfter - nativeBefore)
                    .put("pssBytes", (pssAfter - pssBefore) * 1024)
                    .put("engineBytes", engineBytes ?: JSONObject.NULL)
                    .put("compactedPssBytes", (pssCompacted - pssBefore) * 1024)
                    .put("compactedEngineBytes", BenchEngine.engineBytes(session) ?: JSONObject.NULL)
                    .put("bytesPerRow", (heapAfter - heapBefore + nativeAfter - nativeBefore) / (held + ROWS))
                results.put(result)
                log("memory: $result")
                // Let it go before the next measurement, native memory too.
                BenchEngine.release(session)
                @Suppress("UNUSED_VALUE")
                session = BenchEngine.newSession(4, 4, 0)
            }
        }
        write("engine-memory", JSONObject().put("screenRows", ROWS).put("maxHeapBytes", Runtime.getRuntime().maxMemory()).put("results", results))
    }

    /** Java heap in use and native heap allocated, once garbage collection has settled. */
    private fun settledMemory(): Pair<Long, Long> {
        val rt = Runtime.getRuntime()
        repeat(4) {
            rt.gc()
            System.runFinalization()
            Thread.sleep(100)
        }
        return (rt.totalMemory() - rt.freeMemory()) to Debug.getNativeHeapAllocatedSize()
    }

    /**
     * A screen like Claude Code's: a conversation with styled text, line drawing, a few symbols and
     * wide characters, an input box and a status line.
     */
    private fun styledScreen(): ByteArray {
        val e = "\u001b"
        val sb = StringBuilder("$e[2J$e[H")
        val r = java.util.Random(11)
        for (row in 1..RENDER_ROWS) {
            sb.append("$e[$row;1H")
            when {
                row == RENDER_ROWS - 4 -> sb.append("$e[38;5;244m╭${"─".repeat(RENDER_COLUMNS - 2)}╮$e[0m")
                row == RENDER_ROWS - 3 -> sb.append("$e[38;5;244m│$e[0m > fix the flaky test in 日本語 module$e[${RENDER_COLUMNS}G$e[38;5;244m│$e[0m")
                row == RENDER_ROWS - 2 -> sb.append("$e[38;5;244m╰${"─".repeat(RENDER_COLUMNS - 2)}╯$e[0m")
                row == RENDER_ROWS - 1 -> sb.append("  $e[38;2;215;119;87m⏵⏵ accept edits on$e[0m $e[2m(shift+tab to cycle)$e[0m")
                row == RENDER_ROWS -> sb.append("$e[38;2;215;119;87m✻$e[0m Thinking… $e[2m(12s · ↑ 1.2k tokens · esc to interrupt)$e[0m")
                row % 7 == 1 -> sb.append("$e[38;2;120;200;120m⏺$e[0m $e[1mUpdate$e[0m(src/main/Terminal.kt) ✅ 🚀")
                row % 7 == 2 -> sb.append("  ⎿  $e[48;2;40;80;40m$e[38;2;200;255;200m+ val rows = emulator.screen.activeTranscriptRows$e[0m")
                row % 7 == 3 -> sb.append("     $e[48;2;80;40;40m$e[38;2;255;200;200m- val rows = 0$e[0m")
                else -> {
                    var width = 0
                    while (width < RENDER_COLUMNS - 10) {
                        if (r.nextInt(5) == 0) sb.append("$e[38;5;${r.nextInt(256)}m")
                        if (r.nextInt(9) == 0) sb.append("$e[1m")
                        val n = 1 + r.nextInt(8)
                        repeat(n) { sb.append('a' + r.nextInt(26)) }
                        sb.append("$e[0m ")
                        width += n + 1
                    }
                }
            }
        }
        return sb.toString().toByteArray()
    }

    /** A screen full of plain text. */
    private fun plainScreen(): ByteArray {
        val r = java.util.Random(12)
        val sb = StringBuilder("\u001b[2J\u001b[H")
        for (row in 1..RENDER_ROWS) {
            sb.append("\u001b[$row;1H")
            repeat(RENDER_COLUMNS) { sb.append(if (r.nextInt(6) == 0) ' ' else 'a' + r.nextInt(26)) }
        }
        return sb.toString().toByteArray()
    }

    private fun write(name: String, json: JSONObject) {
        json.put("engine", BenchEngine.NAME)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.joinToString()}")
        val dir = File(context.getExternalFilesDir(null), "bench").apply { mkdirs() }
        val file = File(dir, "$name-${BenchEngine.NAME}.json")
        file.writeText(json.toString(2) + "\n")
        log("wrote ${file.absolutePath}\n${json.toString(2)}")
    }

    private fun log(message: String) {
        message.lines().forEach { Log.i(TAG, it) }
    }

    private fun round(v: Double) = Math.round(v * 100) / 100.0

    private companion object {
        const val TAG = "RexBench"
        const val COLUMNS = 100
        const val ROWS = 40
        const val HISTORY_ROWS = 4_000
        const val WARMUP_PASSES = 2
        const val PARSE_PASSES = 7

        /** A phone held upright: 60 columns of 18 px across 1080 px. */
        const val RENDER_COLUMNS = 60
        const val RENDER_ROWS = 40
        const val SCREEN_WIDTH_PX = 1080
        const val SCREEN_HEIGHT_PX = 2400
        const val TEXT_SIZE_PX = 30
        const val RENDER_WARMUP = 100
        const val RENDER_FRAMES = 500

        const val MEMORY_COLUMNS = 150
    }
}
