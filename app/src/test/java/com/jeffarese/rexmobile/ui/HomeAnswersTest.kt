package com.jeffarese.rexmobile.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.FakeRex
import com.jeffarese.rexmobile.FakeRex.Companion.status
import com.jeffarese.rexmobile.model.AppModel
import com.jeffarese.rexmobile.model.HostConnection
import com.jeffarese.rexmobile.rex.TERMINAL_CREATOR
import com.jeffarese.rexmobile.rex.obj
import com.jeffarese.rexmobile.rex.str
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Claude answered from its card on Home's board, against a fake server whose b1 shows a staged
 * permission prompt: which cards are read and when, the answers they offer, and what each answer's
 * result says.
 *
 * Drawn with native graphics: a card between others in its group has only some corners rounded, and
 * without them taps can't find what is inside its clip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeAnswersTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val rex = FakeRex("b1", "b2", "b3").apply {
        columns = 46
        rows = 30
        block("b1").screen = vt("claude-permission-bash-staged")
    }
    // An empty saved list, so the store doesn't seed this build's preset hosts beside the fake one.
    private val context: Context = ApplicationProvider.getApplicationContext<Context>().also {
        it.getSharedPreferences("hosts", Context.MODE_PRIVATE).edit().putString("list", "[]").commit()
    }
    private val model = AppModel(context, rex)
    private val at = Instant.now().minusSeconds(60).toString()
    private val alwaysAllow = "Yes, and always allow access to /private/tmp/claude-502/-Users-dev-person-workspace-rex-mobile/" +
        "34274be2-8e3d-4e8e-b42e-fe0949a9fa8d/scratchpad/prompts-live/probe1 from this project"

    /** The panes whose screens were read, a block id each time. */
    private val read = CopyOnWriteArrayList<String>()
    /** The keys pressed in the pane, as "digit_4 press". */
    private val pressed = CopyOnWriteArrayList<String>()
    private val opened = CopyOnWriteArrayList<Target>()
    /** Run as a key goes down in the pane, such as to show what Claude does next. */
    @Volatile private var onPress: () -> Unit = {}
    /** Answers a request before the fake server's own answers, or null to leave it to them. */
    @Volatile private var fail: (String) -> CompletableFuture<JsonElement>? = { null }

    @After
    fun tearDown() {
        model.connections.value.forEach { it.disconnect() }
        model.scope.cancel()
    }

    /** Claude Code's record: blocked on a permission unless said otherwise. */
    private fun claude(msg: String? = null) = buildJsonObject {
        put("id", "claude")
        put("state", "blocked")
        put("kind", "permission")
        put("title", "Fix the tests")
        put("app", "claude-code")
        if (msg != null) put("msg", msg)
        put("updated_at", at)
    }

    /**
     * Home on a host where Claude Code in b1 asks for permission, b2 works, and b3, another program,
     * waits for the user; once b1 offers its answers.
     */
    private fun show() {
        rex.block("b1").statuses = listOf(claude())
        rex.block("b2").statuses = listOf(status("agent", "working", title = "Task b2", at = at))
        rex.block("b3").statuses = listOf(status("agent", "blocked", title = "Task b3", at = at))
        rex.handle = { method, payload -> handle(method, payload) }
        model.hostStore.add("Studio", "fake.test")
        rule.waitUntil(5_000) { model.connections.value.isNotEmpty() }
        conn().connect()
        rule.setContent { RexTheme { HomeScreen(model, onOpen = { opened += it }, onHosts = {}) } }
        rule.waitUntil(10_000) { listOf("b1", "b2", "b3").all { rule.onAllNodesWithTag("agent:$it").fetchSemanticsNodes().isNotEmpty() } }
        rule.waitUntil(10_000) { offers("No") }
    }

    private fun handle(method: String, payload: JsonObject): CompletableFuture<JsonElement> {
        fail(method)?.let { return it }
        when (method) {
            "$TERMINAL_CREATOR.size" -> read += payload.str("block_id")
            "block.event" -> payload.obj("event")?.obj("payload")?.let { key ->
                pressed += "${key.str("key")} ${key.str("action")}"
                if (key.str("action") == "press") onPress()
            }
        }
        return rex.answer(method, payload)
    }

    private fun conn(): HostConnection = model.connections.value.single()
    private fun offers(label: String) = rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
    private fun shows(text: String) = rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun statusChanged(bid: String, record: JsonObject) =
        rex.controls.last().blockEvent(bid, "program_status_changed", buildJsonObject { put("record", record) })

    @Test
    fun aClaudeCardOffersItsNotificationsAnswersAndPressesTheOneTapped() {
        show()
        // What it asks to run, which the card's line doesn't say.
        assertTrue(shows("date > out2.txt"))
        // As the notification offers them: of four, the first two and No.
        assertTrue(offers("Yes") && offers(alwaysAllow))
        assertFalse(shows("switch to auto mode"))
        rule.onNodeWithText("No").assertHasClickAction().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        // Only Claude's card is read; the other program waiting has no answers to offer.
        assertEquals(listOf("b1"), read.toList())

        // Taken, Claude goes on and the prompt goes.
        onPress = { rex.block("b1").screen = "\u001b[H⏺ Done." }
        rule.onNodeWithText("No").performClick()
        rule.waitUntil(10_000) { offers("Answered: No") }
        assertEquals(listOf("digit_4 press", "digit_4 release"), pressed.toList())
        // Not offered again for a prompt that has gone.
        assertFalse(offers("Yes"))
    }

    @Test
    fun aCardIsReadAgainWhenItsStatusChangesNotOnEveryUpdate() {
        show()
        assertEquals(listOf("b1"), read.toList())

        // Another agent's progress, and Claude's own pane retitled: the host updates, b1's status doesn't.
        statusChanged("b2", buildJsonObject {
            put("id", "agent")
            put("state", "working")
            put("title", "Task b2")
            put("progress", 40)
            put("updated_at", at)
        })
        rex.controls.last().blockEvent("b1", "title_changed", buildJsonObject { put("title", "claude: fixing") })
        rule.waitUntil(5_000) { conn().state.value.blocks.let { it["b2"]?.status?.progress == 40 && it["b1"]?.title == "claude: fixing" } }
        rule.waitForIdle()
        assertEquals(listOf("b1"), read.toList())

        // Claude says what it waits for: read again. The card says the command now, so it isn't shown twice.
        statusChanged("b1", claude(msg = "Run date > out2.txt?"))
        rule.waitUntil(5_000) { read.size == 2 && offers("No") }
        assertEquals(listOf("b1", "b1"), read.toList())
        assertFalse(shows("Bash command"))
    }

    @Test
    fun noReadWhileHomeIsOffScreen() {
        show()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        statusChanged("b1", claude(msg = "Run the tests?"))
        rule.waitUntil(5_000) { conn().state.value.blocks["b1"]?.status?.msg == "Run the tests?" }
        rule.waitForIdle()
        assertEquals(listOf("b1"), read.toList())

        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(5_000) { read.size == 2 }
    }

    @Test
    fun aScreenThatChangedIsLeftForThePaneAndReadAgain() {
        show()
        rex.block("b1").screen = vt("claude-permission-bash-staged").replace("date > out2.txt", "rm -rf build")
        rule.onNodeWithText("Yes").performClick()
        rule.waitUntil(10_000) { offers("Not answered: the screen has changed since") }
        assertEquals(emptyList<String>(), pressed.toList())
        // What it asks now, offered in place of what it asked.
        rule.waitUntil(10_000) { shows("rm -rf build") && offers("Yes") }
        assertFalse(shows("date > out2.txt"))

        rule.onNodeWithText("Open").performClick()
        rule.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals(listOf("b1"), opened.map { it.blockId })
    }

    @Test
    fun anUnreachableHostIsSaid() {
        show()
        fail = { method -> if (method == "$TERMINAL_CREATOR.size") CompletableFuture.failedFuture(FakeRex.Fail("internal", "gone")) else null }
        rule.onNodeWithText("Yes").performClick()
        rule.waitUntil(10_000) { offers("Not answered: couldn't reach Studio") }
        assertEquals(emptyList<String>(), pressed.toList())
        rule.onNodeWithText("Open").assertHasClickAction()
        // Read again, it can't be: the card is as it would be without answers.
        rule.waitUntil(10_000) { !offers("Yes") }
    }

    @Test
    fun anAnswerClaudeDidntTakeIsSaid() {
        show()
        rule.onNodeWithText("No").performClick()
        // Under way: the others take no taps, and the one tapped says so.
        rule.onNodeWithText("Yes").assertIsNotEnabled()
        rule.onNodeWithText("No").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Answering"))
        rule.waitUntil(10_000) { offers("Sent No, but Claude didn't seem to take it") }
        assertEquals(listOf("digit_4 press", "digit_4 release"), pressed.toList())
        rule.onNodeWithText("Open").assertHasClickAction()
    }

    private fun vt(name: String) = javaClass.classLoader!!.getResource("screens/$name.vt")!!.readText()
}
