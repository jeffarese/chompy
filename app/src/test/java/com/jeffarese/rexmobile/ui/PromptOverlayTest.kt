package com.jeffarese.rexmobile.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.jeffarese.rexmobile.terminal.PromptOption
import com.jeffarese.rexmobile.terminal.QuestionTab
import com.jeffarese.rexmobile.terminal.ReviewedAnswer
import com.jeffarese.rexmobile.terminal.ScreenInfo
import com.jeffarese.rexmobile.terminal.ScreenPrompt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The prompt card over the terminal: when it shows, and which taps answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class PromptOverlayTest {
    @get:Rule val rule = createComposeRule()

    private val chosen = mutableListOf<String>()
    private var underTaps = 0
    private lateinit var view: View

    private fun permission(command: String) = ScreenPrompt(
        ScreenPrompt.Kind.MENU, "Do you want to proceed?",
        listOf(
            PromptOption(1, "Yes", selected = true),
            PromptOption(2, "Yes, and don't ask again for this command"),
            PromptOption(3, "No"),
        ),
        context = listOf("Bash command", command),
    )

    private fun fruits(vararg ticked: Boolean) = ScreenPrompt(
        ScreenPrompt.Kind.MENU, "Which fruits do you like?",
        listOf("Apple", "Banana", "Cherry").mapIndexed { i, f -> PromptOption(i + 1, f, selected = i == 0, checked = ticked[i]) } +
            PromptOption(null, "Submit"),
    )

    /** Claude's questions, Colour and then Fruits, on [tab]: Claude's colours tell which tab is on screen. */
    private fun claude(tab: Int, multi: Boolean, typed: String? = null): ScreenPrompt {
        val answers = if (multi) listOf("Apple", "Banana", "Cherry") else listOf("Red", "Green", "Blue")
        val ownCheck = if (multi) (typed != null) else null
        val options = answers.mapIndexed { i, a -> PromptOption(i + 1, a, selected = i == 0, checked = if (multi) false else null) } +
            PromptOption(4, typed ?: "Type something.", checked = ownCheck, role = PromptOption.Role.OWN) +
            listOfNotNull(if (multi) PromptOption(null, "Submit", role = PromptOption.Role.SUBMIT) else null) +
            PromptOption(5, "Chat about this", role = PromptOption.Role.CHAT)
        return ScreenPrompt(
            ScreenPrompt.Kind.MENU, if (multi) "Which fruits do you like?" else "Which colour do you prefer?", options,
            tabs = listOf(
                QuestionTab("Colour", if (multi) QuestionTab.State.ANSWERED else QuestionTab.State.OPEN, current = tab == 0),
                QuestionTab("Fruits", QuestionTab.State.OPEN, current = tab == 1),
                QuestionTab("Submit", QuestionTab.State.SUBMIT, current = tab == 2),
            ),
        )
    }

    /** Lets a new card come up and take taps. */
    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(600)
    }

    /** The overlay over a stand-in for the terminal, which counts the taps that reach it. */
    private fun show(info: MutableState<ScreenInfo>, live: () -> Boolean = { true }, blocked: String? = null) {
        rule.setContent {
            view = LocalView.current
            RexTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { underTaps++ } })
                    BottomOverlay(
                        info = info, live = live(), blocked = blocked, scrolledBack = { false }, onJump = {},
                        onChoose = { _, o -> chosen += o.label },
                        onTab = { _, i -> chosen += "tab $i" },
                        onOwn = { _, text -> chosen += "own $text" },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        // A card that just came up ignores taps for a moment.
        rule.mainClock.advanceTimeBy(600)
    }

    @Test
    fun aCardSaysItsQuestionAsItComesUpAndAnAnswerConfirms() {
        val info = mutableStateOf(ScreenInfo(prompt = permission("date")))
        show(info)
        // A screen reader says a pane's title as it appears.
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Do you want to proceed?")).assertExists()
        rule.onNodeWithText("Yes").performClick()
        assertEquals(listOf("Yes"), chosen)
        assertEquals(HapticFeedbackConstants.CONFIRM, shadowOf(view).lastHapticFeedbackPerformed())

        info.value = ScreenInfo(prompt = claude(tab = 0, multi = false))
        rule.mainClock.advanceTimeBy(600)
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Which colour do you prefer?")).assertExists()
    }

    @Test
    fun doubleTapAnswersOnce() {
        val info = mutableStateOf(ScreenInfo(prompt = permission("date > out.txt")))
        show(info)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Yes").performClick()
        rule.onNodeWithText("Yes").performClick()
        assertEquals(listOf("Yes"), chosen)

        // The next prompt comes up at once: the second tap of a double tap can't answer it unseen.
        info.value = ScreenInfo(prompt = permission("rm -rf build"))
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Bash command\nrm -rf build").assertIsDisplayed()
        rule.onNodeWithText("Yes").performClick()
        assertEquals(listOf("Yes"), chosen)
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText("No").performClick()
        assertEquals(listOf("Yes", "No"), chosen)
        assertEquals(0, underTaps)
    }

    @Test
    fun answeredCardComesBackIfTheScreenStillAsks() {
        val info = mutableStateOf(ScreenInfo(prompt = permission("date")))
        show(info)
        rule.onNodeWithText("Yes").performClick()
        rule.onNodeWithText("Yes").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(1600)
        rule.onNodeWithText("Yes").performClick()
        assertEquals(listOf("Yes", "Yes"), chosen)
    }

    @Test
    fun multiSelectTicksKeepTheCardUp() {
        val info = mutableStateOf(ScreenInfo(prompt = fruits(false, false, false)))
        show(info)
        rule.onNodeWithText("Apple").performClick()
        // A tick doesn't answer: the card stays, and shows the tick once the screen does.
        rule.onNodeWithText("Which fruits do you like?").assertIsDisplayed()
        info.value = ScreenInfo(prompt = fruits(true, false, false))
        rule.onNodeWithText("Apple").assertIsOn()
        rule.onNodeWithText("Cherry").assertIsDisplayed().performClick()
        rule.onNodeWithText("Submit").performClick()
        assertEquals(listOf("Apple", "Cherry", "Submit"), chosen)
        // Submit answers, so the card goes.
        rule.onNodeWithText("Which fruits do you like?").assertDoesNotExist()
    }

    @Test
    fun claudesQuestionIsAnsweredFromItsOwnCard() {
        val info = mutableStateOf(ScreenInfo(prompt = claude(tab = 0, multi = false)))
        show(info)
        // Its tabs; Chat about this set apart from the answers, and the field in place of "Type something.".
        rule.onNodeWithText("Submit").assertIsDisplayed()
        rule.onNodeWithText("Chat about this").assertIsDisplayed()
        rule.onNodeWithText("Type something.").assertDoesNotExist()
        rule.onNodeWithText("Fruits").performClick()
        rule.onNodeWithText("Green").performClick()
        assertEquals(listOf("tab 1", "Green"), chosen)
        assertEquals(0, underTaps)
    }

    @Test
    fun anAnswerOfOnesOwn() {
        val info = mutableStateOf(ScreenInfo(prompt = claude(tab = 0, multi = false)))
        show(info)
        rule.onNodeWithContentDescription("Your own answer").performTextInput("deep purple")
        rule.onNodeWithContentDescription("Send your answer").performClick()
        assertEquals(listOf("own deep purple"), chosen)
        // A single choice is answered with it.
        rule.onNodeWithText("Which colour do you prefer?").assertDoesNotExist()

        // In a multi-select question it is one more tick, and Next goes on.
        chosen.clear()
        info.value = ScreenInfo(prompt = claude(tab = 1, multi = true))
        settle()
        rule.onNodeWithText("Apple").performClick()
        rule.onNodeWithContentDescription("Your own answer").performTextInput("figs")
        rule.onNodeWithContentDescription("Add your answer").performClick()
        info.value = ScreenInfo(prompt = claude(tab = 1, multi = true, typed = "figs"))
        // The option's new text makes it another menu, which takes taps once it has settled.
        settle()
        rule.onNodeWithText("Which fruits do you like?").assertIsDisplayed()
        // Fruits is the last question, so Submit leads to the review: Done rather than Next.
        rule.onNodeWithText("Next").assertDoesNotExist()
        rule.onNodeWithText("Done").performClick()
        assertEquals(listOf("Apple", "own figs", "Submit"), chosen)
    }

    @Test
    fun theReviewGoesBackToAQuestionOrSends() {
        val review = ScreenPrompt(
            ScreenPrompt.Kind.MENU, "Ready to submit your answers?",
            listOf(PromptOption(1, "Submit answers", selected = true), PromptOption(2, "Cancel")),
            tabs = listOf(
                QuestionTab("Colour", QuestionTab.State.ANSWERED, false),
                QuestionTab("Fruits", QuestionTab.State.ANSWERED, false),
                QuestionTab("Submit", QuestionTab.State.SUBMIT, true),
            ),
            review = listOf(ReviewedAnswer("Which colour do you prefer?", "deep purple"), ReviewedAnswer("Which fruits do you like?", "Apple, figs")),
        )
        val info = mutableStateOf(ScreenInfo(prompt = review))
        show(info)
        rule.onNodeWithText("Apple, figs").performClick()
        rule.onNodeWithText("Submit answers").performClick()
        assertEquals(listOf("tab 1", "Submit answers"), chosen)
    }

    @Test
    fun cardHidesWhileTheStreamIsNotLive() {
        var live by mutableStateOf(false)
        val info = mutableStateOf(ScreenInfo(prompt = permission("date")))
        show(info, live = { live }, blocked = "Waiting for you")
        rule.onNodeWithText("Do you want to proceed?").assertDoesNotExist()
        rule.onNodeWithText("Waiting for you").assertIsDisplayed()
        live = true
        rule.onNodeWithText("Do you want to proceed?").assertIsDisplayed()
    }

    @Test
    fun tapsOnThePillsStayOffTheTerminal() {
        val info = mutableStateOf(ScreenInfo())
        show(info, blocked = "Waiting for you")
        rule.onNodeWithText("Waiting for you").performClick()
        assertEquals(0, underTaps)

        // Just missing the folded card's pill doesn't click the terminal either.
        info.value = ScreenInfo(prompt = permission("date"))
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithContentDescription("Hide the answers").performClick()
        val pill = rule.onNodeWithContentDescription("Show the answers").getUnclippedBoundsInRoot()
        rule.onRoot().performTouchInput {
            click(Offset((pill.left - 5.dp).toPx(), ((pill.top + pill.bottom) / 2).toPx()))
        }
        assertEquals(0, underTaps)
        // The terminal does get taps away from the overlay.
        rule.onRoot().performTouchInput { click(Offset(centerX, 40f)) }
        assertEquals(1, underTaps)
    }
}
