package com.jeffarese.rexmobile.ui

import com.jeffarese.rexmobile.model.BlockMeta
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostState
import com.jeffarese.rexmobile.rex.BlockInfo
import com.jeffarese.rexmobile.rex.Layer
import com.jeffarese.rexmobile.rex.ProgramStatus
import com.jeffarese.rexmobile.rex.SessionView
import com.jeffarese.rexmobile.rex.WindowView
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** The agent board on Home: which blocks it shows, in which group, and in what order. */
class AgentBoardTest {
    private val host = HostConfig(id = "h", label = "Studio", url = "studio.example")

    private fun status(state: String, at: String = "2026-10-28T10:00:00Z", id: String? = null, kind: String? = null) =
        ProgramStatus(id = id, state = state, kind = kind, updatedAt = at)

    /** One session with a tab per block, so each block's meta is what the board sees. */
    private fun board(vararg blocks: Pair<String, BlockMeta>): List<BoardEntry> {
        val windows = blocks.map { (id, _) -> WindowView("w-$id", layers = listOf(Layer(blocks = listOf(BlockInfo(id))))) }
        val state = HostState(
            views = mapOf("s" to SessionView(sessionId = "s", windows = windows)),
            blocks = blocks.toMap(),
        )
        return agentBoard(listOf(host to state))
    }

    private fun meta(vararg statuses: ProgramStatus, unseen: Boolean = false, title: String = "") =
        BlockMeta("s", title = title, statuses = statuses.toList(), unseen = unseen)

    @Test
    fun groupsByWhatNeedsAPersonFirst() {
        val entries = board(
            "work" to meta(status("working"), title = "build"),
            "done" to meta(status("done"), unseen = true),
            "ask" to meta(status("blocked", kind = "permission")),
            "fail" to meta(status("error"), unseen = true),
        )
        assertEquals(listOf("ask", "fail", "done", "work"), entries.map { it.blockId })
        assertEquals(
            listOf(BoardGroup.NEEDS_YOU, BoardGroup.FAILED, BoardGroup.DONE, BoardGroup.WORKING),
            entries.map { it.group },
        )
    }

    @Test
    fun finishedAndFailedLeaveOnceSeen() {
        val entries = board(
            "done" to meta(status("done")),
            "fail" to meta(status("error")),
            "idle" to meta(status("idle")),
            "shell" to meta(),
        )
        assertEquals(emptyList<String>(), entries.map { it.blockId })
    }

    @Test
    fun aBellWithNoStatusNeedsYou() {
        assertEquals(listOf(BoardGroup.NEEDS_YOU), board("bell" to meta(unseen = true)).map { it.group })
    }

    @Test
    fun theWorstRecordDecidesTheGroup() {
        // The agent is working, but one of its subtasks is waiting for an answer.
        val entries = board("agent" to meta(status("working"), status("blocked", id = "deploy", kind = "question")))
        assertEquals(listOf(BoardGroup.NEEDS_YOU), entries.map { it.group })
    }

    @Test
    fun longestWaitingFirstAndNewestFinishedFirst() {
        val entries = board(
            "ask-new" to meta(status("blocked", at = "2026-10-28T10:05:00Z")),
            "ask-old" to meta(status("blocked", at = "2026-10-28T10:01:00Z")),
            "done-old" to meta(status("done", at = "2026-10-28T09:00:00Z"), unseen = true),
            "done-new" to meta(status("done", at = "2026-10-28T09:30:00Z"), unseen = true),
        )
        assertEquals(listOf("ask-old", "ask-new", "done-new", "done-old"), entries.map { it.blockId })
    }

    @Test
    fun workingAgentsKeepTheirPlaceAsTheyReport() {
        val before = board(
            "b" to meta(status("working", at = "2026-10-28T10:00:00Z"), title = "beta"),
            "a" to meta(status("working", at = "2026-10-28T10:00:01Z"), title = "alpha"),
        )
        val after = board(
            "b" to meta(status("working", at = "2026-10-28T10:09:00Z"), title = "beta"),
            "a" to meta(status("working", at = "2026-10-28T10:00:01Z"), title = "alpha"),
        )
        assertEquals(listOf("a", "b"), before.map { it.blockId })
        assertEquals(before.map { it.blockId }, after.map { it.blockId })
    }

    @Test
    fun theDetailLineDoesntRepeatTheTitleOrTheGroup() {
        // Claude Code reports a state and nothing else: its group already says Done, Failed or Needs you.
        assertEquals(null, agentDetail(meta(status("done")), "Fix login"))
        assertEquals(null, agentDetail(meta(status("error")), "Fix login"))
        assertEquals(null, agentDetail(meta(status("blocked")), "Fix login"))
        // What it waits for says more than "Needs you".
        assertEquals("Waiting for permission", agentDetail(meta(status("blocked", kind = "permission")), "Fix login"))
        // A notification titled like the pane keeps only its body; one that is only the title goes.
        val note = BlockMeta("s", statuses = listOf(status("done")), notification = "Fix login · Claude is waiting for your input")
        assertEquals("Claude is waiting for your input", agentDetail(note, "Fix login"))
        assertEquals(null, agentDetail(note.copy(notification = "Fix login"), "Fix login"))
        assertEquals("Build · passed", agentDetail(note.copy(notification = "Build · passed"), "Fix login"))
        // A message is the program's own words, but not when it is just the title again.
        assertEquals("212 tests pass", agentDetail(meta(ProgramStatus(state = "done", msg = "212 tests pass")), "Fix login"))
        assertEquals(null, agentDetail(meta(ProgramStatus(state = "working", msg = "Fix login")), "Fix login"))
    }

    @Test
    fun theTaskRecordIsTheTitleNotASubtask() {
        // As Claude Code reports when an orchestrator runs it as a lane: a bare working record, and the task as its own record.
        val task = "Checkout v3 round 4, lane retries: the payment retries after a dropped connection"
        val m = BlockMeta(
            "s", title = "checkout-r4-retries",
            statuses = listOf(
                ProgramStatus(state = "working", app = "claude-code"),
                ProgramStatus(id = "wpyw955v5", state = "working", app = "claude-code", title = task, msg = "Merging the retries branch"),
                ProgramStatus(id = "build", state = "working", title = "Build"),
            ),
        )
        val title = blockTitle(m)
        assertEquals(task, title)
        assertEquals(listOf("build"), subtasks(m, m.status, title).map { it.id })
        // What the task record says still shows, under the title.
        assertEquals("Merging the retries branch", agentDetail(m, title))
    }

    @Test
    fun theSourceLineLeavesOutASessionNamedLikeTheTaskOrFolder() {
        fun source(session: String?, showHost: Boolean = false): String? {
            val e = board("a" to BlockMeta("s", pwd = "/Users/alex/src/billing", statuses = listOf(status("working")))).single()
            return agentSource(e.copy(sessionLabel = session), "Add retries", showHost)
        }
        assertEquals("work · ~/src/billing", source("work"))
        assertEquals("~/src/billing", source("billing"))
        assertEquals("~/src/billing", source("Add retries"))
        assertEquals("Studio · ~/src/billing", source(null, showHost = true))
    }

    @Test
    fun sinceReadsTheServersTimestamp() {
        val now = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
        assertEquals("now", since("2026-10-28T11:59:30Z", now))
        assertEquals("4m", since("2026-10-28T11:56:00Z", now))
        assertEquals("2h", since("2026-10-28T09:30:00Z", now))
        assertEquals("3d", since("2026-10-25T11:00:00Z", now))
        // A server clock ahead of the phone's isn't the future.
        assertEquals("now", since("2026-10-28T12:01:00Z", now))
        assertEquals(null, since("", now))
        assertEquals(null, since(null, now))
    }

    @Test
    fun aScreenReaderSaysHowLongAgoInWords() {
        val now = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
        assertEquals("just now", spokenSince("2026-10-28T11:59:30Z", now))
        assertEquals("1 minute ago", spokenSince("2026-10-28T11:59:00Z", now))
        assertEquals("4 minutes ago", spokenSince("2026-10-28T11:56:00Z", now))
        assertEquals("2 hours ago", spokenSince("2026-10-28T09:30:00Z", now))
        assertEquals("1 day ago", spokenSince("2026-10-27T11:00:00Z", now))
        assertEquals(null, spokenSince(null, now))
    }

    @Test
    fun aCardsStateSaysItsGroupWhatItWaitsForAndWhen() {
        val now = Instant.parse("2026-10-28T10:04:00Z").toEpochMilli()
        fun state(m: BlockMeta) = board("a" to m).single().let { boardState(it, blockTitle(it.meta), now) }
        assertEquals("Working", state(meta(status("working"))))
        assertEquals("Done, 4 minutes ago", state(meta(status("done"), unseen = true)))
        assertEquals("Failed, 4 minutes ago", state(meta(status("error"), unseen = true)))
        // What it waits for, when the card shows what the program said instead.
        val asking = ProgramStatus(state = "blocked", kind = "permission", msg = "Run the tests?", updatedAt = "2026-10-28T10:00:00Z")
        assertEquals("Needs you, waiting for permission, 4 minutes ago", state(meta(asking)))
        // Not when the card's line already says it.
        assertEquals("Needs you, 4 minutes ago", state(meta(status("blocked", kind = "permission"))))
        assertEquals("Needs you, 4 minutes ago", state(meta(status("blocked"))))
    }

    @Test
    fun aTabsStateSaysWhatItsBadgeShows() {
        assertEquals("Working", tabState(status("working"), unseen = false))
        assertEquals("Needs you, new", tabState(status("blocked"), unseen = true))
        assertEquals("Failed", tabState(status("error"), unseen = false))
        assertEquals("Done", tabState(status("done"), unseen = false))
        assertEquals("New", tabState(null, unseen = true))
        assertEquals(null, tabState(null, unseen = false))
        assertEquals("Closing", tabState(status("working"), unseen = false, closing = true))
    }
}
