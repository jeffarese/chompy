package com.jeffarese.rexmobile.terminal

import android.graphics.Typeface
import android.os.Looper
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** In phone mode the size the view last asked for is the size it fits, whatever the remote has now. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalViewportSizeTest {
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var viewport: TerminalViewport
    private lateinit var term: TestSession
    private val wishes = mutableListOf<RemoteSize>()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        viewport = TerminalViewport(activity.get(), Typeface.MONOSPACE)
        viewport.mode = TerminalViewport.Mode.PHONE
        term = TestSession(40, 10, onViewSize = { c, r -> wishes += RemoteSize(c, r) }) { viewport.terminalView.onScreenUpdated() }
        activity.get().setContentView(FrameLayout(activity.get()).apply { addView(viewport, FrameLayout.LayoutParams(480, 800)) })
        idle()
        viewport.attach(term.session)
        idle()
    }

    @After
    fun tearDown() = activity.pause().stop().destroy().let { }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun aFontThatFitsTheRemoteSizeStillAsksForIt() {
        viewport.phoneTextSize = BIG
        idle()
        val big = wishes.last()
        // The server has applied it.
        term.session.setRemoteSize(big.columns, big.rows)
        idle()

        // A smaller font asks for more, and before the server answers, the font goes back.
        viewport.phoneTextSize = SMALL
        idle()
        assertNotEquals(big, wishes.last())
        viewport.phoneTextSize = BIG
        idle()

        // The view fits the remote's size again, but the last thing it asked for was the smaller font's.
        assertEquals(big, wishes.last())
    }

    @Test
    fun theSavedFontIsMeasuredBeforeTheFirstLayout() {
        viewport.phoneTextSize = BIG
        idle()
        val saved = wishes.last()

        // A new viewport, as when coming back to the tab, given the saved size before it is laid out.
        val fresh = TerminalViewport(activity.get(), Typeface.MONOSPACE)
        val asked = mutableListOf<RemoteSize>()
        val session = TestSession(40, 10, onViewSize = { c, r -> asked += RemoteSize(c, r) })
        fresh.phoneTextSize = BIG
        fresh.mode = TerminalViewport.Mode.PHONE
        fresh.attach(session.session)
        activity.get().setContentView(FrameLayout(activity.get()).apply { addView(fresh, FrameLayout.LayoutParams(480, 800)) })
        idle()

        // Every size it asked for is the saved font's, none the default font's on the way there.
        assertEquals(listOf(saved), asked.distinct())
    }

    @Test
    fun phoneColumnsFitInsideTheSidePadding() {
        val side = (TerminalViewport.SIDE_PADDING_DP * viewport.resources.displayMetrics.density).toInt()
        val view = viewport.terminalView
        assertEquals(480 - 2 * side, view.width)
        assertEquals(side, view.left)
        assertEquals(((480 - 2 * side) / view.fontWidth).toInt(), wishes.last().columns)
    }

    private companion object {
        // Pixels, inside the phone-mode range at Robolectric's density.
        const val BIG = 22
        const val SMALL = 11
    }
}
