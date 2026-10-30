package com.jeffarese.rexmobile.terminal

import android.os.Looper
import com.jeffarese.rexmobile.model.ConnStatus
import com.jeffarese.rexmobile.model.HostConfig
import com.jeffarese.rexmobile.model.HostConnection
import com.jeffarese.rexmobile.rex.ControlConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
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
 * Presses a "You should know" note's buttons through [RemoteTerminal] on a real Rex server, and
 * reads an explanation taller than Claude's panel: with the wheel in fullscreen, and with the keys
 * in the classic renderer.
 *
 * Claude Code can't be asked for a note, so one must be staged in the plugin's store first: an
 * entry in "sessions" with a new session id, a tag and an "offer" view holding a line and an
 * explanation long enough to overflow 40 rows at 50 columns. Set REX_LIVE_HOST, and
 * REX_LIVE_FYI_SESSION to that id (REX_LIVE_FYI_CLASSIC_SESSION to a second one for the classic
 * renderer). For [understoodUnderAShell], set REX_LIVE_FYI_SHELL_SESSION and
 * REX_LIVE_FYI_SHELL_CLASSIC_SESSION to two more. For [aSurveyOverTheNote], set
 * REX_LIVE_FYI_SURVEY_SESSION and REX_LIVE_FYI_SURVEY_CLASSIC_SESSION to two whose view is already
 * "explained" (the line, the explanation as its text, and iteration 1), as Claude shows no offer
 * under a survey, with an explanation short enough for the survey to fit over it at 50x40. Claude
 * starts with each id in a new temporary directory, and the session is destroyed at the end; the
 * store entries are the caller's to remove.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveFyiTest {
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    private var terminal: RemoteTerminal? = null

    @Test
    fun claudeCodeNotes() {
        val fullscreenSession = System.getenv("REX_LIVE_FYI_SESSION")
        assumeTrue("set REX_LIVE_HOST and REX_LIVE_FYI_SESSION to run", !fullscreenSession.isNullOrBlank())
        val classicSession = System.getenv("REX_LIVE_FYI_CLASSIC_SESSION")
        onRex { t, resize ->
            t.typeText("cd \"$(mktemp -d)\" && command claude --session-id $fullscreenSession $CLAUDE $FULLSCREEN\r")
            walkThrough(t, fullscreen = true)
            exitClaude(t)

            if (!classicSession.isNullOrBlank()) {
                resize(50, 24)
                pump(500)
                t.typeText("clear; $CLASSIC command claude --session-id $classicSession $CLAUDE\r")
                walkThrough(t, fullscreen = false)
                exitClaude(t)
            }
        }
    }

    /**
     * A note with a background shell's row over it, which a plugin pins in the same panel, and
     * Claude's "new task? /clear to save … tokens" hint between it and the input box: open it, read
     * it whole and press Understood. Needs a plugin that pins background shells over the note;
     * Claude shows the hint at once here.
     */
    @Test
    fun understoodUnderAShell() {
        val fullscreenSession = System.getenv("REX_LIVE_FYI_SHELL_SESSION")
        assumeTrue("set REX_LIVE_HOST and REX_LIVE_FYI_SHELL_SESSION to run", !fullscreenSession.isNullOrBlank())
        val classicSession = System.getenv("REX_LIVE_FYI_SHELL_CLASSIC_SESSION")
        onRex { t, resize ->
            t.typeText("cd \"$(mktemp -d)\" && $HINT_AT_ONCE command claude --session-id $fullscreenSession $CLAUDE $FULLSCREEN\r")
            understoodUnderAShell(t, fullscreen = true)
            exitClaude(t)

            if (!classicSession.isNullOrBlank()) {
                resize(50, 24)
                pump(500)
                t.typeText("clear; $HINT_AT_ONCE $CLASSIC command claude --session-id $classicSession $CLAUDE\r")
                understoodUnderAShell(t, fullscreen = false)
                exitClaude(t)
            }
        }
    }

    /**
     * Claude's survey over an open note, which has no buttons meanwhile: dismissed from the phone,
     * after which the note's buttons come back and Understood is pressed. Claude shows the survey at
     * once here.
     */
    @Test
    fun aSurveyOverTheNote() {
        val fullscreenSession = System.getenv("REX_LIVE_FYI_SURVEY_SESSION")
        assumeTrue("set REX_LIVE_HOST and REX_LIVE_FYI_SURVEY_SESSION to run", !fullscreenSession.isNullOrBlank())
        val classicSession = System.getenv("REX_LIVE_FYI_SURVEY_CLASSIC_SESSION")
        onRex { t, _ ->
            t.typeText("cd \"$(mktemp -d)\" && $SURVEY_AT_ONCE command claude --session-id $fullscreenSession $CLAUDE $FULLSCREEN\r")
            surveyOverTheNote(t, fullscreen = true)
            exitClaude(t)

            // At 50x40 still: on a shorter screen, the classic renderer pushes the survey over the note out of sight.
            if (!classicSession.isNullOrBlank()) {
                pump(500)
                t.typeText("clear; $SURVEY_AT_ONCE $CLASSIC command claude --session-id $classicSession $CLAUDE\r")
                surveyOverTheNote(t, fullscreen = false)
                exitClaude(t)
            }
        }
    }

    /** A terminal in a new Rex session at 50x40 for [run], which may resize it; the session is destroyed after. */
    private fun onRex(run: (RemoteTerminal, resize: (columns: Int, rows: Int) -> Unit) -> Unit) {
        val address = System.getenv("REX_LIVE_HOST")
        assumeTrue("set REX_LIVE_HOST to run", !address.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = HostConnection(HostConfig(label = "live", url = ControlConnection.normalizeHostUrl(address!!)), http, scope) {}
        val (sid, bid) = runBlocking {
            host.connect()
            withTimeout(20_000) { host.state.first { it.status == ConnStatus.ONLINE } }
            val sid = host.newSession("rex-mobile-fyi-itest")!!
            val view = withTimeout(10_000) {
                host.state.first { st -> st.views[sid]?.windows?.firstOrNull()?.blocks?.isNotEmpty() == true }.views[sid]!!
            }
            sid to view.windows!!.first().blocks.first().blockId
        }
        try {
            runBlocking { host.ownBlock(sid, bid) }
            resize(host, sid, bid, 50, 40)
            val t = RemoteTerminal(host, sid, bid, http).also { terminal = it }
            showOnScreen(t)
            t.start()
            waitFor("the first screen") { !t.syncing.value && screen().any { it.isNotBlank() } }
            pump(800)
            run(t) { columns, rows -> resize(host, sid, bid, columns, rows) }
        } catch (e: Throwable) {
            println("screen at failure:\n${runCatching { text() }.getOrNull()}")
            throw e
        } finally {
            terminal?.let { t ->
                // An exit that fails here mustn't hide the failure that led here, nor skip the destroy below.
                if (info().claudeMode != null) runCatching { exitClaude(t) }
                t.close()
            }
            runBlocking {
                runCatching { host.call("session.destroy", buildJsonObject { put("session_id", sid) }) }
                host.disconnect()
            }
            scope.cancel()
        }
    }

    /** Open the staged note, read all of it, dismiss it, and dismiss the thanks. */
    private fun walkThrough(t: RemoteTerminal, fullscreen: Boolean) {
        val mode = if (fullscreen) "fullscreen" else "classic"
        waitFor("the note ($mode)", 60_000) { info().fyi?.actions?.isNotEmpty() == true && info().claudeInput == "" }
        assertEquals(fullscreen, info().fullscreen)
        val offer = info().fyi!!
        println("$mode offer: ${offer.tag} · ${offer.headline} ${offer.actions}")
        val learn = offer.actions.first { it.label == "Learn more" }

        // Pressed at once: the press waits out the moment Claude ignores digits in, rather than typing one into the box.
        var at = System.currentTimeMillis()
        t.pressFyi(offer, learn)
        waitFor("the explanation ($mode)", 30_000) { info().fyi?.body?.isNotEmpty() == true }
        println("$mode opened in ${System.currentTimeMillis() - at} ms; box: '${info().claudeInput}'")
        assertEquals("", info().claudeInput)

        val open = info().fyi!!
        println("$mode window ${open.shown} of ${open.rows.size} rows, complete: ${open.complete}")
        at = System.currentTimeMillis()
        t.readFyi()
        waitFor("the whole explanation ($mode)", 30_000) { info().fyi?.complete == true }
        waitFor("the panel's end ($mode)") { info().fyi!!.let { n -> n.actions.isNotEmpty() && n.actions.all(n::shows) } }
        val whole = info().fyi!!
        println("$mode read in ${System.currentTimeMillis() - at} ms: ${whole.body.map { "${it.kind} ${it.text.take(40)}" }}")
        assertTrue(whole.body.any { it.kind == FyiLine.Kind.TITLE })
        assertTrue(whole.body.any { it.kind == FyiLine.Kind.SKETCH })
        assertTrue(whole.body.none { it.kind == FyiLine.Kind.MISSING })
        // The keys left the panel: the input box takes typing again.
        pump(800)
        t.typeText("x")
        waitFor("typing in the box ($mode)") { info().claudeInput == "x" }
        t.typeBytes(byteArrayOf(0x7f))
        waitFor("an empty box ($mode)") { info().claudeInput == "" }

        val dismiss = whole.actions.first { it.key == 0 }
        t.pressFyi(whole, dismiss)
        waitFor("the thanks ($mode)", 10_000) { info().fyi?.headline == "Dismissed." }
        val thanks = info().fyi!!
        println("$mode after dismissing: ${thanks.headline} ${thanks.actions}")
        t.pressFyi(thanks, thanks.actions.first { it.key == 0 })
        waitFor("the note to go ($mode)", 10_000) { info().fyi == null }
        assertEquals("", info().claudeInput)
    }

    /**
     * Claude runs a shell in the background, which a plugin pins over the note, and goes idle, so
     * its hint comes under the note; then the note is opened, read whole, and Understood pressed.
     */
    private fun understoodUnderAShell(t: RemoteTerminal, fullscreen: Boolean) {
        val mode = if (fullscreen) "fullscreen" else "classic"
        waitFor("the note ($mode)", 60_000) { info().fyi?.actions?.isNotEmpty() == true && info().claudeInput == "" }
        assertEquals(fullscreen, info().fullscreen)
        // Typed at once, the line reads as a paste: Enter goes on its own, or Claude takes it for a newline.
        t.typeText("Use the Bash tool with run_in_background set to true to run: sleep 300. Then reply with just ok.")
        pump(500)
        t.sendKey(RemoteKey.ENTER)
        waitFor("the shell's row over the note ($mode)", 90_000) { screen().any { it.endsWith(" [-]") && it.trim() != "[-]" } }
        waitFor("the hint ($mode)", 60_000) { screen().any { "new task?" in it } && !info().interruptible }
        waitFor("the note under them ($mode)") { info().fyi?.actions?.isNotEmpty() == true && info().claudeInput == "" }
        val offer = info().fyi!!
        println("$mode offer under a shell: ${offer.tag} · ${offer.headline} ${offer.actions}")
        assertEquals(listOf("Learn more", "Knew this already", "Dismiss"), offer.actions.map { it.label })

        t.pressFyi(offer, offer.actions.first { it.label == "Learn more" })
        waitFor("the explanation ($mode)", 30_000) { info().fyi?.body?.isNotEmpty() == true }
        t.readFyi()
        waitFor("the whole explanation ($mode)", 30_000) { info().fyi?.complete == true }
        val whole = info().fyi!!
        println("$mode explanation under a shell: ${whole.body.map { "${it.kind} ${it.text.take(40)}" }} ${whole.actions}")
        assertTrue(whole.body.any { it.kind == FyiLine.Kind.TITLE })
        assertTrue(whole.body.none { it.kind == FyiLine.Kind.MISSING })
        assertTrue(whole.body.none { "new task?" in it.text })

        t.pressFyi(whole, whole.actions.first { it.label == "Understood" })
        waitFor("the note to go ($mode)", 10_000) { info().fyi == null }
        // The digit went to the button, not into the box.
        pump(1_000)
        assertEquals("", info().claudeInput)
        assertTrue(screen().any { it.endsWith(" [-]") && it.trim() != "[-]" })
    }

    /** The survey over an open note is dismissed from the phone, and then the note's Understood pressed. */
    private fun surveyOverTheNote(t: RemoteTerminal, fullscreen: Boolean) {
        val mode = if (fullscreen) "fullscreen" else "classic"
        waitFor("the survey over the note ($mode)", 60_000) { info().survey != null && info().fyi != null }
        assertEquals(fullscreen, info().fullscreen)
        val survey = info().survey!!
        println("$mode survey: ${survey.question} ${survey.answers}")
        assertEquals(listOf("Bad", "Fine", "Good", "Dismiss"), survey.answers.map { it.label })
        // Claude gives the digits to the survey meanwhile.
        assertTrue(info().fyi!!.actions.isEmpty())

        t.answerSurvey(survey, survey.answers.first { it.label == "Dismiss" })
        waitFor("the survey to go ($mode)", 10_000) { info().survey == null }
        assertEquals("", info().claudeInput)
        // In fullscreen the buttons may be under the panel's window, which reading the rest brings into it.
        waitFor("the note's buttons ($mode)", 30_000) {
            info().fyi?.let { if (!it.complete) t.readFyi() }
            info().fyi?.actions?.any { it.label == "Understood" } == true
        }
        val note = info().fyi!!
        t.pressFyi(note, note.actions.first { it.label == "Understood" })
        waitFor("the note to go ($mode)", 10_000) { info().fyi == null }
        // The digit went to the button, not into the box.
        pump(1_000)
        assertEquals("", info().claudeInput)
    }

    private fun exitClaude(t: RemoteTerminal) {
        t.paste("/exit")
        t.sendKey(RemoteKey.ENTER)
        // With a shell still running in the background, Claude asks first; its first answer stops the shell.
        var asked = false
        waitFor("Claude to exit", 15_000) {
            if (!asked && info().prompt?.options?.firstOrNull()?.label == "Exit and stop tasks") {
                asked = true
                t.sendKey(RemoteKey.ENTER)
            }
            info().claudeMode == null && info().claudeInput == null
        }
        pump(500)
    }

    private fun resize(host: HostConnection, sid: String, bid: String, columns: Int, rows: Int) = runBlocking {
        host.blockMethod(sid, bid, "resize", buildJsonObject {
            put("columns", columns)
            put("rows", rows)
        })
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

    private companion object {
        const val CLAUDE = "--model claude-haiku-5-5"
        /** Fullscreen, whatever renderer the machine's settings choose. */
        const val FULLSCREEN = "--settings '{\"tui\":\"fullscreen\"}'"
        const val CLASSIC = "CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1"
        /** Claude's "new task? /clear to save … tokens" hint, after a few idle seconds rather than 75 minutes and 100k tokens. */
        const val HINT_AT_ONCE = "CLAUDE_CODE_IDLE_TOKEN_THRESHOLD=1 CLAUDE_CODE_IDLE_THRESHOLD_MINUTES=0.1"
        /** Claude's "How is Claude doing this session?" survey as it starts, rather than after a session's turns. */
        const val SURVEY_AT_ONCE = "CLAUDE_FORCE_DISPLAY_SURVEY=1"
    }
}
