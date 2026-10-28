package com.jeffarese.rexmobile.terminal

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import com.jeffarese.rexmobile.rex.ControlConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Scrolls Claude Code's fullscreen mode on a real Rex server. By default it drags and flings a real
 * TerminalView over a long reply and checks that Claude Code scrolls about as far as the finger
 * moved. REX_LIVE_SCROLL_PART picks other parts, comma-separated: `gestures-old` (the view sending
 * each line at once), `claude` (lines per wheel event, bursts, taps), `model` (its acceleration)
 * and `transport` (round trips of writes and block.event, awaited and pipelined).
 * REX_LIVE_RENDERER=classic starts Claude Code in its classic renderer instead, which leaves the
 * conversation in the terminal's history; the `history` part scrolls that. `switch` restarts a
 * fullscreen Claude Code in the classic renderer the way the phone's banner does; `switch,history`
 * then scrolls what that left.
 * Skipped unless REX_LIVE_HOST is set. Runs Haiku once in a fresh directory (REX_LIVE_WORKDIR, or a
 * temporary one) and destroys its session at the end. Results are printed, and written to
 * REX_LIVE_WORKDIR/scroll-results.txt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveScrollTest {
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    private var terminal: RemoteTerminal? = null
    /** The view the terminal has between the parts that draw it in one of their own, so its screen is read. */
    private var shown: TerminalViewport? = null
    private val out = StringBuilder()
    private lateinit var host: HostConnection
    private lateinit var sid: String
    private lateinit var bid: String
    private val columns = 50
    private val rows = 40

    private fun log(s: String) {
        println(s)
        out.append(s).append('\n')
    }

    @Test
    fun claudeCodeFullscreenScrolling() {
        val address = System.getenv("REX_LIVE_HOST")
        assumeTrue("set REX_LIVE_HOST to run", !address.isNullOrBlank())
        val workRoot = System.getenv("REX_LIVE_WORKDIR")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        host = HostConnection(HostConfig(label = "live", url = ControlConnection.normalizeHostUrl(address!!)), http, scope) {}
        runBlocking {
            host.connect()
            withTimeout(20_000) { host.state.first { it.status == ConnStatus.ONLINE } }
            sid = host.newSession("rex-mobile-scroll-itest")!!
            val view = withTimeout(10_000) {
                host.state.first { st -> st.views[sid]?.windows?.firstOrNull()?.blocks?.isNotEmpty() == true }.views[sid]!!
            }
            bid = view.windows!!.first().blocks.first().blockId
        }
        try {
            runBlocking {
                host.ownBlock(sid, bid)
                host.blockMethod(sid, bid, "resize", buildJsonObject {
                    put("columns", columns)
                    put("rows", rows)
                })
            }
            val t = RemoteTerminal(host, sid, bid, http).also { terminal = it }
            shown = showOnScreen(t)
            t.start()
            waitFor("the first screen") { !t.syncing.value && screen().any { it.isNotBlank() } }
            pump(800)
            val part = System.getenv("REX_LIVE_SCROLL_PART") ?: "gestures"
            val parts = part.split(",")
            if ("transport" in parts) quietShellTransport()

            val dir = workRoot?.let { "mkdir -p '$it' && cd \"$(mktemp -d '$it/run.XXXXXX')\"" } ?: "cd \"$(mktemp -d)\""
            val renderer = if (System.getenv("REX_LIVE_RENDERER") == "classic") "CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1 " else ""
            t.typeText("$dir && ${renderer}command claude --model claude-haiku-5-5\r")
            waitFor("Claude Code's footer", 60_000) { info().claudeMode != null }
            val emu = t.session.emulator!!
            log("modes: alt=${emu.isAlternateBufferActive} mouse=${emu.isMouseTrackingActive}")

            t.paste("Print the numbers from 1 to 300, one per line, as plain text and nothing else. Don't use any tools.")
            t.sendKey(RemoteKey.ENTER)
            waitFor("the reply", 120_000) { text().lines().any { it.trim() == "300" } && !info().interruptible }
            pump(2000)
            log("screen at the bottom:\n${text()}")
            log("first number at the bottom: ${firstNumber()}")

            for (p in parts) when (p) {
                "transport" -> transport()
                "model" -> accelerationModel()
                "gestures" -> gestures(withSink = true)
                "gestures-old" -> gestures(withSink = false)
                "history" -> history()
                "switch" -> switchToClassic()
                else -> claudeBehaviour()
            }
            log("modes at end: alt=${emu.isAlternateBufferActive} mouse=${emu.isMouseTrackingActive}")
        } catch (e: Throwable) {
            log("screen at failure:\n${runCatching { text() }.getOrNull()}")
            throw e
        } finally {
            terminal?.let { t ->
                if (info().claudeMode != null) {
                    t.sendKey(RemoteKey.ESCAPE)
                    pump(500)
                    t.paste("/exit")
                    t.sendKey(RemoteKey.ENTER)
                    pump(2500)
                }
                log("exited claude: ${info().claudeMode == null}")
                t.close()
            }
            runBlocking {
                runCatching { host.call("session.destroy", buildJsonObject { put("session_id", sid) }) }
                    .onSuccess { log("destroyed $sid") }
                    .onFailure { log("destroy failed: $it") }
                host.disconnect()
            }
            scope.cancel()
            activity?.let { runCatching { it.pause().stop().destroy() } }
            workRoot?.let { runCatching { File(it, "scroll-results.txt").writeText(out.toString()) } }
        }
    }

    private val midCol get() = columns / 2
    private val midRow get() = rows / 2

    private fun wheelBytes(up: Boolean, n: Int = 1) = buildString { repeat(n) { append("\u001b[<${if (up) 64 else 65};$midCol;${midRow}M") } }

    /** Lines per wheel event, bursts, coalesced writes, and taps. */
    private fun claudeBehaviour() {

        // 1. Single events, spaced out, as typed bytes.
        log("\n== single wheel events as typed bytes (write), 700 ms apart")
        repeat(6) { measure("bytes up #$it") { write(wheelBytes(true)) } }
        repeat(3) { measure("bytes down #$it") { write(wheelBytes(false)) } }

        // 2. Single events as block.event wheel events.
        log("\n== single block.event wheel events, 700 ms apart")
        repeat(6) { measure("event up #$it") { wheelEvent(true) } }
        repeat(3) { measure("event down #$it") { wheelEvent(false) } }

        toBottom()
        // 3. Bursts: back to back, spaced like a drag, and coalesced into one write.
        log("\n== bursts")
        measure("10 writes back to back, up") { repeat(10) { write(wheelBytes(true)) } }
        toBottom()
        measure("10 writes 30 ms apart, up") { repeat(10) { write(wheelBytes(true)); spin(30) } }
        toBottom()
        measure("10 writes 100 ms apart, up") { repeat(10) { write(wheelBytes(true)); spin(100) } }
        toBottom()
        measure("1 write of 10 events, up") { write(wheelBytes(true, 10)) }
        toBottom()
        measure("1 write of 3 events, up") { write(wheelBytes(true, 3)) }
        toBottom()
        measure("10 block.events back to back, up") { repeat(10) { wheelEvent(true) } }
        toBottom()
        measure("10 block.events in parallel, up") {
            runBlocking { (1..10).map { async(Dispatchers.Default) { wheelEvent(true) } }.awaitAll() }
        }
        toBottom()
        measure("1 write of 40 events, up") { write(wheelBytes(true, 40)) }
        measure("1 write of 40 events, down") { write(wheelBytes(false, 40)) }

        // 4. Turnaround while events keep coming: send 20 writes 16 ms apart and log every redraw.
        toBottom()
        log("\n== 20 writes 16 ms apart, every redraw")
        trace { repeat(20) { write(wheelBytes(true)); spin(16) } }
        toBottom()

        // 5. A tap: left press and release in the transcript, then on the input line.
        log("\n== taps")
        write(wheelBytes(true, 5))
        settle()
        for ((label, row) in listOf("transcript" to 10, "input" to rows - 4)) {
            val before = screen()
            write("\u001b[<0;10;${row}M\u001b[<0;10;${row}m")
            settle()
            val after = screen()
            log("tap on $label row $row: ${diff(before, after)}")
        }
        toBottom()
    }

    private var activity: org.robolectric.android.controller.ActivityController<androidx.activity.ComponentActivity>? = null

    /**
     * Drag and fling a real TerminalView showing the live session, as a finger would, and compare
     * how far Claude Code scrolled with how far the finger moved. [withSink] hands the view to the
     * RemoteTerminal, which paces wheel events; without it the view sends each line at once.
     */
    private fun gestures(withSink: Boolean) {
        val t = terminal!!
        // Animation frames on the clock as it keeps up with real time, rather than run to the end at once.
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val a = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().also { activity = it }
        val view = com.jeffarese.rexmobile.terminal.view.TerminalView(a.get(), null)
        view.setTerminalViewClient(NoClient())
        view.setTypeface(android.graphics.Typeface.MONOSPACE)
        view.setTextSize(24)
        view.attachSession(t.session)
        val w = kotlin.math.ceil(columns * view.fontWidth).toInt() + 2
        val h = rows * view.fontLineSpacing + view.fontLineSpacingAndAscent + 2
        a.get().setContentView(android.widget.FrameLayout(a.get()).apply { addView(view, android.widget.FrameLayout.LayoutParams(w, h)) })
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        if (withSink) t.view = view
        realtime(300)
        val lh = view.fontLineSpacing.toFloat()
        val slop = android.view.ViewConfiguration.get(a.get()).scaledTouchSlop
        log("\n== gestures ${if (withSink) "through the paced sink" else "sent at once (as before)"}: line height ${lh}px, touch slop ${slop}px")

        val x = w / 2f
        var downTime = 0L
        fun touch(action: Int, y: Float) {
            val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            view.dispatchTouchEvent(e)
            e.recycle()
        }
        /** Move the finger through [path] (pixels from the start, one per 8 ms frame), then hold or lift with speed. */
        fun gesture(label: String, path: List<Float>, fling: Boolean, after: (() -> Unit)? = null): Int {
            toBottom()
            write(wheelBytes(true)) // Leave Claude Code going up, as after reading back a little.
            settleRealtime()
            val n0 = firstNumber()
            val y0 = 200f
            val t0 = System.nanoTime()
            downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, y0)
            val redraws = mutableListOf<Double>()
            // How far Claude Code's text is from where the finger took it, in lines, sampled while the finger moves.
            var fingerPx = 0f
            var moving = false
            val lag = mutableListOf<Float>()
            var last = screen()
            fun watch(ms: Long) {
                val until = System.nanoTime() + ms * 1_000_000
                while (System.nanoTime() < until) {
                    tick()
                    val now = screen()
                    if (now != last) {
                        last = now
                        redraws += (System.nanoTime() - t0) / 1e6
                    }
                    val n = firstNumber()
                    if (moving && n0 != null && n != null) lag += maxOf(0f, fingerPx - slop) / lh - (n0 - n)
                }
            }
            moving = true
            for (dy in path) {
                watch(8)
                touch(MotionEvent.ACTION_MOVE, y0 + dy)
                fingerPx = dy
            }
            moving = false
            if (!fling) {
                // Hold still before lifting, so there is no fling.
                repeat(15) {
                    watch(8)
                    touch(MotionEvent.ACTION_MOVE, y0 + path.last())
                }
            }
            touch(MotionEvent.ACTION_UP, y0 + path.last())
            val lift = (System.nanoTime() - t0) / 1e6
            after?.invoke()
            // Until the screen has been still for 600 ms.
            while (true) {
                val before = redraws.size
                watch(600)
                if (redraws.size == before) break
            }
            val n1 = firstNumber()
            val finger = path.last() / lh
            val scrolled = if (n0 != null && n1 != null) n0 - n1 else null
            val lastRedraw = redraws.lastOrNull() ?: 0.0
            log("%-34s finger %5.1f lines, Claude scrolled %-4s in %3d redraws; last redraw %4.0f ms after lift (lift at %4.0f ms)".format(
                label, finger, scrolled, redraws.size, lastRedraw - lift, lift))
            if (lag.isNotEmpty()) log("  behind the finger, in lines: mean %.2f, max %.2f".format(
                lag.map { kotlin.math.abs(it) }.average(), lag.maxOf { kotlin.math.abs(it) }))
            return scrolled ?: Int.MIN_VALUE
        }
        fun linear(lines: Float, ms: Int): List<Float> {
            val frames = ms / 8
            return (1..frames).map { lines * lh * it / frames }
        }
        // The slop takes about half a line off each drag.
        val slow = gesture("slow drag, 10 lines in 1 s", linear(10f, 1000), fling = false)
        val medium = gesture("medium drag, 10 lines in 300 ms", linear(10f, 300), fling = false)
        val fast = gesture("fast drag, 20 lines in 200 ms", linear(20f, 200), fling = false)
        val wobble = linear(6f, 300) + linear(-2f, 96).map { it + 6 * lh } + linear(6f, 300).map { it + 4 * lh }
        val wobbly = gesture("wobble: 6 down, 2 up, 6 down", wobble, fling = false)
        val flung = gesture("fling: 6 lines in 48 ms, lift", linear(6f, 48), fling = true)
        val stopped = gesture("fling, then a tap 150 ms later", linear(6f, 48), fling = true) {
            realtime(150)
            val before = firstNumber()
            downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, 300f)
            realtime(40)
            touch(MotionEvent.ACTION_UP, 300f)
            log("  tapped with top at $before")
        }
        if (withSink) t.view = shown?.terminalView
        toBottom()
        if (withSink) {
            // Robolectric's velocity tracker sees a little fling after a held finger, hence some slack.
            assertTrue("slow drag: $slow", slow in 8..12)
            assertTrue("medium drag: $medium", medium in 8..12)
            assertTrue("fast drag: $fast", fast in 18..24)
            assertTrue("wobble: $wobbly", wobbly in 7..12)
            assertTrue("fling: $flung", flung in 6..3 * rows)
            assertTrue("a tap stops a fling: $stopped of $flung", stopped < flung)
        }
    }

    /**
     * Claude Code's classic renderer leaves the conversation in the terminal's own history, so the
     * view scrolls it here, with no wheel events and no wait for Claude to redraw. Checks that the
     * reply is in the history whole, that drags and a fling move the text with the finger, and
     * whether the lines being read stay put while Claude prints a second long reply below them.
     */
    private fun history() {
        val t = terminal!!
        fun emu() = t.session.emulator!!
        val numbers = allLines().mapNotNull { number.find(it)?.groupValues?.get(1)?.toInt() }
        log("\n== history: alt=${emu().isAlternateBufferActive} mouse=${emu().isMouseTrackingActive}, ${emu().screen.activeTranscriptRows} rows of history, " +
            "the numbers 1 to 300 once and in order: ${numbers == (1..300).toList()} (${numbers.size} found, ${numbers.distinct().size} distinct)")
        allLines().withIndex().filter { (_, line) -> number.find(line)?.groupValues?.get(1)?.toInt()?.let { n -> numbers.count { it == n } > 1 } == true }
            .forEach { (i, line) -> log("  repeated, row ${i - emu().screen.activeTranscriptRows}: '$line'") }

        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val a = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().also { activity = it }
        val view = com.jeffarese.rexmobile.terminal.view.TerminalView(a.get(), null)
        view.setTerminalViewClient(NoClient())
        view.setTypeface(android.graphics.Typeface.MONOSPACE)
        view.setTextSize(24)
        view.attachSession(t.session)
        val w = kotlin.math.ceil(columns * view.fontWidth).toInt() + 2
        val h = rows * view.fontLineSpacing + view.fontLineSpacingAndAscent + 2
        a.get().setContentView(android.widget.FrameLayout(a.get()).apply { addView(view, android.widget.FrameLayout.LayoutParams(w, h)) })
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        t.view = view
        realtime(300)
        val lh = view.fontLineSpacing.toFloat()
        val slop = android.view.ViewConfiguration.get(a.get()).scaledTouchSlop
        /** How far the text has moved down into history, in pixels, as the view draws it. */
        fun viewPx() = view.getPointY(0).toFloat()
        fun topText() = emu().screen.getSelectedText(0, view.topRow, emu().mColumns - 1, view.topRow).trimEnd()

        val x = w / 2f
        var downTime = 0L
        fun touch(action: Int, y: Float) {
            val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            view.dispatchTouchEvent(e)
            e.recycle()
        }
        fun linear(lines: Float, ms: Int): List<Float> {
            val frames = ms / 8
            return (1..frames).map { lines * lh * it / frames }
        }
        /** Move the finger down through [path] from the live bottom, then hold or lift with speed, and see how the text follows. */
        fun gesture(label: String, path: List<Float>, fling: Boolean) {
            view.jumpToBottom()
            realtime(100)
            val y0 = 200f
            downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, y0)
            // How far the text is from where the finger took it, in lines, as each frame shows it.
            val lag = mutableListOf<Float>()
            for (dy in path) {
                realtime(8)
                touch(MotionEvent.ACTION_MOVE, y0 + dy)
                if (dy > slop) lag += (dy - slop - viewPx()) / lh
            }
            if (!fling) repeat(15) {
                realtime(8)
                touch(MotionEvent.ACTION_MOVE, y0 + path.last())
            }
            val atLift = viewPx()
            touch(MotionEvent.ACTION_UP, y0 + path.last())
            var last = viewPx()
            var frames = 0
            var still = 0
            while (still < 20) {
                realtime(16)
                val now = viewPx()
                if (now == last) still++ else {
                    still = 0
                    frames++
                }
                last = now
            }
            log("%-34s finger %5.1f lines, text %5.1f at lift, %5.1f at rest after %d more frames; behind the finger: mean %.2f, max %.2f lines".format(
                label, path.last() / lh, atLift / lh, last / lh, frames, lag.map { kotlin.math.abs(it) }.average(), lag.maxOf { kotlin.math.abs(it) }))
        }
        gesture("slow drag, 10 lines in 1 s", linear(10f, 1000), fling = false)
        gesture("medium drag, 10 lines in 300 ms", linear(10f, 300), fling = false)
        gesture("fast drag, 20 lines in 200 ms", linear(20f, 200), fling = false)
        gesture("fling: 6 lines in 48 ms, lift", linear(6f, 48), fling = true)

        // Read two screens up while Claude prints another long reply below, as when the desk asks it something.
        view.jumpToBottom()
        view.setTopRow(-2 * rows)
        realtime(200)
        val held = topText()
        log("reading ${view.scrollbackRows} rows above the bottom, top row '$held'")
        t.paste("Now print the numbers from 301 to 450, one per line, as plain text and nothing else. Don't use any tools.")
        t.sendKey(RemoteKey.ENTER)
        var changes = 0
        var shrank = 0
        var lastText = held
        var lastHistory = emu().screen.activeTranscriptRows
        val frame = android.graphics.Canvas(android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888))
        val until = System.nanoTime() + 120_000_000_000L
        while (System.nanoTime() < until) {
            realtime(16)
            // Draw every frame, as while a finger scrolls, and read the top row as drawn.
            view.draw(frame)
            val history = emu().screen.activeTranscriptRows
            if (history < lastHistory) shrank++
            lastHistory = history
            val text = topText()
            if (text != lastText) {
                changes++
                lastText = text
            }
            if (!info().interruptible && allLines().any { it.trim() == "450" }) break
        }
        realtime(1500)
        val after = allLines().mapNotNull { number.find(it)?.groupValues?.get(1)?.toInt() }
        log("while Claude printed 150 more lines: the top row changed $changes times and the history shrank $shrank times; " +
            "top row now '${topText()}', ${view.scrollbackRows} rows above the bottom; the numbers 1 to 450 once and in order: ${after == (1..450).toList()} " +
            "(${after.size} found, ${after.distinct().size} distinct)")

        // The desk takes the block back at its own width, then the phone again, as switching between them does.
        for (cols in listOf(100, columns)) {
            runBlocking {
                host.blockMethod(sid, bid, "resize", buildJsonObject {
                    put("columns", cols)
                    put("rows", rows)
                })
            }
            settleRealtime()
            val now = allLines().mapNotNull { number.find(it)?.groupValues?.get(1)?.toInt() }
            log("resized to $cols columns: ${emu().screen.activeTranscriptRows} rows of history, the numbers 1 to 450 once and in order: ${now == (1..450).toList()} " +
                "(${now.size} found, ${now.distinct().size} distinct); top row '${topText()}', ${view.scrollbackRows} rows above the bottom")
            val repeats = now.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            allLines().withIndex().filter { (_, line) -> number.find(line)?.groupValues?.get(1)?.toInt() in repeats }
                .forEach { (i, line) -> log("  repeated, row ${i - emu().screen.activeTranscriptRows}: '$line'") }
            // The server's own terminal, to tell what Claude printed from how this emulator reflowed it.
            val server = runBlocking { host.blockMethod(sid, bid, "format", buildJsonObject { put("format", "text") }) }
                .jsonObject.getValue("content").jsonPrimitive.content
                .lines().mapNotNull { number.find(it)?.groupValues?.get(1)?.toInt() }
            log("  the server's terminal: ${server.size} numbers, ${server.distinct().size} distinct, repeated ${server.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}")
        }
        log("the end of the history and screen:\n" + allLines().takeLast(rows + 12).joinToString("\n"))
        // No toBottom(): without mouse reporting, its wheel bytes would be typed into Claude's input.
        t.view = shown?.terminalView
    }

    /** Restarts Claude Code in its classic renderer as the phone's banner does, and checks what came back. */
    private fun switchToClassic() {
        val t = terminal!!
        fun emu() = t.session.emulator!!
        val before = info()
        log("\n== switch: alt=${emu().isAlternateBufferActive} mouse=${emu().isMouseTrackingActive} fullscreen=${before.fullscreen}, " +
            "mode=${before.claudeMode}, effort=${before.claudeEffort}, input='${before.claudeInput}'")
        val started = SystemClock.uptimeMillis()
        var last: ClassicSwitch.State? = null
        t.classicSwitch.start()
        waitFor("the switch", 120_000) {
            val state = t.classicSwitch.state.value
            if (state != last) {
                log("  ${SystemClock.uptimeMillis() - started} ms: $state")
                last = state
            }
            state is ClassicSwitch.State.Failed || state == ClassicSwitch.State.Idle && SystemClock.uptimeMillis() - started > 1000
        }
        pump(2000)
        val after = info()
        val numbers = allLines().mapNotNull { number.find(it)?.groupValues?.get(1)?.toInt() }
        log("after: alt=${emu().isAlternateBufferActive} mouse=${emu().isMouseTrackingActive} fullscreen=${after.fullscreen}, " +
            "mode=${after.claudeMode}, effort=${after.claudeEffort}, input='${after.claudeInput}'; ${emu().screen.activeTranscriptRows} rows of history, " +
            "the numbers 1 to 300 in order: ${numbers.distinct() == (1..300).toList()} (${numbers.size} found, ${numbers.distinct().size} distinct)")
        log("screen after the switch:\n${text()}")
        assertTrue("the switch: ${t.classicSwitch.state.value}", t.classicSwitch.state.value == ClassicSwitch.State.Idle)
        assertTrue("classic after the switch", !emu().isAlternateBufferActive && !after.fullscreen)
        assertTrue("the mode came back", after.claudeMode == before.claudeMode)
    }

    /** Every row of history and screen, oldest first. */
    private fun allLines(): List<String> {
        val e = terminal!!.session.emulator ?: return emptyList()
        val last = e.mColumns - 1
        return (-e.screen.activeTranscriptRows until e.mRows).map { e.screen.getSelectedText(0, it, last, it).trimEnd() }
    }

    private class NoClient : com.jeffarese.rexmobile.terminal.view.TerminalViewClient {
        override fun onScale(scale: Float) = 1f
        override fun onSingleTapUp(e: MotionEvent) = Unit
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = false
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: com.jeffarese.rexmobile.terminal.emulator.TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = true
        override fun readControlKey() = false
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: com.jeffarese.rexmobile.terminal.emulator.TerminalSession) = false
        override fun onEmulatorSet() = Unit
        override fun onScrollbackChanged(rows: Int) = Unit
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    /** Let [ms] of real time pass, with the main looper and its clock keeping up. */
    private fun realtime(ms: Long) {
        val until = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < until) tick()
    }

    /** Wait until the screen stops changing for 400 ms, in real time. */
    private fun settleRealtime() {
        var last = screen()
        var stillSince = System.nanoTime()
        val until = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < until) {
            tick()
            val now = screen()
            if (now != last) {
                last = now
                stillSince = System.nanoTime()
            } else if (System.nanoTime() - stillSince > 400_000_000L) {
                return
            }
        }
    }

    /** Check a model of Claude Code's wheel acceleration: spacing of single events, and N events per write. */
    private fun accelerationModel() {
        fun fresh() {
            toBottom()
            write(wheelBytes(true)) // The first event after a change of direction scrolls nothing.
            settle()
        }
        log("\n== single events by spacing (12 events)")
        for (gap in listOf(100L, 60L, 45L, 30L, 20L, 10L)) {
            fresh()
            measure("12 events $gap ms apart") { repeat(12) { write(wheelBytes(true)); spin(gap) } }
        }
        log("\n== N events in one write, 4 writes 120 ms apart")
        for (n in listOf(1, 2, 3, 4, 5, 6, 8)) {
            fresh()
            measure("4 x $n events") { repeat(4) { write(wheelBytes(true, n)); spin(120) } }
        }
        log("\n== a flip and back within 200 ms, then 10 events 60 ms apart")
        fresh()
        measure("up, down, up, then 10 ups") {
            write(wheelBytes(true)); spin(60)
            write(wheelBytes(false)); spin(60)
            write(wheelBytes(true)); spin(60)
            repeat(10) { write(wheelBytes(true)); spin(60) }
        }
        toBottom()
    }

    /** Call latency against spacing, to a program that prints nothing back. */
    private fun quietShellTransport() {
        val t = terminal!!
        t.typeText("stty -echo; cat > /dev/null\r")
        pump(800)
        log("\n== quiet program (cat > /dev/null): call latency by spacing, awaited")
        for (gap in listOf(0L, 16L, 30L, 60L, 100L, 250L)) {
            latencies("write, $gap ms apart", 12, gap) { write("a") }
            latencies("block.event wheel, $gap ms apart", 12, gap) { wheelEvent(true) }
        }
        write("\u0003")
        pump(300)
        t.typeText("stty echo; clear\r")
        pump(800)
    }

    /** Round trips while Claude Code redraws: awaited and pipelined, writes and block.events. */
    private fun transport() {
        log("\n== Claude: awaited calls, every redraw")
        for (gap in listOf(16L, 30L, 60L, 100L)) {
            toBottom()
            log("-- writes, $gap ms apart")
            trace { latencies("write", 12, gap) { write(wheelBytes(true)) } }
            toBottom()
            log("-- block.events, $gap ms apart")
            trace { latencies("block.event", 12, gap) { wheelEvent(true) } }
        }
        log("\n== Claude: pipelined calls (sent without waiting for the reply), every redraw")
        for (gap in listOf(16L, 30L, 50L)) {
            toBottom()
            log("-- block.events pipelined, $gap ms apart")
            trace { pipelined(12, gap) { wheelEventCall(true) } }
            toBottom()
            log("-- writes pipelined, $gap ms apart")
            trace { pipelined(12, gap) { writeCall(wheelBytes(true)) } }
        }
        toBottom()
    }

    /** Make [n] calls [gap] ms apart, each awaited, and log their round trips. */
    private fun latencies(label: String, n: Int, gap: Long, call: () -> Unit) {
        val times = (1..n).map {
            val t0 = System.nanoTime()
            call()
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            spin(gap)
            ms
        }
        log("  %-34s round trip: median %6.1f ms, max %6.1f ms  [%s]".format(label, times.sorted()[n / 2], times.max(), times.joinToString(" ") { "%.0f".format(it) }))
    }

    /** Start [n] calls [gap] ms apart without waiting for each reply, in order, and log when each reply came. */
    private fun pipelined(n: Int, gap: Long, call: suspend () -> Unit) {
        val t0 = System.nanoTime()
        val done = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, Double>>()
        val scope = CoroutineScope(Dispatchers.Default)
        val jobs = (1..n).map { i ->
            val sent = (System.nanoTime() - t0) / 1_000_000.0
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                call()
                done += i to (System.nanoTime() - t0) / 1_000_000.0 - sent
            }
            spin(gap)
            job
        }
        runBlocking { jobs.forEach { it.join() } }
        val rt = done.map { it.second }.sorted()
        log("  pipelined round trip: median %6.1f ms, max %6.1f ms, sending took %.0f ms".format(rt[rt.size / 2], rt.last(), (System.nanoTime() - t0) / 1_000_000.0))
    }

    private suspend fun writeCall(data: String) {
        host.blockMethod(sid, bid, "write", buildJsonObject { put("data", data) })
    }

    private suspend fun wheelEventCall(up: Boolean) {
        host.call("block.event", buildJsonObject {
            put("session_id", sid)
            put("block_id", bid)
            putJsonObject("event") {
                put("type", "wheel")
                putJsonObject("payload") {
                    put("x", columns / 2 - 1)
                    put("y", rows / 2 - 1)
                    put("delta_y", if (up) -1 else 1)
                    put("delta_mode", "line")
                }
            }
        })
    }

    private fun write(data: String) = runBlocking {
        host.blockMethod(sid, bid, "write", buildJsonObject { put("data", data) })
    }

    private fun wheelEvent(up: Boolean) = runBlocking {
        host.call("block.event", buildJsonObject {
            put("session_id", sid)
            put("block_id", bid)
            putJsonObject("event") {
                put("type", "wheel")
                putJsonObject("payload") {
                    put("x", columns / 2 - 1)
                    put("y", rows / 2 - 1)
                    put("delta_y", if (up) -1 else 1)
                    put("delta_mode", "line")
                }
            }
        })
    }

    /** Send, then time the call, the first redraw, and the redraw after which the screen stays still. */
    private fun measure(label: String, send: () -> Unit) {
        settle()
        val before = screen()
        val n0 = firstNumber()
        val t0 = System.nanoTime()
        send()
        val sent = (System.nanoTime() - t0) / 1_000_000.0
        var first = -1.0
        var last = -1.0
        var lastScreen = before
        val quietMs = 400
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) {
            tick()
            val now = screen()
            if (now != lastScreen) {
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                if (first < 0) first = ms
                last = ms
                lastScreen = now
            }
            if (last >= 0 && (System.nanoTime() - t0) / 1_000_000.0 - last > quietMs) break
        }
        val n1 = firstNumber()
        val lines = if (n0 != null && n1 != null) n0 - n1 else null
        log("%-36s lines=%-5s calls=%6.1f ms  firstRedraw=%6.1f ms  settled=%6.1f ms  (top %s -> %s)".format(label, lines, sent, first, last, n0, n1))
        pump(300)
    }

    /** Log each redraw with its time while [send] runs and until the screen settles. */
    private fun trace(send: () -> Unit) {
        settle()
        val n0 = firstNumber()
        val t0 = System.nanoTime()
        val th = Thread { send() }.apply { start() }
        var lastScreen = screen()
        var last = 0.0
        val redraws = mutableListOf<String>()
        val until = System.nanoTime() + 6_000_000_000L
        while (System.nanoTime() < until) {
            tick()
            val now = screen()
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            if (now != lastScreen) {
                lastScreen = now
                last = ms
                redraws += "%.0f:%s".format(ms, firstNumber())
            }
            if (!th.isAlive && ms - last > 500) break
        }
        th.join()
        log("  redraws (ms:top) " + redraws.joinToString(" "))
        log("  total lines ${n0?.let { a -> firstNumber()?.let { a - it } }}, last redraw at %.0f ms".format(last))
    }

    private fun toBottom() {
        write("\u001b[<65;${columns / 2};${rows / 2}M".repeat(80))
        settle()
    }

    /** Wait until the screen stops changing for 400 ms. */
    private fun settle() {
        var last = screen()
        var stillSince = System.currentTimeMillis()
        val until = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < until) {
            pump(25)
            val now = screen()
            if (now != last) {
                last = now
                stillSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - stillSince > 400) {
                return
            }
        }
    }

    private val number = Regex("""^\s*(?:⏺\s*)?(\d{1,3})\s*$""")

    /** The first number shown in the transcript, which says how far it is scrolled. */
    private fun firstNumber(): Int? = screen().firstNotNullOfOrNull { number.find(it)?.groupValues?.get(1)?.toInt() }

    private fun diff(a: List<String>, b: List<String>): String {
        if (a == b) return "no change"
        return a.indices.filter { a.getOrNull(it) != b.getOrNull(it) }.joinToString("; ") { "row $it: '${a.getOrNull(it)}' -> '${b.getOrNull(it)}'" }
    }

    private fun info() = terminal!!.screenInfo.value

    private fun screen(): List<String> = terminal!!.session.emulator?.let { ScreenReader.visibleLines(it) }.orEmpty()

    private fun text() = screen().joinToString("\n")

    /** One short step of the main looper, so stream output reaches the emulator within a millisecond or two. */
    private fun tick() {
        spin(1)
        val now = System.nanoTime()
        val ms = (now - lastTick) / 1_000_000
        if (ms > 0) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
            lastTick += ms * 1_000_000
        } else {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private var lastTick = System.nanoTime()

    /**
     * Wait [ms] by spinning: on this Mac, Thread.sleep(16) in a test JVM takes about 110 ms (timer
     * coalescing), which would space out the events far more than asked.
     */
    private fun spin(ms: Long) {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) Thread.onSpinWait()
    }

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
