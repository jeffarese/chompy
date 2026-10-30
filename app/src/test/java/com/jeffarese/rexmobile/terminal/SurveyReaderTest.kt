package com.jeffarese.rexmobile.terminal

import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Claude Code 2.1.314's surveys over its input box, captured through a live Rex server at 50 columns
 * with the session survey forced (see src/test/resources/screens/claude-survey*): on its own, over an
 * open note in both renderers, and the note once the survey was dismissed. The ones that can't be
 * forced are laid out as Claude's source draws them.
 */
class SurveyReaderTest {
    private val rating = ClaudeSurvey(
        "How is Claude doing this session?",
        listOf(SurveyAnswer('1', "Bad"), SurveyAnswer('2', "Fine"), SurveyAnswer('3', "Good"), SurveyAnswer('0', "Dismiss")),
    )
    private val note = "Release builds fall back to the debug key when keystore.properties is missing, " +
        "so a phone that has the signed app will refuse the update."

    @Test
    fun aSurveyOnItsOwn() {
        val info = vt("claude-survey")
        assertEquals(rating, info.survey)
        assertNull(info.fyi)
        assertEquals("", info.claudeInput)
    }

    /** While the survey shows, the note under it has no buttons: the digits are the survey's. */
    @Test
    fun aSurveyOverAnOpenNote() {
        val info = vt("claude-survey-note")
        assertEquals(rating, info.survey)
        val n = info.fyi!!
        assertEquals(note, n.headline)
        assertTrue(n.actions.isEmpty())
        assertTrue(n.complete)
    }

    @Test
    fun aSurveyOverAnOpenNoteInFullscreen() {
        // The panel shows a window of the note, the rest under it.
        val info = vt("claude-survey-note-fullscreen")
        assertEquals(rating, info.survey)
        assertEquals(note, info.fyi!!.headline)
        assertTrue(info.fyi!!.actions.isEmpty())
    }

    @Test
    fun theNoteHasItsButtonsOnceTheSurveyIsDismissed() {
        val info = vt("claude-survey-dismissed")
        assertNull(info.survey)
        assertEquals(listOf("Understood", "Chat in main session", "Dismiss"), info.fyi!!.actions.map { it.label })
    }

    /** No other capture has one: not a note's buttons, a menu's numbers, nor Claude's questions. */
    @Test
    fun noSurveyInTheOtherCaptures() {
        val others = File(javaClass.classLoader!!.getResource("screens")!!.toURI()).listFiles()!!
            .filter { !it.name.startsWith("claude-survey") }
        others.filter { it.name.endsWith(".vt") }.forEach { assertNull(it.name, vt(it.nameWithoutExtension).survey) }
        others.filter { it.name.endsWith(".txt") }.forEach { assertNull(it.name, fixture(it.nameWithoutExtension).survey) }
    }

    /** A memory's survey has a fifth answer, Unsure, which leaves one space before Dismiss. */
    @Test
    fun aMemorysSurvey() {
        val survey = screen(
            "● Claude recalled a memory:  Releases go out through the",
            "  app's own updater.  How was Claude's recollection?",
            "  (optional)",
            "  1: Bad    2: Fine   3: Good   4: Unsure 0: Dismiss",
        ).survey!!
        assertEquals(
            "Claude recalled a memory:  Releases go out through the app's own updater.  How was Claude's recollection?",
            survey.question,
        )
        assertEquals(listOf("Bad", "Fine", "Good", "Unsure", "Dismiss"), survey.answers.map { it.label })
        assertEquals(listOf('1', '2', '3', '4', '0'), survey.answers.map { it.key })
    }

    /** A rating may bring a question about the transcript, with a link to what that shares, answered with letters. */
    @Test
    fun theQuestionAboutTheTranscript() {
        val survey = screen(
            "● Can Anthropic look at your session transcript to help us",
            "  improve Claude Code?",
            "  https://code.claude.com/docs/en/data-usage#session-quality",
            "  -surveys",
            "  y: Yes    n: No     d: Don't ask again",
        ).survey
        assertEquals(
            ClaudeSurvey(
                "Can Anthropic look at your session transcript to help us improve Claude Code?",
                listOf(SurveyAnswer('y', "Yes"), SurveyAnswer('n', "No"), SurveyAnswer('d', "Don't ask again")),
                link = "https://code.claude.com/docs/en/data-usage#session-quality-surveys",
            ),
            survey,
        )
    }

    /** Claude's hint and its effort badge may sit between the survey and the input box. */
    @Test
    fun aHintUnderTheSurvey() {
        val info = screen(
            "● How is Claude doing this session? (optional)",
            "  1: Bad    2: Fine   3: Good   0: Dismiss",
            "",
            "           new task? /clear to save 46.3k tokens",
            "                                          ◐ medium · /effort",
        )
        assertEquals(rating, info.survey)
    }

    /** In a reply, numbers in the text's colour are not answers, even under a cyan ●. */
    @Test
    fun aReplyIsNotASurvey() {
        val t = TestSession(columns = 50, rows = 10)
        val rule = "─".repeat(50)
        t.print(listOf("\u001b[36m●\u001b[0m Which one?", "  1: apples   2: pears", "", rule, "❯ ", rule, "  ⏵⏵ auto mode on").joinToString("\r\n"))
        val info = ScreenReader.read(t.emulator)
        assertEquals("", info.claudeInput)
        assertNull(info.survey)
    }

    /** One of Claude Code's screens at 60 columns: a reply, [over] its input box, the box and its footer. */
    private fun screen(vararg over: String): ScreenInfo {
        val rule = "─".repeat(60)
        val lines = listOf("⏺ Done.", "") + over + listOf(rule, "❯ ", rule, "  ⏵⏵ auto mode on (shift+tab to cycle)")
        return ScreenReader.read(lines, cursorRow = lines.size - 3)
    }

    /** A captured screen: a "# cursor_row=N ..." line, then the screen's rows. */
    private fun fixture(name: String): ScreenInfo {
        val text = javaClass.classLoader!!.getResource("screens/$name.txt")!!.readText()
        val header = text.substringBefore('\n')
        val cursorRow = Regex("""cursor_row=(\d+)""").find(header)!!.groupValues[1].toInt()
        val rows = Regex("""rows=(\d+)""").find(header)!!.groupValues[1].toInt()
        return ScreenReader.read(text.substringAfter('\n').split('\n').take(rows), cursorRow)
    }

    /** A screen captured as escape sequences, replayed into a terminal 50 columns wide. */
    private fun vt(name: String, rows: Int = 40): ScreenInfo {
        val t = TestSession(columns = 50, rows = rows)
        t.print(javaClass.classLoader!!.getResource("screens/$name.vt")!!.readText())
        return ScreenReader.read(t.emulator)
    }
}
