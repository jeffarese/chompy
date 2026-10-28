package com.jeffarese.rexmobile.terminal

import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Esc from a keyboard goes to the server as a key event, so it can't run into the keys typed after it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalViewportKeyTest {
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var viewport: TerminalViewport
    private lateinit var term: TestSession
    private val keys = mutableListOf<RemoteKey>()
    private val tv get() = viewport.terminalView

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        viewport = TerminalViewport(activity.get(), Typeface.MONOSPACE)
        term = TestSession(40, 10) { tv.onScreenUpdated() }
        viewport.remoteSize = RemoteSize(40, 10)
        activity.get().setContentView(FrameLayout(activity.get()).apply { addView(viewport, FrameLayout.LayoutParams(480, 800)) })
        idle()
        viewport.attach(term.session)
        idle()
        viewport.onRemoteKey = { keys += it }
    }

    @After
    fun tearDown() = activity.pause().stop().destroy().let { }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun press(code: Int, meta: Int = 0, repeat: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        return tv.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, repeat, meta))
    }

    @Test
    fun escapeGoesAsAKeyEvent() {
        assertTrue(press(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(listOf(RemoteKey.ESCAPE), keys)
        assertEquals("not typed as a byte", "", term.typed.toString())
    }

    @Test
    fun heldModifiersGoWithIt() {
        val all = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_CTRL_ON or
            KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        press(KeyEvent.KEYCODE_ESCAPE, all)
        press(KeyEvent.KEYCODE_ESCAPE, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        press(KeyEvent.KEYCODE_ESCAPE, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        assertEquals(listOf("shift+ctrl+alt", "ctrl", "alt"), keys.map { it.mods })
        assertTrue(keys.all { it.key == "escape" && it.text.isEmpty() })
        assertEquals("", term.typed.toString())
    }

    @Test
    fun stickyModifiersFromTheKeyBarAreUsedUp() {
        var consumed = 0
        viewport.onModifiersConsumed = { consumed++ }
        viewport.stickyCtrl = true
        viewport.stickyAlt = true
        // Ctrl held on the keyboard too: the sticky one is still used up.
        press(KeyEvent.KEYCODE_ESCAPE, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        idle()
        assertEquals("ctrl+alt", keys.single().mods)
        assertFalse(viewport.stickyCtrl)
        assertFalse(viewport.stickyAlt)
        assertTrue(consumed > 0)

        press(KeyEvent.KEYCODE_ESCAPE)
        assertEquals("", keys.last().mods)
    }

    @Test
    fun aHeldEscapeRepeatsAsKeyEvents() {
        press(KeyEvent.KEYCODE_ESCAPE)
        press(KeyEvent.KEYCODE_ESCAPE, repeat = 1)
        press(KeyEvent.KEYCODE_ESCAPE, repeat = 2)
        assertEquals(3, keys.size)
        assertEquals("", term.typed.toString())
    }

    @Test
    fun otherKeysAndBackAreUnchanged() {
        press(KeyEvent.KEYCODE_A)
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals("a\r", term.typed.toString())
        val back = press(KeyEvent.KEYCODE_BACK)
        assertFalse("back is left to the system", back)
        assertTrue(keys.isEmpty())
        assertEquals("a\r", term.typed.toString())
    }

    @Test
    fun withoutAHandlerEscapeIsTypedAsBefore() {
        viewport.onRemoteKey = null
        press(KeyEvent.KEYCODE_ESCAPE)
        assertEquals("\u001b", term.typed.toString())
        assertTrue(keys.isEmpty())
    }

    @Test
    fun escapeReturnsToTheLiveScreen() {
        term.printLines(0, 200)
        idle()
        val x = viewport.width / 2f
        val down = SystemClock.uptimeMillis()
        var t = down
        fun touch(action: Int, y: Float) {
            val e = MotionEvent.obtain(down, t, action, x, y, 0)
            viewport.dispatchTouchEvent(e)
            e.recycle()
            t += 16
        }
        touch(MotionEvent.ACTION_DOWN, 100f)
        for (i in 1..6) touch(MotionEvent.ACTION_MOVE, 100f + 40f * i)
        touch(MotionEvent.ACTION_CANCEL, 340f)
        assertTrue(viewport.scrollbackRows > 0)
        press(KeyEvent.KEYCODE_ESCAPE)
        assertEquals(0, viewport.scrollbackRows)
    }
}
