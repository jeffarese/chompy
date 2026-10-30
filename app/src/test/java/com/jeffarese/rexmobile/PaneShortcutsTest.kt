package com.jeffarese.rexmobile

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.model.BlockMeta
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostState
import com.jeffarese.rexmobile.model.Notifier
import com.jeffarese.rexmobile.rex.BlockInfo
import com.jeffarese.rexmobile.rex.Layer
import com.jeffarese.rexmobile.rex.ProgramStatus
import com.jeffarese.rexmobile.rex.SessionView
import com.jeffarese.rexmobile.rex.WindowView
import com.jeffarese.rexmobile.ui.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaneShortcutsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shortcuts = PaneShortcuts(context)

    /** One session, "api", with a tab per block. */
    private fun host(vararg blockIds: String, status: ConnStatus = ConnStatus.ONLINE, metas: Map<String, BlockMeta> = emptyMap()) = HostState(
        status = status,
        views = mapOf(
            "s1" to SessionView(
                sessionId = "s1", label = "api",
                windows = blockIds.map { WindowView("w-$it", label = "Tab $it", layers = listOf(Layer(blocks = listOf(BlockInfo(it))))) },
            ),
        ),
        blocks = metas,
    )

    private fun dynamic() = ShortcutManagerCompat.getDynamicShortcuts(context)

    @Test
    fun anOpenedPaneGetsAShortcutThatOpensItAgainNamedAsHomeNamesIt() {
        val claude = BlockMeta("s1", title = "✳ Claude Code", statuses = listOf(ProgramStatus(state = "working", app = "claude", title = "Fix the login test")))
        shortcuts.opened(Target("h1", "s1", "w-b1", "b1"), host("b1", metas = mapOf("b1" to claude)))

        val s = dynamic().single()
        assertEquals("Fix the login test", s.shortLabel)
        assertEquals("Fix the login test · api", s.longLabel)
        assertEquals(Notifier.ACTION_OPEN_PANE, s.intent.action)
        assertEquals("h1", s.intent.getStringExtra(Notifier.EXTRA_HOST))
        assertEquals("s1", s.intent.getStringExtra(Notifier.EXTRA_SESSION))
        assertEquals("b1", s.intent.getStringExtra(Notifier.EXTRA_BLOCK))
    }

    @Test
    fun aTabOpensOnThePaneHomeShowsForIt() {
        shortcuts.opened(Target("h1", "s1", "w-b1", null), host("b1"))
        assertEquals("b1", dynamic().single().intent.getStringExtra(Notifier.EXTRA_BLOCK))
        assertEquals("Tab b1", dynamic().single().shortLabel)
    }

    @Test
    fun theFourMostRecentAreKept() {
        val state = host("b1", "b2", "b3", "b4", "b5")
        listOf("b1", "b2", "b3", "b4", "b1", "b5").forEach { shortcuts.opened(Target("h1", "s1", "w-$it", it), state) }
        assertEquals(listOf("h1/b5", "h1/b1", "h1/b4", "h1/b3"), shortcuts.recent)
        assertEquals(shortcuts.recent.toSet(), dynamic().map { it.id }.toSet())
    }

    @Test
    fun aPaneKnownToHaveClosedLosesItsShortcut() {
        listOf("b1", "b2").forEach { shortcuts.opened(Target("h1", "s1", "w-$it", it), host("b1", "b2")) }
        shortcuts.opened(Target("h2", "s1", "w-b1", "b1"), host("b1"))

        // An unreachable host says nothing about its panes.
        shortcuts.prune(setOf("h1", "h2"), mapOf("h1" to host(status = ConnStatus.OFFLINE)))
        assertEquals(3, dynamic().size)
        // A connected one does; so does removing a host.
        shortcuts.prune(setOf("h1"), mapOf("h1" to host("b2")))
        assertEquals(listOf("h1/b2"), shortcuts.recent)
        assertEquals(listOf("h1/b2"), dynamic().map { it.id })
    }

    @Test
    fun closedIsOnlyWhatTheHostSays() {
        val states = mapOf("h1" to host("b1"), "h2" to host(status = ConnStatus.CONNECTING))
        assertFalse(PaneShortcuts.closed("h1/b1", setOf("h1", "h2"), states))
        assertTrue(PaneShortcuts.closed("h1/b9", setOf("h1", "h2"), states))
        assertFalse(PaneShortcuts.closed("h2/b9", setOf("h1", "h2"), states))
        assertTrue(PaneShortcuts.closed("h3/b1", setOf("h1", "h2"), states))
    }
}
