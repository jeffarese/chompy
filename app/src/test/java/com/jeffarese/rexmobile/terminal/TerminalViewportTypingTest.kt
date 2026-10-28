package com.jeffarese.rexmobile.terminal

import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a keyboard, the soft keyboard and the key bar send, encoded by libghostty-vt for the program's keyboard
 * mode: legacy bytes for a shell, the Kitty protocol's for a program that turns it on, as Claude Code does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalViewportTypingTest {
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var viewport: TerminalViewport
    private lateinit var term: TestSession
    private val tv get() = viewport.terminalView
    private val typed get() = term.typed.toString()

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
    }

    @After
    fun tearDown() = activity.pause().stop().destroy().let { }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun press(code: Int, meta: Int = 0) {
        val now = SystemClock.uptimeMillis()
        tv.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        idle()
    }

    /** Text from the soft keyboard. */
    private fun commit(text: String) {
        tv.onCreateInputConnection(EditorInfo()).commitText(text, 1)
        idle()
    }

    private fun kitty() = term.print("\u001b[>1u")

    private val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

    @Test
    fun aKeyboardTypesWhatAShellExpects() {
        press(KeyEvent.KEYCODE_C, ctrl)
        press(KeyEvent.KEYCODE_B, alt)
        press(KeyEvent.KEYCODE_SPACE, ctrl)
        press(KeyEvent.KEYCODE_A, shift)
        press(KeyEvent.KEYCODE_DPAD_LEFT, ctrl)
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals("\u0003\u001bb\u0000A\u001b[1;5D\r", typed)
    }

    @Test
    fun aKeyboardSpeaksTheKittyProtocolToAProgramThatAsks() {
        kitty()
        press(KeyEvent.KEYCODE_ENTER, shift)
        press(KeyEvent.KEYCODE_C, ctrl)
        press(KeyEvent.KEYCODE_A, ctrl or shift)
        press(KeyEvent.KEYCODE_A, shift)
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals("\u001b[13;2u\u001b[99;5u\u001b[97;6uA\r", typed)
    }

    @Test
    fun theKeyBarsShiftMakesShiftEnter() {
        kitty()
        viewport.sendKey(KeyEvent.KEYCODE_ENTER, shift = true)
        viewport.sendKey(KeyEvent.KEYCODE_ENTER)
        viewport.sendKey(KeyEvent.KEYCODE_DPAD_UP, ctrl = true)
        assertEquals("\u001b[13;2u\r\u001b[1;5A", typed)
    }

    @Test
    fun theSoftKeyboardsEnterTakesTheKeyBarsModifiers() {
        commit("\n")
        viewport.stickyShift = true
        commit("\n")
        assertEquals("\r\u001b[27;2;13~", typed)
        kitty()
        viewport.stickyShift = true
        commit("\n")
        commit("\t")
        assertEquals("\r\u001b[27;2;13~\u001b[13;2u\t", typed)
    }

    @Test
    fun theKeyBarsCtrlWithTheSoftKeyboard() {
        viewport.stickyCtrl = true
        commit("c")
        commit("c")
        assertEquals("ctrl is used up by one character", "\u0003c", typed)
        kitty()
        viewport.stickyCtrl = true
        commit("c")
        viewport.stickyAlt = true
        commit("b")
        assertEquals("\u0003c\u001b[99;5u\u001b[98;3u", typed)
    }

    @Test
    fun theMessageBoxsHeldCharactersAreEncodedToo() {
        assertEquals("\u0003", viewport.encodeTyped("c", ctrl = true, alt = false, shift = false)?.toString(Charsets.UTF_8))
        kitty()
        assertEquals("\u001b[99;5u", viewport.encodeTyped("c", ctrl = true, alt = false, shift = false)?.toString(Charsets.UTF_8))
        assertEquals("\u001b[13;3u", viewport.encodeTyped("\n", ctrl = false, alt = true, shift = false)?.toString(Charsets.UTF_8))
        assertEquals(null, viewport.encodeTyped("ab", ctrl = true, alt = false, shift = false))
    }
}
