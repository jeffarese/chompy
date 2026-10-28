package com.jeffarese.rexmobile.terminal

import android.os.Looper
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import com.jeffarese.rexmobile.rex.ControlConnection
import com.jeffarese.rexmobile.terminal.emulator.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Drives Claude Code through [RemoteTerminal] on a real Rex server: reads its footer, answers a
 * permission prompt and a question with [RemoteTerminal.choose], two questions at once as the question
 * card answers them (tabs, an answer of one's own, ticks and the review), interrupts a turn with Escape, and
 * answers a shell's [y/N] for a line read and for a single-key read. Skipped unless REX_LIVE_HOST is set. It uses a few short turns of Haiku
 * in a fresh directory (REX_LIVE_WORKDIR on the server, or a new temporary one), puts the effort
 * level back as it found it, and destroys its session at the end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LivePromptTest {
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    private var terminal: RemoteTerminal? = null

    @Test
    fun claudeCodePromptsAndKeys() {
        val address = System.getenv("REX_LIVE_HOST")
        assumeTrue("set REX_LIVE_HOST to run", !address.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = HostConnection(HostConfig(label = "live", url = ControlConnection.normalizeHostUrl(address!!)), http, scope) {}
        val (sid, bid) = runBlocking {
            host.connect()
            withTimeout(20_000) { host.state.first { it.status == ConnStatus.ONLINE } }
            val sid = host.newSession("rex-mobile-prompts-itest")!!
            val view = withTimeout(10_000) {
                host.state.first { st -> st.views[sid]?.windows?.firstOrNull()?.blocks?.isNotEmpty() == true }.views[sid]!!
            }
            sid to view.windows!!.first().blocks.first().blockId
        }
        var effortToRestore: String? = null
        try {
            runBlocking {
                host.ownBlock(sid, bid)
                host.blockMethod(sid, bid, "resize", buildJsonObject {
                    put("columns", 60)
                    put("rows", 40)
                })
            }
            val t = RemoteTerminal(host, sid, bid, http).also { terminal = it }
            showOnScreen(t)
            t.start()
            waitFor("the first screen") { !t.syncing.value && screen().any { it.isNotBlank() } }
            pump(800) // let the shell draw its prompt
            val shell = foreground(host, sid, bid)
            println("shell: $shell")

            val dir = System.getenv("REX_LIVE_WORKDIR")?.let { "mkdir -p '$it' && cd '$it'" } ?: "cd \"$(mktemp -d)\""
            t.typeText("$dir && command claude --model claude-haiku-5-5\r")
            waitFor("Claude Code's footer", 60_000) { info().claudeMode != null }
            println("started: ${info()}")

            // Shift+tab cycles the permission mode; prompts only show in manual mode.
            repeat(5) {
                if (info().claudeMode == "manual mode") return@repeat
                val before = info().claudeMode
                t.sendKey(RemoteKey.SHIFT_TAB)
                waitFor("the mode after $before") { info().claudeMode != before }
                println("mode: ${info().claudeMode}")
            }
            assertEquals("manual mode", info().claudeMode)

            // A slash command pasted and then entered sets the effort, and the screen says so.
            val initialEffort = info().claudeEffort ?: "medium"
            val other = if (initialEffort == "low") "high" else "low"
            effortToRestore = initialEffort
            t.paste("/effort $other")
            t.sendKey(RemoteKey.ENTER)
            waitFor("effort $other") { info().claudeEffort == other }
            t.paste("/effort $initialEffort")
            t.sendKey(RemoteKey.ENTER)
            waitFor("effort $initialEffort") { info().claudeEffort == initialEffort }
            effortToRestore = null
            println("effort: $other, then back to $initialEffort")

            // A Bash permission prompt, answered by typing the option's number.
            t.paste("Run this exact shell command with the Bash tool: date > rex-mobile-itest.txt")
            t.sendKey(RemoteKey.ENTER)
            waitFor("the permission prompt", 90_000) { info().prompt?.question == "Do you want to proceed?" }
            val permission = info().prompt!!
            println("permission: $permission")
            assertEquals("Yes", permission.options.first().label)
            assertTrue(permission.options.first().selected)
            assertEquals("Bash command", permission.context.firstOrNull())
            assertTrue(permission.context.toString(), permission.context.any { "rex-mobile-itest.txt" in it })

            // A reconnect drops the card at once, and a tap on it meanwhile goes nowhere. It comes
            // back once the stream is live again and the screen has been read.
            t.reconnectNow()
            assertNull(info().prompt)
            t.choose(permission, permission.options.first())
            waitFor("the prompt after reconnecting", 30_000) { info().prompt?.question == "Do you want to proceed?" }
            assertFalse(listDir(host, sid, bid).contains("rex-mobile-itest.txt"))
            println("reconnected: ${t.streamState.value}, prompt back")
            val again = info().prompt!!
            t.choose(again, again.options.first())
            waitFor("the prompt to close") { info().prompt == null }
            waitFor("the file", 60_000) { listDir(host, sid, bid).contains("rex-mobile-itest.txt") }
            waitFor("the turn to end", 60_000) { !info().interruptible && info().claudeMode != null && text().contains("done") }

            // An AskUserQuestion menu, answered by number as well.
            t.paste("Use the AskUserQuestion tool to ask me which of three colours I prefer: red, green or blue.")
            t.sendKey(RemoteKey.ENTER)
            waitFor("the question", 90_000) { info().prompt?.options?.any { it.label.equals("Green", ignoreCase = true) } == true }
            val question = info().prompt!!
            println("question: $question")
            println("question rows (style of each row's first letter):\n${styledRows(question.question ?: "1.")}")
            t.choose(question, question.options.first { it.label.equals("Green", ignoreCase = true) })
            waitFor("the answer", 30_000) { text().contains("→ Green") }
            waitFor("the turn to end", 60_000) { !info().interruptible && info().claudeMode != null && info().prompt == null }

            // Two questions at once, as the question card answers them: tabs, an answer of one's
            // own to a single choice, ticks and a typed answer in a multi-select one, and the review.
            t.paste(
                "Use the AskUserQuestion tool once, with two questions: first a single-choice question with header \"Colour\" " +
                    "asking which of red, green or blue I prefer, then a multiSelect question with header \"Fruits\" asking " +
                    "which of apple, banana and cherry I like.",
            )
            t.sendKey(RemoteKey.ENTER)
            waitFor("the questions", 90_000) { info().prompt?.tabs?.size == 3 }
            val colour = info().prompt!!
            println("tabs: ${colour.tabs}")
            assertEquals(0, colour.currentTab)
            t.goToTab(colour, 1)
            waitFor("the Fruits tab") { info().prompt?.currentTab == 1 }
            t.goToTab(info().prompt!!, 0)
            waitFor("back on Colour") { info().prompt?.currentTab == 0 }
            t.answerOwn(info().prompt!!, "deep purple")
            waitFor("the fruits") { info().prompt?.multiSelect == true }
            assertEquals(QuestionTab.State.ANSWERED, info().prompt!!.tabs[0].state)
            val fruits = info().prompt!!
            t.choose(fruits, fruits.options.first { it.label.equals("Cherry", ignoreCase = true) })
            waitFor("cherry ticked") { info().prompt?.options?.firstOrNull { it.label.equals("Cherry", ignoreCase = true) }?.checked == true }
            t.answerOwn(info().prompt!!, "figs")
            waitFor("figs typed and ticked") { info().prompt?.options?.firstOrNull { it.role == PromptOption.Role.OWN }?.let { it.typed == "figs" && it.checked == true } == true }
            val ticked = info().prompt!!
            t.choose(ticked, ticked.options.first { it.role == PromptOption.Role.SUBMIT })
            waitFor("the review") { info().prompt?.review?.size == 2 }
            val review = info().prompt!!
            println("review: ${review.review}")
            assertEquals("deep purple", review.review[0].answer)
            assertTrue(review.review[1].answer, "figs" in review.review[1].answer && "Cherry" in review.review[1].answer)
            t.choose(review, review.options.first())
            waitFor("the answers sent", 30_000) { text().contains("deep purple") && info().prompt == null }
            waitFor("the turn to end", 60_000) { !info().interruptible && info().claudeMode != null && info().prompt == null }

            // Escape, sent as a key event, interrupts a turn. The spinner hides while the reply
            // streams, so this also relies on Claude Code reporting that it's working.
            t.paste("Write a 600-word essay about rivers as plain text. Don't use any tools.")
            t.sendKey(RemoteKey.ENTER)
            waitFor("the turn to start", 60_000) { info().interruptible }
            // Escape before the reply starts would put the message back in the input box instead.
            waitFor("the reply", 60_000) { replyBelow("600-word essay about rivers") }
            pump(1000)
            println("working: interruptible=${info().interruptible}, spinner line: ${screen().lastOrNull { "…" in it && "│" !in it }}")
            assertTrue(info().interruptible)
            t.sendKey(RemoteKey.ESCAPE)
            waitFor("the interruption", 30_000) { text().contains("Interrupted") }
            waitFor("the turn to stop") { !info().interruptible }

            // Ctrl+C, also a key event, clears what was typed.
            t.typeText("half a thought")
            waitFor("the typed text") { text().contains("half a thought") }
            t.sendKey(RemoteKey.CTRL_C)
            waitFor("the input to clear") { !text().contains("half a thought") }

            // Leave Claude Code, then answer a shell's [y/N]. Claude Code takes a moment to exit
            // after its hint shows, and would eat what is typed meanwhile, so wait for the shell to
            // be in the foreground again, then clear whatever is left on its command line.
            pump(1500) // a second ctrl+c within a moment would exit
            t.paste("/exit")
            t.sendKey(RemoteKey.ENTER)
            waitFor("the shell", 30_000) {
                info().claudeMode == null && text().contains("claude --resume") && foreground(host, sid, bid) == shell
            }
            pump(500)
            t.sendKey(RemoteKey.ctrl('u'))

            // A line read: the answer and then Enter.
            t.typeText("read \"a?Continue? [y/N] \"; echo \"got:\$a\"\r")
            waitFor("the shell question") { info().prompt?.question == "Continue?" }
            val yesNo = info().prompt!!
            println("shell: $yesNo, input_mode: ${inputMode(host, sid, bid)}")
            assertEquals(ScreenPrompt.Kind.YES_NO, yesNo.kind)
            assertEquals(true, inputMode(host, sid, bid)["canonical"]?.let { (it as JsonPrimitive).content.toBoolean() })
            t.choose(yesNo, yesNo.options.first { it.label == "Yes" })
            waitFor("the shell's answer") { text().contains("got:y") }

            // A single-key read takes the answer alone; an Enter after it would land on the next prompt.
            t.typeText("read -q \"b?Overwrite? (y/n) \"; echo \" gotq:\$b\"\r")
            waitFor("the single-key question") { info().prompt?.question == "Overwrite?" }
            val single = info().prompt!!
            println("shell: $single, input_mode: ${inputMode(host, sid, bid)}")
            assertEquals(false, inputMode(host, sid, bid)["canonical"]?.let { (it as JsonPrimitive).content.toBoolean() })
            t.choose(single, single.options.first { it.label == "Yes" })
            waitFor("the shell's answer") { text().contains("gotq:y") }
            pump(1500)
            val lines = screen()
            val mark = lines.last { "read -q" in it }.trim().substringBefore(' ')
            val prompts = lines.drop(lines.indexOfLast { "gotq:y" in it } + 1).count { it.trim().startsWith(mark) }
            println("after the single-key answer:\n${lines.dropWhile { "read -q" !in it }.joinToString("\n")}")
            assertEquals("prompts below the answer", 1, prompts)
        } catch (e: Throwable) {
            println("screen at failure:\n${runCatching { text() }.getOrNull()}")
            throw e
        } finally {
            terminal?.let { t ->
                if (info().claudeMode != null) {
                    effortToRestore?.let {
                        t.paste("/effort $it")
                        t.sendKey(RemoteKey.ENTER)
                        pump(1500)
                    }
                    t.paste("/exit")
                    t.sendKey(RemoteKey.ENTER)
                    pump(2000)
                }
                t.close()
            }
            runBlocking {
                runCatching { host.call("session.destroy", buildJsonObject { put("session_id", sid) }) }
                host.disconnect()
            }
            scope.cancel()
        }
    }

    private fun info() = terminal!!.screenInfo.value

    private fun screen(): List<String> = terminal!!.session.emulator?.let { ScreenReader.visibleLines(it) }.orEmpty()

    private fun text() = screen().joinToString("\n")

    /** Robolectric runs the main looper only when told, so keep it going while real time passes. */
    private fun pump(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            Thread.sleep(25)
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what; last read: ${info()}")
            pump(50)
        }
    }

    /** True once a reply ("⏺ ...") shows below the message holding [text]. */
    private fun replyBelow(text: String): Boolean {
        val lines = screen()
        val asked = lines.indexOfFirst { text in it }
        return asked >= 0 && lines.drop(asked + 1).any { it.startsWith("⏺") }
    }

    /** The name of the block's foreground process, such as zsh at its prompt. */
    private fun foreground(host: HostConnection, sid: String, bid: String): String? = runBlocking {
        val r = runCatching { host.blockMethod(sid, bid, "process") as JsonObject }.getOrNull()
        ((r?.get("foreground") as? JsonObject)?.get("name") as? JsonPrimitive)?.content
    }

    private fun inputMode(host: HostConnection, sid: String, bid: String): JsonObject = runBlocking {
        host.blockMethod(sid, bid, "input_mode") as JsonObject
    }

    /** The rows from the one holding [from] down, each with the colour and effects of its first letter. */
    private fun styledRows(from: String): String {
        val e = terminal!!.session.emulator!!
        val lines = ScreenReader.visibleLines(e)
        val start = lines.indexOfFirst { from in it }.coerceAtLeast(0)
        return (start until lines.size).filter { lines[it].any(Char::isLetter) }.joinToString("\n") { row ->
            val style = e.screen.getStyleAt(row, lines[row].indexOfFirst(Char::isLetter))
            "fg=${TextStyle.decodeForeColor(style).toUInt().toString(16)} effect=${TextStyle.decodeEffect(style)} | ${lines[row]}"
        }
    }

    private fun listDir(host: HostConnection, sid: String, bid: String): List<String> = runBlocking {
        val r = host.blockMethod(sid, bid, "list_dir", buildJsonObject { put("files", true) }) as JsonObject
        (r["entries"] as? JsonArray).orEmpty().mapNotNull { ((it as JsonObject)["name"] as? JsonPrimitive)?.content }
    }
}
