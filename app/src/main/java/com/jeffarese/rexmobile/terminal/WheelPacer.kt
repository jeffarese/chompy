package com.jeffarese.rexmobile.terminal

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Turns lines to scroll into batches of wheel events for a program that reads the mouse, paced so
 * the program scrolls about as far as the finger moved rather than racing ahead.
 *
 * Claude Code's fullscreen mode (2.1.x, as measured over Rex) scrolls one line per wheel event
 * when events come more than 40 ms apart. Closer together, including events read in one go, each
 * event scrolls 0.3 lines more than the last, up to 6, so 4 events in a row scroll 4 lines but 8
 * scroll 13. It ignores the first event after a change of direction, and if the next event goes
 * back the way it was going within 200 ms, it takes that for a jittery mouse wheel and scrolls up
 * to 15 lines an event for a while. So batches go at least [intervalMs] apart, one unanswered
 * batch at a time, each with as many events as scroll the lines wanted from a fresh start, and
 * with an extra event first when the direction changes, which keeps that wheel mode off.
 *
 * Other programs get one event per line, paced the same way.
 */
class WheelPacer(
    private val intervalMs: Long = 50,
    private val maxEvents: Int = 8,
) {
    /** Whether the program is Claude Code, which speeds up and turns as described above. */
    var claude = true

    /** Lines still to scroll, positive towards the end. */
    var pending = 0
        private set

    private var lastDirection = 0
    private var lastSentAt = Long.MIN_VALUE / 2

    /** Scroll [lines] more, positive towards the end, keeping at most [backlog] lines waiting. */
    fun add(lines: Int, backlog: Int) {
        val cap = max(1, backlog)
        pending = (pending + lines).coerceIn(-cap, cap)
    }

    /** Forget lines not sent yet, as when a touch stops a fling. True if there were some. */
    fun clear(): Boolean {
        val had = pending != 0
        pending = 0
        return had
    }

    /** True while lines wait or a batch went out within the last interval. */
    fun active(now: Long): Boolean = pending != 0 || now - lastSentAt < intervalMs * 3

    /**
     * The wheel events to send now, negative to scroll up, or 0 to wait: for the interval to pass,
     * or for the last batch to be answered while [busy].
     */
    fun next(now: Long, busy: Boolean): Int {
        if (pending == 0 || busy || now - lastSentAt < intervalMs) return 0
        val direction = pending.sign
        val want = abs(pending)
        val events: Int
        val lines: Int
        if (claude) {
            events = claudeEventsFor(want, maxEvents)
            lines = claudeLines(events)
        } else {
            events = min(want, maxEvents)
            lines = events
        }
        pending -= direction * lines
        // Before the first batch the program's last direction is unknown: an extra event costs at
        // most a line, while a lone ignored event followed by a turn back would start wheel mode.
        val turn = claude && direction != lastDirection
        lastDirection = direction
        lastSentAt = now
        return direction * (events + if (turn) 1 else 0)
    }

    /** Milliseconds until [next] may send, or -1 when nothing waits. */
    fun waitMs(now: Long): Long = if (pending == 0) -1 else max(0, lastSentAt + intervalMs - now)

    companion object {
        /** Lines Claude Code scrolls for [events] read together after a pause, as its own arithmetic gives. */
        fun claudeLines(events: Int): Int {
            var mult = 1.0
            var lines = 0
            for (i in 0 until events) {
                if (i > 0) mult = min(6.0, mult + 0.3)
                lines += floor(mult).toInt()
            }
            return lines
        }

        /** The most events, up to [maxEvents], that scroll no more than [lines]; at least one. */
        fun claudeEventsFor(lines: Int, maxEvents: Int): Int {
            var n = 1
            while (n < maxEvents && claudeLines(n + 1) <= lines) n++
            return n
        }
    }
}
