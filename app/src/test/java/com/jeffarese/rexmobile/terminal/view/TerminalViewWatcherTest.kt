package com.jeffarese.rexmobile.terminal.view

import android.graphics.Typeface
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import com.jeffarese.rexmobile.terminal.TerminalViewport
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What a [TerminalView] tells the terminal behind it: whether its window shows, and when history is left. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalViewWatcherTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val viewport = TerminalViewport(activity.get(), Typeface.MONOSPACE)
    private val view get() = viewport.terminalView
    private val term = TestSession(40, 10) { view.onScreenUpdated() }
    private val told = mutableListOf<String>()

    @Before
    fun setUp() {
        viewport.attach(term.session)
        view.setWatcher(object : TerminalView.Watcher {
            override fun onWindowShown(shown: Boolean) {
                told += if (shown) "shown" else "hidden"
            }

            override fun onBackFromHistory() {
                told += "back"
            }
        })
        activity.get().setContentView(viewport)
        idle()
        term.printLines(0, 200)
        idle()
    }

    @After
    fun tearDown() = activity.pause().stop().destroy().let { }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun leavingHistoryIsToldOnce() {
        view.setTopRow(-50)
        idle()
        assertTrue(view.scrollbackRows > 0)
        view.setTopRow(-20)
        idle()
        view.scrollToBottom()
        idle()
        assertEquals(0, view.scrollbackRows)
        assertEquals(listOf("back"), told)
    }

    @Test
    fun theAppGoingAwayAndComingBackIsTold() {
        assertTrue(view.isWindowShown)
        // As the window's root is told when the app goes to the background; Robolectric's stop doesn't.
        view.rootView.dispatchWindowVisibilityChanged(View.GONE)
        assertEquals(listOf("hidden"), told)
        assertFalse(view.isWindowShown)
        view.rootView.dispatchWindowVisibilityChanged(View.GONE)
        assertEquals("told once", listOf("hidden"), told)

        view.rootView.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertEquals(listOf("hidden", "shown"), told)
        assertTrue(view.isWindowShown)
    }
}
