package com.jeffarese.rexmobile.model

import com.jeffarese.rexmobile.FakeRex
import com.jeffarese.rexmobile.rex.TERMINAL_CREATOR
import com.jeffarese.rexmobile.rex.obj
import com.jeffarese.rexmobile.rex.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Claude's prompts read off a pane's screen over the control connection, and answered, against a
 * fake server. The staged screens are what a live server's terminal.format gave for Claude Code's
 * prompts printed in a shell (see LivePanePromptsTest), cut to the screen as the app cuts them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanePromptsTest {
    private val rex = FakeRex().apply {
        columns = 46
        rows = 30
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val host = HostConnection(HostConfig(label = "fake", url = "https://fake.test"), rex, scope) {}
    private val prompts = PanePrompts()
    private val alwaysAllow = "Yes, and always allow access to /private/tmp/claude-502/-Users-dev-person-workspace-rex-mobile/" +
        "34274be2-8e3d-4e8e-b42e-fe0949a9fa8d/scratchpad/prompts-live/probe1 from this project"

    /** The keys pressed in the pane, as "digit_4 press". */
    private val pressed = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        host.disconnect()
        scope.cancel()
    }

    private fun online() {
        host.connect()
        val until = System.currentTimeMillis() + 10_000
        while (host.state.value.status != ConnStatus.ONLINE) {
            check(System.currentTimeMillis() < until) { "never online" }
            Thread.sleep(20)
        }
    }

    private fun show(screen: String) {
        rex.block("b1").screen = screen
    }

    private fun read(timeoutMs: Long = PanePrompts.READ_MS) = runBlocking { prompts.read(host, "s1", "b1", timeoutMs) }

    @Test
    fun aPermissionPromptWithItsCommand() {
        online()
        show(vt("claude-permission-bash-staged"))
        val p = read()!!
        assertEquals("Do you want to proceed?", p.prompt.question)
        assertEquals(listOf("Bash command", "date > out2.txt"), p.prompt.context)
        assertTrue(p.permission)
        assertEquals(listOf("Yes", alwaysAllow, "Yes, and switch to auto mode · auto mode handles these prompts for you", "No"), p.answers.map { it.label })
        assertEquals(Triple(host.id, "s1", "b1"), Triple(p.hostId, p.sessionId, p.blockId))
    }

    @Test
    fun onlyTheEndOfALongHistoryIsRead() {
        online()
        val staged = vt("claude-permission-bash-staged")
        val opening = staged.substringBefore("  runs first")
        val history = (1..6000).joinToString("") { "history line $it\r\n" }
        show(opening + history + staged.removePrefix(opening))
        val p = read()!!
        assertEquals(listOf("Bash command", "date > out2.txt"), p.prompt.context)
        val readBytes = rex.blobReads.sumOf { it.last - it.first + 1 }
        assertTrue("read $readBytes bytes of ${history.length}", readBytes < history.length / 4)
    }

    @Test
    fun aQuestionOnItsOwnOffersItsAnswers() {
        online()
        show(vt("claude-ask-colour-staged"))
        val p = read()!!
        assertFalse(p.permission)
        assertEquals("Which of these three colours do you prefer?", p.prompt.question)
        // Not "Type something." or "Chat about this", which take the terminal.
        assertEquals(listOf("Red", "Green", "Blue"), p.answers.map { it.label })
        assertEquals(listOf(1, 2, 3), p.answers.map { it.number })
        // Read with its styles: the tab on screen, and the descriptions apart from the labels.
        assertEquals(0, p.prompt.currentTab)
        assertEquals("A calm, natural colour associated with growth and balance.", p.answers[1].detail)
    }

    @Test
    fun whatTheTerminalsCardsAnswerIsLeftToThem() {
        online()
        // Ticks, a question among others, and a dialog that doesn't say what it is about.
        for (name in listOf("claude-ask-multi", "claude-ask-two-colour", "claude-trust-synthetic")) {
            show(captured(name))
            assertNull(name, read())
        }
    }

    @Test
    fun aScreenWithoutAPromptIsReadAgainUntilTheTimeRunsOut() {
        online()
        show(captured("claude-idle-manual"))
        assertNull(read(timeoutMs = 1_600))
        assertTrue(rex.count("$TERMINAL_CREATOR.format") >= 2)
        // Drawn a moment after its agent said it waits: found on a later read.
        show(captured("claude-idle-manual"))
        rex.columns = 46
        rex.rows = 30
        val later = CompletableFuture.runAsync {
            Thread.sleep(800)
            show(vt("claude-permission-bash-staged"))
        }
        assertNotNull(read())
        later.join()
    }

    @Test
    fun anAnswerPressesItsNumberWhileTheSamePromptShows() {
        online()
        show(vt("claude-permission-bash-staged"))
        val p = read()!!
        pressKeys(onPress = { show(captured("claude-idle-manual")) })
        val result = runBlocking { prompts.answer(host, p, p.answers.last()) }
        assertEquals(PanePrompts.Answer.ANSWERED, result)
        assertEquals(listOf("digit_4 press", "digit_4 release"), pressed)
    }

    @Test
    fun nothingIsPressedOnceThePromptHasChanged() {
        online()
        show(vt("claude-permission-bash-staged"))
        val p = read()!!
        pressKeys()
        // The same question and options, about another command.
        show(vt("claude-permission-bash-staged").replace("date > out2.txt", "rm -rf build"))
        assertEquals(PanePrompts.Answer.CHANGED, runBlocking { prompts.answer(host, p, p.answers.first()) })
        // Or another prompt altogether, or none.
        show(vt("claude-ask-colour-staged"))
        assertEquals(PanePrompts.Answer.CHANGED, runBlocking { prompts.answer(host, p, p.answers.first()) })
        show(captured("claude-idle-manual"))
        assertEquals(PanePrompts.Answer.CHANGED, runBlocking { prompts.answer(host, p, p.answers.first()) })
        assertEquals(emptyList<String>(), pressed)
    }

    @Test
    fun aPressThatDoesntTakeSaysSo() {
        online()
        show(vt("claude-permission-bash-staged"))
        val p = read()!!
        // The screen still shows the prompt after the press, as when Claude ignored it.
        pressKeys()
        assertEquals(PanePrompts.Answer.UNCONFIRMED, runBlocking { prompts.answer(host, p, p.answers.first()) })
        assertEquals(listOf("digit_1 press", "digit_1 release"), pressed)
    }

    @Test
    fun aSecondAnswerWhileOneIsUnderWayIsRefused() {
        online()
        show(vt("claude-permission-bash-staged"))
        val p = read()!!
        val held = CompletableFuture<JsonElement>()
        pressKeys(reply = { held })
        runBlocking {
            val first = async(Dispatchers.Default) { prompts.answer(host, p, p.answers.first()) }
            while (pressed.isEmpty()) Thread.sleep(10)
            assertEquals(PanePrompts.Answer.BUSY, prompts.answer(host, p, p.answers.last()))
            show(captured("claude-idle-manual"))
            held.complete(JsonObject(emptyMap()))
            assertEquals(PanePrompts.Answer.ANSWERED, first.await())
        }
        assertEquals(listOf("digit_1 press", "digit_1 release"), pressed)
    }

    @Test
    fun aHostThatIsntConnectedCantBeAnswered() {
        val asked = PanePrompt("fake", "s1", "b1", PanePrompts.screenOf(vt("claude-permission-bash-staged"), 46, 30).prompt!!, 0)
        assertEquals(PanePrompts.Answer.UNREACHABLE, runBlocking { prompts.answer(host, asked, asked.answers.first()) })
        assertNull(read(timeoutMs = 1_000))
    }

    /**
     * Note keys pressed in the pane in [pressed], with [onPress] run as one goes down, and answer
     * each with [reply].
     */
    private fun pressKeys(onPress: () -> Unit = {}, reply: () -> CompletableFuture<JsonElement> = { CompletableFuture.completedFuture(JsonObject(emptyMap())) }) {
        rex.handle = { method, payload ->
            if (method == "block.event") {
                val event = payload.obj("event")
                val key = event?.obj("payload")
                if (event?.str("type") == "key" && key != null) {
                    pressed += "${key.str("key")} ${key.str("action")}"
                    if (key.str("action") == "press") onPress()
                }
                reply()
            } else {
                rex.answer(method, payload)
            }
        }
    }

    /** A staged screen as a live server's terminal.format gave it. */
    private fun vt(name: String) = javaClass.classLoader!!.getResource("screens/$name.vt")!!.readText()

    /** A screen captured as text, redrawn as escape sequences, with the fake's size set to its own. */
    private fun captured(name: String): String {
        val text = javaClass.classLoader!!.getResource("screens/$name.txt")!!.readText()
        val header = text.substringBefore('\n')
        rex.columns = Regex("""cols=(\d+)""").find(header)!!.groupValues[1].toInt()
        rex.rows = Regex("""rows=(\d+)""").find(header)!!.groupValues[1].toInt()
        return "\u001b[H" + text.substringAfter('\n').split('\n').take(rex.rows).joinToString("\r\n")
    }
}
