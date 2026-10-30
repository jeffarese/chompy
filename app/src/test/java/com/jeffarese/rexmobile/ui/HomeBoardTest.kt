package com.jeffarese.rexmobile.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.FakeRex
import com.jeffarese.rexmobile.FakeRex.Companion.status
import com.jeffarese.rexmobile.model.AppModel
import com.jeffarese.rexmobile.model.HostConnection
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The agent board on Home against a fake server: clearing what's done or failed, and what a screen reader hears. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class HomeBoardTest {
    @get:Rule val rule = createComposeRule()

    private val rex = FakeRex("b1", "b2", "b3")
    // An empty saved list, so the store doesn't seed this build's preset hosts beside the fake one.
    private val context: Context = ApplicationProvider.getApplicationContext<Context>().also {
        it.getSharedPreferences("hosts", Context.MODE_PRIVATE).edit().putString("list", "[]").commit()
    }
    private val model = AppModel(context, rex)
    private var home by mutableStateOf(true)
    private val fourMinutesAgo = Instant.now().minusSeconds(4 * 60 + 10).toString()

    @After
    fun tearDown() {
        model.connections.value.forEach { it.disconnect() }
        model.scope.cancel()
    }

    /** Home on a host whose blocks report [statuses], once the board shows them. */
    private fun show(vararg statuses: Pair<String, String>) {
        statuses.forEach { (bid, state) -> rex.block(bid).statuses = listOf(status("agent", state, title = "Task $bid", at = fourMinutesAgo)) }
        model.hostStore.add("Studio", "fake.test")
        rule.waitUntil(5_000) { model.connections.value.isNotEmpty() }
        conn().connect()
        rule.setContent { RexTheme { if (home) HomeScreen(model, onOpen = {}, onHosts = {}) } }
        val shown = statuses.map { "agent:${it.first}" }
        rule.waitUntil(10_000) { shown.all { rule.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() } }
    }

    private fun conn(): HostConnection = model.connections.value.single()
    private fun unseen(bid: String) = conn().state.value.blocks[bid]?.unseen
    private fun onBoard(bid: String) = rule.onAllNodesWithTag("agent:$bid").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aSwipeClearsADoneCardAndUndoBringsItBack() {
        show("b1" to "done", "b2" to "working")
        rule.onNodeWithTag("agent:b1").performTouchInput { swipeRight() }
        rule.waitUntil(5_000) { !onBoard("b1") }
        rule.onNodeWithText("Cleared “Task b1”").assertExists()

        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil(5_000) { onBoard("b1") }
        assertEquals(true, unseen("b1"))

        // Without Undo, it's seen once the snackbar goes, and stays off the board.
        rule.onNodeWithTag("agent:b1").performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) { !onBoard("b1") }
        rule.mainClock.advanceTimeBy(6_000)
        rule.waitUntil(5_000) { unseen("b1") == false }
        rule.waitForIdle()
        assertEquals(false, onBoard("b1"))
    }

    @Test
    fun workingAndWaitingCardsDontClear() {
        show("b1" to "working", "b2" to "blocked")
        for (bid in listOf("b1", "b2")) {
            val card = rule.onNodeWithTag("agent:$bid").fetchSemanticsNode()
            assertNull(card.config.getOrNull(SemanticsActions.CustomActions))
            rule.onNodeWithTag("agent:$bid").performTouchInput { swipeRight() }
        }
        rule.waitForIdle()
        assertEquals(true, onBoard("b1") && onBoard("b2"))
        rule.onNodeWithText("Clear").assertDoesNotExist()
    }

    @Test
    fun clearPutsTheWholeGroupAwayAndLeavingHomeMarksItSeen() {
        show("b1" to "done", "b2" to "done", "b3" to "error")
        // Failed's header comes first, then Done's.
        rule.onAllNodesWithText("Clear")[1].performClick()
        rule.waitUntil(5_000) { !onBoard("b1") && !onBoard("b2") }
        rule.onNodeWithText("Cleared 2 done").assertExists()
        assertEquals(true, onBoard("b3"))
        assertEquals(true, unseen("b1"))

        // Opening a terminal takes Home away while the snackbar is up: what was cleared is seen.
        home = false
        rule.waitForIdle()
        rule.waitUntil(5_000) { unseen("b1") == false && unseen("b2") == false }
        assertEquals(true, unseen("b3"))
    }

    @Test
    fun aScreenReaderHearsEachCardsState() {
        show("b1" to "done", "b2" to "working", "b3" to "error")
        rule.onNodeWithTag("agent:b1").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Done, 4 minutes ago"))
        rule.onNodeWithTag("agent:b2").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Working"))
        rule.onNodeWithTag("agent:b3").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Failed, 4 minutes ago"))
        // The time is in the state, not read out as "4m" too.
        rule.onNodeWithText("4m", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aTabSaysALongPressClosesIt() {
        show()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("window:w1").fetchSemanticsNodes().isNotEmpty() }
        val row = rule.onNodeWithTag("window:w1").fetchSemanticsNode()
        assertEquals("Close tab", row.config[SemanticsActions.OnLongClick].label)
        assertEquals(true, row.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("3 panes"))
    }
}
