package com.jeffarese.rexmobile.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

class WheelPacerTest {
    @Test
    fun claudeLinesFollowItsArithmetic() {
        // Measured live: 4 writes of n events, 120 ms apart, scrolled 4n lines for n up to 4, then 6, 8 and 13 per write.
        assertEquals(listOf(1, 2, 3, 4, 6, 8, 10, 13, 16, 19), (1..10).map { WheelPacer.claudeLines(it) })
        assertEquals(listOf(1, 2, 3, 4, 4, 5, 5, 6, 6, 7, 7, 7, 8), (1..13).map { WheelPacer.claudeEventsFor(it, 8) })
        assertEquals(6, WheelPacer.claudeEventsFor(100, 6))
        assertEquals(1, WheelPacer.claudeEventsFor(0, 8))
    }

    @Test
    fun batchesArePacedOneAtATime() {
        val p = WheelPacer(intervalMs = 50, maxEvents = 8)
        p.add(12, backlog = 40)
        // 7 events scroll 10 lines, plus one that Claude Code drops as the first of a new direction.
        assertEquals(8, p.next(now = 1000, busy = false))
        assertEquals(2, p.pending)
        assertEquals(0, p.next(now = 1020, busy = false))
        assertEquals(30, p.waitMs(1020))
        assertEquals(0, p.next(now = 1060, busy = true))
        assertEquals(2, p.next(now = 1060, busy = false))
        assertEquals(0, p.pending)
        assertEquals(-1, p.waitMs(1060))

        // Turning back costs an extra event.
        p.add(-3, backlog = 40)
        assertEquals(-4, p.next(now = 1200, busy = false))
        p.add(-2, backlog = 40)
        assertEquals(-2, p.next(now = 1260, busy = false))
    }

    @Test
    fun otherProgramsGetAnEventPerLine() {
        val p = WheelPacer(intervalMs = 50, maxEvents = 8)
        p.claude = false
        p.add(10, backlog = 40)
        assertEquals(8, p.next(1000, false))
        assertEquals(2, p.next(1050, false))
        p.add(-1, backlog = 40)
        assertEquals(-1, p.next(1100, false))
    }

    @Test
    fun theBacklogIsCappedAndCanBeDropped() {
        val p = WheelPacer()
        p.add(100, backlog = 20)
        assertEquals(20, p.pending)
        p.add(-50, backlog = 20)
        assertEquals(-20, p.pending)
        assertTrue(p.active(0))
        assertTrue(p.clear())
        assertFalse(p.clear())
        assertEquals(0, p.next(0, false))
    }

    // ---- Against Claude Code's own wheel arithmetic ----

    /**
     * Claude Code 2.1.311's wheel handling in a native terminal (not xterm.js, JetBrains or a
     * high-rate wheel), ported from its bundled source: rows to scroll for each wheel event.
     */
    private class ClaudeWheel(private val base: Double = 1.0) {
        var time = 0.0
        var mult = base
        var dir = 0
        var pendingFlip = false
        var wheelMode = false
        var burstCount = 0
        var wheelModeSeen = false

        fun event(x: Int, now: Double): Int {
            if (wheelMode && now - time > 1500) {
                wheelMode = false; burstCount = 0; mult = base
            }
            if (pendingFlip) {
                pendingFlip = false
                if (x != dir || now - time > 200) {
                    dir = x; time = now; mult = base
                    return max(1, floor(mult).toInt())
                }
                wheelMode = true
                wheelModeSeen = true
            }
            val gap = now - time
            if (x != dir && dir != 0) {
                pendingFlip = true; time = now
                return 0
            }
            dir = x; time = now
            if (wheelMode) {
                if (gap < 5) {
                    if (++burstCount >= 5) {
                        wheelMode = false; burstCount = 0; mult = base
                    } else {
                        return 1
                    }
                } else {
                    burstCount = 0
                }
            }
            if (wheelMode) {
                val decay = 0.5.pow(gap / 150)
                val cap = max(15 * min(base, 1.0), base * 2)
                mult = minOf(cap, 1 + (mult - 1) * decay + 15 * decay, mult + 3)
                return max(1, floor(mult).toInt())
            }
            if (gap > 40) mult = base else mult = min(max(6 * min(base, 1.0), base * 2), mult + 0.3)
            return max(1, floor(mult).toInt())
        }
    }

    /**
     * Run a gesture: [linesAt] gives the lines the finger moved in each 8 ms frame. Batches reach
     * Claude Code after [latency] ms plus up to [jitter] ms, and are answered a round trip after
     * they were sent. Returns the lines asked for and the lines Claude Code scrolled.
     */
    private fun run(
        linesAt: (frame: Int) -> Int,
        frames: Int,
        latency: Double = 20.0,
        jitter: Double = 0.0,
        claude: ClaudeWheel = ClaudeWheel().apply { dir = 1 },
        seed: Int = 1,
    ): Triple<Int, Int, ClaudeWheel> {
        val random = Random(seed)
        val pacer = WheelPacer()
        var asked = 0
        var scrolled = 0
        var answeredAt = 0.0
        var lastArrival = 0.0
        val end = frames + 400 // let the backlog drain
        for (frame in 0 until end) {
            val now = frame * 8L + 10_000
            if (frame < frames) {
                val lines = linesAt(frame)
                asked += lines
                pacer.add(lines, backlog = 40)
            }
            val events = pacer.next(now, busy = now < answeredAt)
            if (events != 0) {
                // TCP keeps batches in order, so a late one holds back the next.
                val arrival = max(lastArrival, now + latency + random.nextDouble() * jitter)
                lastArrival = arrival
                repeat(abs(events)) { scrolled += Integer.signum(events) * claude.event(Integer.signum(events), arrival) }
                answeredAt = now + 2 * latency
            }
        }
        return Triple(asked, scrolled, claude)
    }

    /** A finger moving [total] lines over [frames] frames, evenly. */
    private fun even(total: Int, frames: Int): (Int) -> Int = { f -> (total * (f + 1)) / frames - (total * f) / frames }

    @Test
    fun aSlowDragScrollsAsFarAsTheFinger() {
        val (asked, scrolled, claude) = run(even(-12, 120), 120)
        assertEquals(-12, asked)
        assertEquals(asked, scrolled)
        assertFalse(claude.wheelModeSeen)
    }

    @Test
    fun aFastDragScrollsAsFarAsTheFinger() {
        // 30 lines in 160 ms: 190 lines a second.
        val (asked, scrolled, claude) = run(even(-30, 20), 20)
        assertEquals(asked, scrolled)
        assertFalse(claude.wheelModeSeen)
    }

    @Test
    fun turningBackAndForthNeverStartsWheelMode() {
        // Up 3, down 1, up 2, down 1, up 4 lines, a few frames each: what a wobbly thumb does.
        val moves = listOf(-1, -1, -1, 0, 1, 0, -1, -1, 0, 1, 0, -1, -1, -1, -1)
        val (asked, scrolled, claude) = run({ moves[it] }, moves.size)
        assertFalse("wheel mode would scroll up to 15 lines an event", claude.wheelModeSeen)
        assertEquals(asked, scrolled)
    }

    @Test
    fun aFirstScrollAgainstAnUnknownDirectionIsAtMostALineOff() {
        for (startDir in listOf(-1, 0, 1)) {
            val (asked, scrolled, claude) = run(even(-8, 40), 40, claude = ClaudeWheel().apply { dir = startDir })
            assertFalse(claude.wheelModeSeen)
            assertTrue("from $startDir: asked $asked, scrolled $scrolled", abs(scrolled - asked) <= 1)
        }
    }

    @Test
    fun jitterOnASlowLinkStaysClose() {
        // 60 ms each way with up to 30 ms of jitter: the next batch waits for the last one's answer.
        for (seed in 1..20) {
            val (asked, scrolled, claude) = run(even(-40, 50), 50, latency = 60.0, jitter = 30.0, seed = seed)
            assertFalse(claude.wheelModeSeen)
            assertTrue("seed $seed: asked $asked, scrolled $scrolled", abs(scrolled - asked) <= 2)
        }
    }

    @Test
    fun sendingEachLineAtOnceRacesAhead() {
        // What the view did before: an event per line as the finger crossed it, on a fast link.
        val claude = ClaudeWheel().apply { dir = -1 }
        val frames = 20
        val lines = even(-30, frames)
        var scrolled = 0
        for (f in 0 until frames) repeat(abs(lines(f))) { scrolled -= claude.event(-1, f * 8.0 + 20) }
        assertTrue("30 lines of finger scrolled $scrolled", scrolled <= -60)
    }
}
