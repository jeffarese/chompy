package com.jeffarese.rexmobile.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jeffarese.rexmobile.terminal.FyiNote
import com.jeffarese.rexmobile.terminal.PromptOption
import com.jeffarese.rexmobile.terminal.ScreenInfo
import com.jeffarese.rexmobile.terminal.ScreenPrompt
import com.jeffarese.rexmobile.terminal.ScreenReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Claude's "You should know" note as a card over the terminal: what it shows, and which taps press its buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class FyiOverlayTest {
    @get:Rule val rule = createComposeRule()

    private val pressed = mutableListOf<String>()
    private var reads = 0
    private var underTaps = 0

    private val offer = note("claude-fyi-offer")
    private val open = note("claude-fyi-explained")
    private val headline = "Release builds fall back to the debug key"

    /** @param height the terminal's height, if not the whole screen's, as with the keyboard up. */
    private fun show(info: MutableState<ScreenInfo>, height: Dp? = null) {
        rule.setContent {
            RexTheme {
                Box(if (height == null) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(height)) {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { underTaps++ } })
                    BottomOverlay(
                        info = info, live = true, blocked = null, scrolledBack = { false }, onJump = {}, onChoose = { _, _ -> },
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onFyi = { _, a -> pressed += a.label },
                        onReadFyi = { reads++ },
                    )
                }
            }
        }
    }

    @Test
    fun aNoteOpensAndEachButtonPressesOnce() {
        val info = mutableStateOf(ScreenInfo(claudeInput = "", fyi = offer))
        show(info)
        rule.mainClock.autoAdvance = false
        // Claude ignores the digits of buttons that just showed, so the card does too.
        rule.onNodeWithText("Learn more").performClick()
        assertEquals(emptyList<String>(), pressed)
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText(headline, substring = true).assertIsDisplayed()
        rule.onNodeWithText("Learn more").performClick()
        rule.onNodeWithText("Learn more").performClick()
        rule.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(listOf("Learn more"), pressed)

        // The explanation comes up with new buttons, which wait a moment too.
        info.value = ScreenInfo(claudeInput = "", fyi = open)
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Why the phone refuses it").assertIsDisplayed()
        rule.onNodeWithText("Understood").performClick()
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("Chat in main session").performClick()
        assertEquals(listOf("Learn more", "Chat in main session"), pressed)
        assertEquals(0, reads)
        assertEquals(0, underTaps)
    }

    @Test
    fun dismissIsTheCross() {
        show(mutableStateOf(ScreenInfo(claudeInput = "", fyi = offer)))
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("Dismiss").assertDoesNotExist()
        rule.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(listOf("Dismiss"), pressed)
    }

    @Test
    fun textInClaudesBoxHoldsTheButtons() {
        // A digit would only add to what is typed there.
        show(mutableStateOf(ScreenInfo(claudeInput = "half a question", fyi = offer)))
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("Claude's message box has text", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Learn more").performClick()
        assertEquals(emptyList<String>(), pressed)
    }

    @Test
    fun aPromptComesFirst() {
        val prompt = ScreenPrompt(
            ScreenPrompt.Kind.MENU, "Do you want to proceed?", listOf(PromptOption(1, "Yes", selected = true), PromptOption(2, "No")),
        )
        show(mutableStateOf(ScreenInfo(prompt = prompt, claudeInput = "", fyi = offer)))
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("Do you want to proceed?").assertIsDisplayed()
        rule.onNodeWithText(headline, substring = true).assertDoesNotExist()
    }

    @Test
    fun theRestOfALongNoteIsRead() {
        show(mutableStateOf(ScreenInfo(claudeInput = "", fyi = note("claude-fyi-explained-top"))))
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("Reading the rest from Claude…").assertIsDisplayed()
        assertEquals(1, reads)
    }

    @Test
    fun theButtonsShowInAShortTerminal() {
        // With the keyboard up, the headline and explanation scroll and the buttons stay inside the card.
        show(mutableStateOf(ScreenInfo(claudeInput = "", fyi = open)), height = 220.dp)
        rule.mainClock.advanceTimeBy(800)
        val bottom = rule.onRoot().getUnclippedBoundsInRoot().bottom
        listOf("Understood", "Chat in main session").forEach { label ->
            val button = rule.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue(label, button.bottom <= bottom - 8.dp && button.bottom - button.top >= 38.dp)
        }
        rule.onNodeWithText("Understood").performClick()
        assertEquals(listOf("Understood"), pressed)
    }

    @Test
    fun aBusyClaudeHoldsTheRestOfALongNoteUntilItStops() {
        // Claude's classic panel is only scrolled while Claude is idle, where a stray Esc interrupts nothing.
        val info = mutableStateOf(ScreenInfo(claudeInput = "", interruptible = true, fyi = note("claude-fyi-explained-top")))
        show(info)
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("Claude is working", substring = true).assertIsDisplayed()
        info.value = info.value.copy(interruptible = false)
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Claude is working", substring = true).assertDoesNotExist()
        assertEquals(2, reads)
    }

    @Test
    fun theNoteFoldsAway() {
        show(mutableStateOf(ScreenInfo(claudeInput = "", fyi = offer)))
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithContentDescription("Hide the note").performClick()
        rule.onNodeWithText("Learn more").assertDoesNotExist()
        rule.onNodeWithText("✦ Heads up · $headline", substring = true).performClick()
        rule.onNodeWithText("Learn more").assertIsDisplayed()
    }

    private fun note(name: String): FyiNote {
        val text = javaClass.classLoader!!.getResource("screens/$name.txt")!!.readText()
        val header = text.substringBefore('\n')
        val cursorRow = Regex("""cursor_row=(\d+)""").find(header)!!.groupValues[1].toInt()
        val rows = Regex("""rows=(\d+)""").find(header)!!.groupValues[1].toInt()
        return ScreenReader.read(text.substringAfter('\n').split('\n').take(rows), cursorRow).fyi!!
    }
}
