package com.jeffarese.rexmobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jeffarese.rexmobile.terminal.ClaudeSurvey
import com.jeffarese.rexmobile.terminal.PromptOption
import com.jeffarese.rexmobile.terminal.ScreenInfo
import com.jeffarese.rexmobile.terminal.ScreenPrompt
import com.jeffarese.rexmobile.terminal.ScreenReader
import com.jeffarese.rexmobile.terminal.SurveyAnswer
import com.jeffarese.rexmobile.terminal.view.TestSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Claude's survey as a card over the terminal: which taps answer it, and what a note under it says meanwhile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class SurveyOverlayTest {
    @get:Rule val rule = createComposeRule()

    private val answered = mutableListOf<String>()
    private val pressed = mutableListOf<String>()

    private val question = "How is Claude doing this session?"
    private val rating = ClaudeSurvey(
        question,
        listOf(SurveyAnswer('1', "Bad"), SurveyAnswer('2', "Fine"), SurveyAnswer('3', "Good"), SurveyAnswer('0', "Dismiss")),
    )

    private fun show(info: MutableState<ScreenInfo>) {
        rule.setContent {
            RexTheme {
                Box(Modifier.fillMaxSize()) {
                    BottomOverlay(
                        info = info, live = true, blocked = null, scrolledBack = { false }, onJump = {}, onChoose = { _, _ -> },
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onFyi = { _, a -> pressed += a.label },
                        onSurvey = { _, a -> answered += a.label },
                    )
                }
            }
        }
    }

    @Test
    fun aScreenReaderSaysTheSurveyAsItComesUp() {
        show(mutableStateOf(ScreenInfo(claudeInput = "", survey = rating)))
        rule.onNode(
            SemanticsMatcher("its pane title has the question") { n ->
                n.config.getOrNull(SemanticsProperties.PaneTitle) == "Survey from Claude: $question"
            },
        ).assertExists()
    }

    @Test
    fun anAnswerIsGivenOnce() {
        show(mutableStateOf(ScreenInfo(claudeInput = "", survey = rating)))
        rule.mainClock.autoAdvance = false
        // Claude ignores keys typed as the survey shows, so the card does too.
        rule.onNodeWithText("Good").performClick()
        assertEquals(emptyList<String>(), answered)
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText(question).assertIsDisplayed()
        rule.onNodeWithText("Good").performClick()
        rule.onNodeWithText("Bad").performClick()
        rule.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(listOf("Good"), answered)
    }

    @Test
    fun dismissIsTheCross() {
        show(mutableStateOf(ScreenInfo(claudeInput = "", survey = rating)))
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("Dismiss").assertDoesNotExist()
        rule.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(listOf("Dismiss"), answered)
    }

    @Test
    fun textInClaudesBoxHoldsTheAnswers() {
        // A key would only add to what is typed there.
        show(mutableStateOf(ScreenInfo(claudeInput = "half a question", survey = rating)))
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("Send or clear it to answer", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Good").performClick()
        assertEquals(emptyList<String>(), answered)
    }

    @Test
    fun aPromptComesFirst() {
        val prompt = ScreenPrompt(
            ScreenPrompt.Kind.MENU, "Do you want to proceed?", listOf(PromptOption(1, "Yes", selected = true), PromptOption(2, "No")),
        )
        show(mutableStateOf(ScreenInfo(prompt = prompt, claudeInput = "", survey = rating)))
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("Do you want to proceed?").assertIsDisplayed()
        rule.onNodeWithText(question).assertDoesNotExist()
    }

    /** A note under the survey has no buttons until the survey goes, and says so rather than seem to wait for them. */
    @Test
    fun aNoteUnderTheSurveyWaitsForIt() {
        val info = mutableStateOf(vt("claude-survey-note"))
        show(info)
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText(question).assertIsDisplayed()
        rule.onNodeWithText("once its survey is answered or dismissed", substring = true).assertIsDisplayed()

        // Dismissed in the terminal: the survey goes, and the note has its buttons back, which wait a moment.
        info.value = vt("claude-survey-dismissed")
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText(question).assertDoesNotExist()
        rule.onNodeWithText("once its survey", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Understood").performClick()
        assertEquals(listOf("Understood"), pressed)
        assertEquals(emptyList<String>(), answered)
    }

    /** A screen captured as escape sequences, replayed into a terminal 50 columns wide. */
    private fun vt(name: String): ScreenInfo {
        val t = TestSession(columns = 50, rows = 40)
        t.print(javaClass.classLoader!!.getResource("screens/$name.vt")!!.readText())
        return ScreenReader.read(t.emulator)
    }
}
