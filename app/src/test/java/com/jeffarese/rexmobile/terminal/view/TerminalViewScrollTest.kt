package com.jeffarese.rexmobile.terminal.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.jeffarese.rexmobile.terminal.emulator.TerminalSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.math.ceil

/** Scrolling a real [TerminalView] with touches, with real font metrics from native graphics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalViewScrollTest {
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var view: TerminalView
    private lateinit var term: TestSession
    private val reported = mutableListOf<Int>()
    /** Taps the view handed to its client, as confirmed single taps. */
    private var taps = 0
    private val columns = 40
    private val rows = 10

    private val lineHeight get() = view.fontLineSpacing

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        view = TerminalView(activity.get(), null)
        view.setTerminalViewClient(Client())
        view.setTypeface(Typeface.MONOSPACE)
        view.setTextSize(30)
        assertTrue("fonts should measure in native graphics mode", lineHeight > 0 && view.fontWidth > 0)
        term = TestSession(columns, rows) { view.onScreenUpdated() }
        view.attachSession(term.session)
        // Sized to the grid, as the mirror mode lays it out.
        val w = ceil(columns * view.fontWidth).toInt() + 2
        val h = rows * lineHeight + view.fontLineSpacingAndAscent + 2
        activity.get().setContentView(FrameLayout(activity.get()).apply { addView(view, FrameLayout.LayoutParams(w, h)) })
        view.measure(exactly(w), exactly(h))
        view.layout(0, 0, w, h)
        idle()
        view.onScreenUpdated()
        term.printLines(0, 200)
        idle()
        assertEquals(columns, view.mEmulator.mColumns)
        assertEquals(rows, view.mEmulator.mRows)
    }

    @After
    fun tearDown() = activity.pause().stop().destroy().let { }

    private fun exactly(px: Int) = android.view.View.MeasureSpec.makeMeasureSpec(px, android.view.View.MeasureSpec.EXACTLY)

    /** Run everything due, including animation frames to their end: Robolectric's frames advance its clock. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Run one frame or other task. */
    private fun step() = shadowOf(Looper.getMainLooper()).runOneTask()

    private var downTime = 0L
    private var time = 0L

    private fun touch(action: Int, y: Float, x: Float = view.width / 2f) {
        val e = MotionEvent.obtain(downTime, time, action, x, y, 0)
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    /** Drag a finger down by [distance] in steps of [stepMs]. A fling lifts the finger; otherwise the touch is cancelled. */
    private fun drag(distance: Float, steps: Int = 10, stepMs: Long = 16, fling: Boolean = false) {
        downTime = SystemClock.uptimeMillis()
        time = downTime
        val y0 = 100f
        touch(MotionEvent.ACTION_DOWN, y0)
        for (i in 1..steps) {
            time += stepMs
            touch(MotionEvent.ACTION_MOVE, y0 + distance * i / steps)
        }
        time += 8
        touch(if (fling) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL, y0 + distance)
    }

    private val position get() = view.mScroll.getPosition(lineHeight)

    @Test
    fun aShorterViewKeepsTheBottomRowInSightUntilTheRemoteResizes() {
        assertEquals(0, view.bottomLiftPixels)
        // The keyboard opens and takes three rows' room, before the remote has taken the new size.
        val w = view.width
        val h = view.height - 3 * lineHeight
        view.measure(exactly(w), exactly(h))
        view.layout(0, 0, w, h)
        idle()
        assertEquals(rows, view.mEmulator.mRows)
        // The screen is drawn up by what doesn't fit, so the bottom row, where the typing is, shows.
        val lift = view.bottomLiftPixels
        assertEquals(view.fontLineSpacingAndAscent + rows * lineHeight - h, lift)
        assertTrue(lift > 2 * lineHeight)
        // Touches go to the rows where they are drawn.
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, h - 2f, 0)
        assertEquals(rows - 1, view.getColumnAndRow(down, false)[1])
        down.recycle()

        // History keeps the lift, so the text follows the finger rather than jumping by it as the drag begins.
        val bottomRowY = view.getPointY(rows - 1)
        drag(2.5f * lineHeight)
        assertEquals(lift, view.bottomLiftPixels)
        assertTrue(view.scrollbackRows > 0)
        val moved = view.getPointY(rows - 1) - bottomRowY
        assertTrue("moved $moved for a drag of ${2.5f * lineHeight}", moved > 2 * lineHeight && moved <= 3 * lineHeight)
        view.scrollToBottom()
        idle()
        assertEquals(lift, view.bottomLiftPixels)
        assertEquals(bottomRowY, view.getPointY(rows - 1))

        // Once the remote has the new size, the screen fits and is drawn from the top again.
        term.session.setRemoteSize(columns, rows - 3)
        idle()
        assertEquals(0, view.bottomLiftPixels)
    }

    @Test
    fun dragMovesByPixelsAndOutputDoesNotPullTheViewDown() {
        val slop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
        drag(5.5f * lineHeight)
        // The text follows the finger past the touch slop, by pixels rather than whole rows.
        assertEquals(-(5.5f * lineHeight - slop), position, 1.5f)
        assertTrue(view.mScroll.offsetPixels > 0)
        val top = view.topRow
        val shown = term.text(top)
        assertEquals(view.scrollbackRows, reported.last())

        term.printLines(200, 6)
        repeat(10) { term.print("\r\u001b[2K* Working $it") }
        idle()

        assertEquals(top - 6, view.topRow)
        assertEquals(shown, term.text(view.topRow))
        assertEquals(-top + 6, reported.last())
    }

    @Test
    fun aFrameDrawnPartwayThroughASynchronizedUpdateKeepsTheLinesInView() {
        drag(8f * lineHeight)
        val shown = term.text(view.topRow)
        // A frame that came in two network messages: its lines are in history, but the update isn't announced yet.
        term.print("\u001b[?2026h")
        term.printLines(200, 6)
        assertTrue(term.session.isUpdateHeld)
        // A finger scrolling meanwhile draws a frame.
        view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
        assertEquals(shown, term.text(view.topRow))

        term.print("\u001b[?2026l")
        idle()
        assertEquals(shown, term.text(view.topRow))
    }

    @Test
    fun atTheBottomOutputIsFollowed() {
        assertEquals(0, view.scrollbackRows)
        term.printLines(200, 30)
        idle()
        assertEquals(0, view.topRow)
        assertEquals(0, view.mScroll.offsetPixels)
    }

    @Test
    fun typingReturnsToTheBottom() {
        drag(8f * lineHeight)
        assertTrue(view.scrollbackRows > 0)
        view.inputCodePoint('a'.code, false, false, false)
        assertEquals(0, view.scrollbackRows)
        assertEquals(0, view.mScroll.offsetPixels)
        assertEquals(0, reported.last())
        assertEquals("a", term.typed.toString())

        drag(8f * lineHeight)
        view.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals(0, view.scrollbackRows)
    }

    @Test
    fun flingGlidesByPixelsAndATouchStopsIt() {
        drag(3f * lineHeight, steps = 4, stepMs = 10, fling = true)
        val lifted = position
        var last = lifted
        repeat(10) {
            step()
            assertTrue("each frame moves a little, not a row at a time", last - position in 0f..lineHeight / 2f)
            last = position
        }
        assertTrue(position < lifted)
        idle()
        assertTrue("the fling carries on well past the lift: $lifted then $position", position < lifted - 5 * lineHeight)

        view.scrollToBottom()
        idle()
        drag(3f * lineHeight, steps = 4, stepMs = 10, fling = true)
        repeat(5) { step() }
        downTime = SystemClock.uptimeMillis()
        time = downTime
        touch(MotionEvent.ACTION_DOWN, 300f)
        val stopped = position
        idle()
        assertEquals(stopped, position, 0f)
        touch(MotionEvent.ACTION_CANCEL, 300f)
    }

    @Test
    fun scrollToBottomGlidesThereAndEndsExactlyAtZero() {
        drag(6f * lineHeight)
        val from = position
        view.scrollToBottom()
        repeat(3) { step() }
        assertTrue("on the way: $from then $position", position > from && view.scrollbackRows > 0)
        // Output on the way does not leave it short.
        term.printLines(200, 3)
        idle()
        assertEquals(0, view.scrollbackRows)
        assertEquals(0, view.mScroll.offsetPixels)
        assertEquals(0, reported.last())
    }

    @Test
    fun aFlingOnTheAlternateScreenSendsAtMostAScreenOfArrowKeys() {
        term.print("\u001b[?1049h")
        idle()
        fun keys() = term.typed.toString().split("\u001b").count { it == "[B" || it == "OB" }
        drag(-6f * lineHeight, steps = 3, stepMs = 8, fling = true)
        val whileDragging = keys()
        idle()
        val byFling = keys() - whileDragging
        assertTrue("the fling sent $byFling", byFling in 1..rows)
        assertEquals(0, view.scrollbackRows)
    }

    @Test
    fun aNewEmulatorStartsAtTheBottom() {
        drag(6f * lineHeight)
        term.session.clearForFreshStream()
        idle()
        assertEquals(0, view.scrollbackRows)
        assertEquals(0, view.mScroll.offsetPixels)
    }

    @Test
    fun aNewTextSizeKeepsTheOffsetInsideARow() {
        drag(4.5f * lineHeight)
        view.setTextSize(20)
        assertEquals(0, view.mScroll.offsetPixels)
        assertTrue(view.topRow <= 0)
    }

    @Test
    fun theScrollThumbDragsThroughHistory() {
        drag(2f * lineHeight)
        val thumbX = view.width - 4f
        // The thumb shows near the bottom; drag it to the top of its track.
        downTime = SystemClock.uptimeMillis()
        time = downTime
        val fromY = view.height - 10f
        touch(MotionEvent.ACTION_DOWN, fromY, thumbX)
        time += 16
        touch(MotionEvent.ACTION_MOVE, -view.height.toFloat(), thumbX)
        time += 16
        touch(MotionEvent.ACTION_UP, -view.height.toFloat(), thumbX)
        assertEquals(-term.history, view.topRow)
    }

    @Test
    fun drawingWithAnOffsetShiftsTheSameRowsByThatManyPixels() {
        term.print("\u001b[41mred background\u001b[0m and text\r\n\u001b[7mreversed\u001b[0m ─── ● ✻\r\n")
        val renderer = view.mRenderer
        val w = view.width
        val h = view.height
        fun draw(top: Int, offset: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            renderer.render(term.emulator, Canvas(it), top, offset, -1, -1, -1, -1)
        }
        val still = draw(-3, 0)
        assertTrue(still.sameAs(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            renderer.render(term.emulator, Canvas(it), -3, -1, -1, -1, -1)
        }))
        val d = lineHeight / 3
        val moved = draw(-3, d)
        val a = IntArray(w * h).also { still.getPixels(it, 0, w, 0, 0, w, h) }
        val b = IntArray(w * h).also { moved.getPixels(it, 0, w, 0, 0, w, h) }
        var differing = 0
        var drawn = 0
        for (y in 0 until h - d - 2) for (x in 0 until w) {
            if (b[y * w + x] != a[(y + d) * w + x]) differing++
            if (b[y * w + x] != 0) drawn++
        }
        assertTrue(drawn > 0)
        assertEquals(0, differing)
        // The part of one more row showing at the bottom is drawn too.
        assertTrue((h - d - 2 until h).any { y -> (0 until w).any { x -> b[y * w + x] != 0 } })
    }

    /** Records what the view hands to a [TerminalView.WheelSink], as RemoteTerminal would pace and send it. */
    private class Sink : TerminalView.WheelSink {
        val lines = mutableListOf<Int>()
        val cells = mutableListOf<Pair<Int, Int>>()
        var underWay = false
        var stops = 0
        override fun scrollLines(lines: Int, column: Int, row: Int) {
            this.lines += lines
            cells += column to row
            underWay = true
        }

        override fun stopScrolling(): Boolean {
            stops++
            return underWay.also { underWay = false }
        }
    }

    /** Claude Code in fullscreen mode: the alternate screen with SGR mouse reporting. */
    private fun mouseProgram() {
        term.print("\u001b[?1049h\u001b[?1000h\u001b[?1002h\u001b[?1006h")
        idle()
        term.typed.reset()
    }

    private fun tap(y: Float = 200f) {
        downTime = SystemClock.uptimeMillis()
        time = downTime
        touch(MotionEvent.ACTION_DOWN, y)
        time += 40
        touch(MotionEvent.ACTION_UP, y)
    }

    private fun clicks() = term.typed.toString().split("\u001b[<").count { it.startsWith("0;") && it.endsWith("M") }

    /** Wait out the double-tap timeout, after which a lone tap is confirmed. */
    private fun waitForTapConfirmation() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

    private val touchSlop get() = android.view.ViewConfiguration.get(view.context).scaledTouchSlop

    @Test
    fun aCancelledTouchIsNeverAClick() {
        mouseProgram()
        downTime = SystemClock.uptimeMillis()
        time = downTime
        touch(MotionEvent.ACTION_DOWN, 200f)
        time += 40
        touch(MotionEvent.ACTION_CANCEL, 200f)
        waitForTapConfirmation()
        assertEquals("taken away before the finger lifted: no click", 0, clicks())
        tap()
        assertEquals(1, clicks())
    }

    @Test
    fun aCancelledScrollDoesNotEatTheNextTap() {
        mouseProgram()
        // Without a sink the drag's wheel events go out at once, so nothing is left under way for the tap to stop.
        drag(3f * lineHeight)
        assertTrue("the drag scrolled the program", term.typed.toString().contains("\u001b[<64;"))
        tap()
        assertEquals(1, clicks())
    }

    @Test
    fun aCancelledDragLeavesNoPartLineForTheNext() {
        mouseProgram()
        val sink = Sink()
        view.setWheelSink(sink)
        drag(touchSlop + 1.5f * lineHeight)
        assertEquals(-1, sink.lines.sum())
        // Half a line was left over; with it, this drag would make a whole line.
        drag(touchSlop + 0.6f * lineHeight)
        assertEquals(-1, sink.lines.sum())
    }

    @Test
    fun aCancelledDragJustAboveTheBottomSettlesThere() {
        drag(touchSlop + 0.3f * lineHeight)
        assertTrue(view.scrollbackRows > 0)
        idle()
        assertEquals(0, view.scrollbackRows)
        assertEquals(0, view.mScroll.offsetPixels)
        assertEquals(0, reported.last())

        // Further up, it stays where it was left.
        drag(touchSlop + 2f * lineHeight)
        val left = position
        idle()
        assertEquals(left, position, 0f)
    }

    @Test
    fun aTapThatStopsAHistoryFlingIsNotATap() {
        drag(3f * lineHeight, steps = 4, stepMs = 10, fling = true)
        repeat(3) { step() }
        tap()
        waitForTapConfirmation()
        assertEquals("the tap only stopped the fling", 0, taps)

        tap()
        waitForTapConfirmation()
        assertEquals(1, taps)
    }

    @Test
    fun mouseReportingOnTheMainScreenReturnsToTheBottom() {
        drag(6f * lineHeight)
        assertTrue(view.scrollbackRows > 0)
        term.print("\u001b[?1000h\u001b[?1006h")
        idle()
        assertEquals("taps go to the program at screen rows, so show the screen", 0, view.scrollbackRows)
        assertEquals(0, view.mScroll.offsetPixels)
        assertEquals(0, reported.last())
        term.typed.reset()
        tap(y = view.fontLineSpacingAndAscent + 2.5f * lineHeight)
        assertTrue("clicked on the third screen row: ${term.typed}", term.typed.toString().contains(";3M"))

        // A fling through history under way is stopped there too.
        term.print("\u001b[?1000l")
        idle()
        drag(3f * lineHeight, steps = 4, stepMs = 10, fling = true)
        repeat(3) { step() }
        assertTrue(view.scrollbackRows > 0)
        term.print("\u001b[?1000h")
        idle()
        assertEquals(0, view.scrollbackRows)
        assertEquals(0, view.mScroll.offsetPixels)
    }

    @Test
    fun aDragInAMouseProgramGoesToTheSinkALineAtATime() {
        mouseProgram()
        val sink = Sink()
        view.setWheelSink(sink)
        val slop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
        drag(5.5f * lineHeight)
        // The finger moved down: the program scrolls up by a line per line height past the touch slop.
        assertEquals(-((5.5f * lineHeight - slop) / lineHeight).toInt(), sink.lines.sum())
        assertTrue("one line or so per move, not bunched: ${sink.lines}", sink.lines.all { it in -2..-1 })
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, view.width / 2f, 100f, 0)
        val cell = view.getColumnAndRow(down, false)
        down.recycle()
        assertEquals("pointed where the finger went down", cell[0] to cell[1], sink.cells.first())
        assertEquals("nothing typed: the sink sends the wheel events", "", term.typed.toString())
        assertEquals(0, view.scrollbackRows)

        drag(-3f * lineHeight)
        assertTrue("then down: ${sink.lines}", sink.lines.last() > 0)
    }

    @Test
    fun aWheelFlingGlidesOnAndIsCapped() {
        mouseProgram()
        val sink = Sink()
        view.setWheelSink(sink)
        drag(3f * lineHeight, steps = 4, stepMs = 10, fling = true)
        val whileDragging = sink.lines.sum()
        idle()
        val byFling = whileDragging - sink.lines.sum()
        assertTrue("the fling sent $byFling lines", byFling in 3..3 * rows)

        // A much faster fling stops at three screens.
        val before = sink.lines.sum()
        drag(-8f * lineHeight, steps = 2, stepMs = 4, fling = true)
        idle()
        assertTrue("capped: ${sink.lines.sum() - before}", sink.lines.sum() - before <= 3 * rows + 8)
        assertEquals("", term.typed.toString())
    }

    @Test
    fun aTapThatStopsAScrollDoesNotClick() {
        mouseProgram()
        val sink = Sink()
        view.setWheelSink(sink)
        drag(3f * lineHeight, steps = 4, stepMs = 10, fling = true)
        repeat(3) { step() }
        val stops = sink.stops
        tap()
        assertTrue("the tap stopped the scroll", sink.stops > stops)
        assertEquals("and did not click", 0, clicks())
        val sent = sink.lines.sum()
        idle()
        assertEquals("nothing more after the tap", sent, sink.lines.sum())

        // Lines still waiting to be sent after a drag: a tap stops them too.
        sink.underWay = true
        tap()
        assertEquals(0, clicks())

        // With nothing under way, a tap is a click again.
        tap()
        assertEquals(1, clicks())
    }

    @Test
    fun withoutMouseReportingTheAlternateScreenStillGetsArrowKeys() {
        term.print("\u001b[?1049h")
        idle()
        val sink = Sink()
        view.setWheelSink(sink)
        drag(-4f * lineHeight)
        assertTrue(term.typed.toString().contains("\u001b[B") || term.typed.toString().contains("\u001bOB"))
        assertTrue(sink.lines.isEmpty())
    }

    @Test
    fun typingDropsLinesNotSentYet() {
        mouseProgram()
        val sink = Sink()
        view.setWheelSink(sink)
        drag(4f * lineHeight)
        val stops = sink.stops
        view.inputCodePoint('a'.code, false, false, false)
        assertTrue(sink.stops > stops)
    }

    private inner class Client : TerminalViewClient {
        override fun onScale(scale: Float) = 1f
        override fun onSingleTapUp(e: MotionEvent) {
            taps++
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = false
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = true
        override fun readControlKey() = false
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
        override fun onEmulatorSet() = Unit
        override fun onScrollbackChanged(rows: Int) {
            reported += rows
        }
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }
}
