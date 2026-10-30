package com.jeffarese.rexmobile.model

import com.jeffarese.rexmobile.rex.ControlConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Reads and answers prompts with [PanePrompts] on a real Rex server, as a notification's buttons do.
 * Claude isn't run: each prompt is staged by printing a screen captured from Claude Code, with its
 * styles, in a throwaway shell that then reads one key and prints it, so the answer shows as
 * "got-", a tag and the key. Skipped unless REX_LIVE_HOST is set; its session is destroyed at the end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LivePanePromptsTest {
    @Test
    fun stagedPromptsAreReadAndAnswered() {
        val address = System.getenv("REX_LIVE_HOST")
        assumeTrue("set REX_LIVE_HOST to run", !address.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()
        val host = HostConnection(HostConfig(label = "live", url = ControlConnection.normalizeHostUrl(address!!)), http, scope) {}
        val prompts = PanePrompts()
        runBlocking {
            host.connect()
            withTimeout(20_000) { host.state.first { it.status == ConnStatus.ONLINE } }
            val sid = host.newSession("rex-mobile-notify-itest")!!
            try {
                val bid = withTimeout(10_000) {
                    host.state.first { st -> st.views[sid]?.windows?.firstOrNull()?.blocks?.isNotEmpty() == true }.views[sid]!!
                }.windows!!.first().blocks.first().blockId
                host.ownBlock(sid, bid)
                host.blockMethod(sid, bid, "resize", buildJsonObject {
                    put("columns", 46)
                    put("rows", 30)
                })
                waitFor("the shell") { text(host, sid, bid).isNotBlank() }
                delay(800)

                // A permission prompt, answered No: its fourth option.
                stage(host, sid, bid, "a", permission("date > out2.txt"))
                waitFor("the staged prompt") { "Tab to amend" in text(host, sid, bid) }
                println("staged permission as vt, base64: ${vt(host, sid, bid)}")
                val asked = prompts.read(host, sid, bid)
                assertNotNull("read: ${text(host, sid, bid)}", asked)
                assertEquals("Do you want to proceed?", asked!!.prompt.question)
                assertEquals(listOf("Bash command", "date > out2.txt"), asked.prompt.context)
                assertEquals(4, asked.answers.size)
                assertEquals(PanePrompts.Answer.ANSWERED, prompts.answer(host, asked, asked.answers.last()))
                waitFor("the shell's key") { "got-a:4" in text(host, sid, bid) }

                // One of Claude's questions on its own, with its tab and descriptions styled as Claude draws them.
                stage(host, sid, bid, "b", question())
                waitFor("the staged question") { "Chat about this" in text(host, sid, bid) }
                println("staged question as vt, base64: ${vt(host, sid, bid)}")
                val question = prompts.read(host, sid, bid)!!
                assertEquals(listOf("Red", "Green", "Blue"), question.answers.map { it.label })
                assertEquals(0, question.prompt.currentTab)
                assertEquals(PanePrompts.Answer.ANSWERED, prompts.answer(host, question, question.answers[1]))
                waitFor("the shell's key") { "got-b:2" in text(host, sid, bid) }

                // A prompt that gave way to another, for another command, isn't answered.
                stage(host, sid, bid, "c", permission("date > out2.txt"), then = permission("rm -rf build"))
                waitFor("the first prompt") { "date > out2.txt" in text(host, sid, bid) }
                val before = prompts.read(host, sid, bid)!!
                waitFor("the second prompt") { "rm -rf build" in text(host, sid, bid) }
                assertEquals(PanePrompts.Answer.CHANGED, prompts.answer(host, before, before.answers.first()))
                delay(500)
                assertFalse("no key typed", Regex("""got-c:\d""") in text(host, sid, bid))
                val after = prompts.read(host, sid, bid)!!
                assertEquals(listOf("Bash command", "rm -rf build"), after.prompt.context)
                assertEquals(PanePrompts.Answer.ANSWERED, prompts.answer(host, after, after.answers.last()))
                waitFor("the shell's key") { "got-c:4" in text(host, sid, bid) }
            } finally {
                runCatching { host.call("session.destroy", buildJsonObject { put("session_id", sid) }) }
                host.disconnect()
                scope.cancel()
            }
        }
    }

    /**
     * Clear the screen and print [screen], then, if given, [then] a few seconds later in its place,
     * and read one key without echoing it, then print it after "got-" and [tag].
     */
    private suspend fun stage(host: HostConnection, sid: String, bid: String, tag: String, screen: String, then: String? = null) {
        fun show(s: String) = "clear; printf %s ${Base64.getEncoder().encodeToString(s.toByteArray())} | base64 -d"
        val command = listOfNotNull(show(screen), then?.let { "sleep 4; ${show(it)}" }).joinToString("; ") +
            "; stty -icanon -echo; k=\$(dd bs=1 count=1 2>/dev/null); stty icanon echo; echo; echo got-$tag:\$k\r"
        host.blockMethod(sid, bid, "write", buildJsonObject { put("data", command) })
    }

    /** Claude Code's Bash permission prompt at 46 columns, as captured, for [command]. */
    private fun permission(command: String) = screen("claude-permission-bash-phone").replace("date > out2.txt", command)

    /** Claude Code's question about colours, with the tab on screen and the descriptions in Claude's colours. */
    private fun question(): String {
        val tab = "\u001b[38;2;0;0;0m\u001b[48;2;177;185;249m"
        val grey = "\u001b[38;2;153;153;153m"
        val plain = "\u001b[0m"
        val highlight = "\u001b[38;2;177;185;249m"
        return screen("claude-ask-colour").lines().joinToString("\n") { line ->
            when {
                line == " ☐ Colour" -> "$tab ☐ Colour $plain"
                line.startsWith("❯ 1.") -> "$highlight$line$plain"
                line.startsWith("     ") -> "$grey$line$plain"
                else -> line
            }
        }
    }

    /** A captured screen's rows, without the header or blank rows at the end. */
    private fun screen(name: String) =
        javaClass.classLoader!!.getResource("screens/$name.txt")!!.readText().substringAfter('\n').trimEnd()

    private suspend fun text(host: HostConnection, sid: String, bid: String): String =
        (host.blockMethod(sid, bid, "format", buildJsonObject { put("format", "text") }).jsonObject["content"] as? JsonPrimitive)?.content.orEmpty()

    /** The redraw the app reads, base64, to keep as a fixture. */
    private suspend fun vt(host: HostConnection, sid: String, bid: String): String {
        val content = (host.blockMethod(sid, bid, "format", buildJsonObject {
            put("format", "vt")
            put("extra_cursor", true)
            put("extra_modes", true)
            put("extra_scrolling_region", true)
            put("extra_style", true)
            put("extra_charsets", true)
            put("extra_tabstops", true)
        }).jsonObject["content"] as? JsonPrimitive)?.content.orEmpty()
        return Base64.getEncoder().encodeToString(content.toByteArray())
    }

    private suspend fun waitFor(what: String, timeoutMs: Long = 15_000, done: suspend () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < until)
            delay(100)
        }
    }
}
