package com.jeffarese.rexmobile.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.jeffarese.rexmobile.terminal.view.TestSession
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

/** One finger scrolls history and two fingers zoom, without the two fighting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalViewportScrollTest {
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var viewport: TerminalViewport
    private lateinit var term: TestSession
    private val reported = mutableListOf<Int>()
    private val tv get() = viewport.terminalView

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        viewport = TerminalViewport(activity.get(), Typeface.MONOSPACE)
        viewport.onScrollbackChanged = { reported += it }
        term = TestSession(40, 10) { tv.onScreenUpdated() }
        viewport.remoteSize = RemoteSize(40, 10)
        activity.get().setContentView(FrameLayout(activity.get()).apply { addView(viewport, FrameLayout.LayoutParams(480, 800)) })
        idle()
        viewport.attach(term.session)
        idle()
        term.printLines(0, 200)
        idle()
    }

    @After
    fun tearDown() = activity.pause().stop().destroy().let { }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private var downTime = 0L
    private var time = 0L

    /** An event with one finger at each of [xs], all at height [y]. */
    private fun event(action: Int, xs: List<Float>, y: Float): MotionEvent {
        val props = Array(xs.size) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(xs.size) { i -> MotionEvent.PointerCoords().apply { x = xs[i]; this.y = y; pressure = 1f; size = 1f } }
        return MotionEvent.obtain(downTime, time, action, xs.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun send(action: Int, xs: List<Float>, y: Float) {
        time += 16
        val e = event(action, xs, y)
        viewport.dispatchTouchEvent(e)
        e.recycle()
    }

    private val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private val secondUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /** Spread two fingers around the middle of the terminal from [from] to [to] apart. */
    private fun pinch(from: Float, to: Float) {
        val cx = tv.left + tv.width / 2f
        val cy = tv.top + tv.height / 2f
        downTime = SystemClock.uptimeMillis()
        time = downTime
        send(MotionEvent.ACTION_DOWN, listOf(cx - from / 2), cy)
        send(secondDown, listOf(cx - from / 2, cx + from / 2), cy)
        for (i in 1..10) {
            val span = from + (to - from) * i / 10
            send(MotionEvent.ACTION_MOVE, listOf(cx - span / 2, cx + span / 2), cy)
        }
        send(secondUp, listOf(cx - to / 2, cx + to / 2), cy)
        send(MotionEvent.ACTION_UP, listOf(cx - to / 2), cy)
        idle()
    }

    /** Where the terminal is scrolled to, in its own pixels. */
    private fun position() = tv.topRow * tv.fontLineSpacing.toFloat() - tv.getPointY(tv.topRow)

    @Test
    fun oneFingerScrollsHistoryWhenZoomed() {
        pinch(200f, 400f)
        assertTrue("zoomed: ${tv.scaleX}", tv.scaleX > 1.5f)
        assertEquals("pinching doesn't scroll history", 0, viewport.scrollbackRows)

        downTime = SystemClock.uptimeMillis()
        time = downTime
        val x = viewport.width / 2f
        send(MotionEvent.ACTION_DOWN, listOf(x), 200f)
        for (i in 1..10) send(MotionEvent.ACTION_MOVE, listOf(x), 200f + 30f * i)
        send(MotionEvent.ACTION_CANCEL, listOf(x), 500f)

        assertTrue(viewport.scrollbackRows > 0)
        assertEquals(viewport.scrollbackRows, reported.last())
        // The text moves with the finger on screen: 300 screen pixels, less the touch slop, at the zoom.
        val slop = android.view.ViewConfiguration.get(activity.get()).scaledTouchSlop
        assertEquals(-(300f / tv.scaleY - slop), position(), 2f)
    }

    @Test
    fun aSecondFingerTakesOverFromAScroll() {
        downTime = SystemClock.uptimeMillis()
        time = downTime
        val x = tv.left + tv.width / 3f
        send(MotionEvent.ACTION_DOWN, listOf(x), 100f)
        for (i in 1..5) send(MotionEvent.ACTION_MOVE, listOf(x), 100f + 20f * i)
        val scrolled = position()
        assertTrue(scrolled < 0)

        val x2 = x + 220f
        send(secondDown, listOf(x, x2), 200f)
        for (i in 1..8) send(MotionEvent.ACTION_MOVE, listOf(x - 10f * i, x2 + 10f * i), 200f + 15f * i)
        send(secondUp, listOf(x - 80f, x2 + 80f), 320f)
        send(MotionEvent.ACTION_UP, listOf(x - 80f), 320f)
        idle()

        assertEquals("the pinch doesn't scroll or fling the terminal", scrolled, position(), 0f)
        assertTrue(tv.scaleX > 1f)
    }

    @Test
    fun pinchingTheFontInPhoneModeKeepsTheScrollValid() {
        viewport.mode = TerminalViewport.Mode.PHONE
        idle()
        val before = viewport.phoneTextSize
        downTime = SystemClock.uptimeMillis()
        time = downTime
        val x = viewport.width / 2f
        send(MotionEvent.ACTION_DOWN, listOf(x), 100f)
        for (i in 1..6) send(MotionEvent.ACTION_MOVE, listOf(x), 100f + 25f * i)
        send(MotionEvent.ACTION_CANCEL, listOf(x), 250f)
        assertTrue(viewport.scrollbackRows > 0)

        pinch(300f, 200f)
        assertTrue("font shrank: $before -> ${viewport.phoneTextSize}", viewport.phoneTextSize < before)
        val offset = -tv.getPointY(tv.topRow)
        assertTrue("offset $offset within a row of ${tv.fontLineSpacing}", offset in 0 until tv.fontLineSpacing)
        assertTrue(tv.topRow in -term.history..0)
        tv.draw(Canvas(Bitmap.createBitmap(tv.width, tv.height, Bitmap.Config.ARGB_8888)))
    }

    @Test
    fun aSlowPinchGrowsTheTextAsFarAsTheFingersSpread() {
        viewport.mode = TerminalViewport.Mode.PHONE
        idle()
        val before = viewport.phoneTextSize
        // Ten small steps, each too small to make a pixel of text on its own.
        pinch(300f, 360f)
        assertTrue("font grew: $before -> ${viewport.phoneTextSize}", viewport.phoneTextSize >= before * 1.1f)
    }

    @Test
    fun scrollToBottomReturnsToTheLiveScreen() {
        downTime = SystemClock.uptimeMillis()
        time = downTime
        val x = viewport.width / 2f
        send(MotionEvent.ACTION_DOWN, listOf(x), 100f)
        for (i in 1..6) send(MotionEvent.ACTION_MOVE, listOf(x), 100f + 40f * i)
        send(MotionEvent.ACTION_CANCEL, listOf(x), 340f)
        assertTrue(viewport.scrollbackRows > 0)
        viewport.scrollToBottom()
        idle()
        assertEquals(0, viewport.scrollbackRows)
        assertEquals(0, reported.last())
    }
}
