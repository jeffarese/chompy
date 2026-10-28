package com.jeffarese.rexmobile.ui

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import com.jeffarese.rexmobile.terminal.PromptOption
import com.jeffarese.rexmobile.terminal.ScreenPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records what the controls send, in place of a terminal. */
private class RecordingSink : TerminalSink {
    val sent = mutableListOf<String>()
    override fun key(code: Int, ctrl: Boolean, alt: Boolean, shift: Boolean) {
        sent += "key $code" + (if (ctrl) " ctrl" else "") + (if (alt) " alt" else "") + (if (shift) " shift" else "")
    }
    override fun bytes(bytes: ByteArray) {
        sent += "bytes " + bytes.joinToString(",") { (it.toInt() and 0xff).toString() }
    }
    override fun paste(text: String) {
        sent += "paste $text"
    }
    override fun choose(prompt: ScreenPrompt, option: PromptOption) {
        sent += "choose ${option.label}"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class TerminalControlsUiTest {
    @get:Rule val rule = createComposeRule()

    private val sink = RecordingSink()
    private val controls = TerminalControls(sink, StickyModifiers())

    @Test
    fun ctrlCKeySendsEtx() {
        rule.setContent { RexTheme { KeyBar(controls, directTyping = false, onPaste = {}, onToggleTyping = {}) } }
        rule.onNodeWithText("^C").performClick()
        rule.onNodeWithText("esc").performClick()
        assertEquals(listOf("bytes 3", "key ${KeyEvent.KEYCODE_ESCAPE}"), sink.sent)
    }

    @Test
    fun releasingAHeldKeyPressesNothingMore() {
        rule.setContent { RexTheme { KeyBar(controls, directTyping = false, onPaste = {}, onToggleTyping = {}) } }
        val backspace = "key ${KeyEvent.KEYCODE_DEL}"
        val key = rule.onNodeWithContentDescription("Backspace").performScrollTo()
        // A tap is one press.
        key.performClick()
        assertEquals(listOf(backspace), sink.sent)
        sink.sent.clear()

        // A hold repeats until it ends, and letting go adds no press of its own.
        rule.mainClock.autoAdvance = false
        key.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(600)
        val held = sink.sent.size
        assertTrue("the hold should have repeated, sent $held", held >= 2)
        key.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(200)
        assertEquals(List(held) { backspace }, sink.sent)
    }

    @Test
    fun aThinLineTakesTapsAButtonsHeight() {
        var line = 0
        var below = 0
        var above = 0
        rule.setContent {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(40.dp).testTag("above").clickable { above++ })
                Box(Modifier.fillMaxWidth().reachingTap(drawn = 28.dp, onClickLabel = "Show") { line++ })
                Box(Modifier.fillMaxWidth().weight(1f).testTag("below").clickable { below++ })
            }
        }
        val top = rule.onNodeWithTag("above").getUnclippedBoundsInRoot().bottom
        val bottom = rule.onNodeWithTag("below").getUnclippedBoundsInRoot().top
        // It takes only the room it is drawn in.
        assertEquals(28f, (bottom - top).value, 0.5f)
        fun tapAt(y: androidx.compose.ui.unit.Dp) = rule.onRoot().performTouchInput { click(Offset(centerX, y.toPx())) }
        // Its taps reach past it, over what is below and above it.
        tapAt(bottom + 4.dp)
        tapAt(top - 4.dp)
        assertEquals(2, line)
        assertEquals(0, below + above)
        // Not much further.
        tapAt(bottom + 10.dp)
        tapAt(top - 10.dp)
        assertEquals(2, line)
        assertEquals(1, below)
        assertEquals(1, above)
    }

    @Test
    fun stickyCtrlAppliesToTheNextKeyOnly() {
        rule.setContent { RexTheme { KeyBar(controls, directTyping = false, onPaste = {}, onToggleTyping = {}) } }
        rule.onNodeWithText("ctrl").performScrollTo().performClick()
        rule.onNodeWithText("/").performScrollTo().performClick()
        rule.onNodeWithText("/").performClick()
        rule.onNodeWithContentDescription("Shift Tab").performScrollTo().performClick()
        assertEquals(listOf("bytes 31", "bytes 47", "key ${KeyEvent.KEYCODE_TAB} shift"), sink.sent)
    }

    @Test
    fun stickyShiftShiftsTheNextKeyOnly() {
        rule.setContent { RexTheme { KeyBar(controls, directTyping = false, onPaste = {}, onToggleTyping = {}) } }
        rule.onNodeWithContentDescription("Shift").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Left").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Left").performClick()
        rule.onNodeWithContentDescription("Shift").performScrollTo().performClick()
        rule.onNodeWithText("tab").performScrollTo().performClick()
        val left = KeyEvent.KEYCODE_DPAD_LEFT
        assertEquals(listOf("key $left shift", "key $left", "key ${KeyEvent.KEYCODE_TAB} shift"), sink.sent)
        assertFalse(controls.modifiers.shift)
    }

    @Test
    fun ctrlThenTypingInTheMessageBoxSendsTheControlCharacter() {
        rule.setContent {
            RexTheme {
                var value by remember { mutableStateOf(TextFieldValue()) }
                Composer(value, { value = it }, hint = "Message Claude…", working = false, controls = controls, onSend = {})
            }
        }
        controls.modifiers.ctrl = true
        rule.onNodeWithContentDescription("Message").performTextInput("c")
        rule.waitForIdle()
        assertEquals(listOf("bytes 3"), sink.sent)
        assertFalse(controls.modifiers.ctrl)
        // The box didn't keep the character, and typing goes on normally after it.
        rule.onNodeWithContentDescription("Message").performTextInput("x")
        rule.onNodeWithContentDescription("Message").assertTextEquals("x")
        assertEquals(listOf("bytes 3"), sink.sent)
    }

    @Test
    fun altThenTypingSendsEscapePrefix() {
        rule.setContent {
            RexTheme {
                var value by remember { mutableStateOf(TextFieldValue()) }
                Composer(value, { value = it }, hint = "", working = false, controls = controls, onSend = {})
            }
        }
        controls.modifiers.alt = true
        rule.onNodeWithContentDescription("Message").performTextInput("b")
        rule.waitForIdle()
        assertEquals(listOf("bytes 27,98"), sink.sent)
    }

    @Test
    fun sendButtonStopsWhileWorkingAndEmpty() {
        val sentMessages = mutableListOf<String>()
        var working by mutableStateOf(true)
        rule.setContent {
            RexTheme {
                var value by remember { mutableStateOf(TextFieldValue()) }
                Composer(value, { value = it }, hint = "", working = working, controls = controls, onSend = { sentMessages += it; value = TextFieldValue() })
            }
        }
        rule.onNodeWithContentDescription("Stop").performClick()
        assertEquals(listOf("key ${KeyEvent.KEYCODE_ESCAPE}"), sink.sent)
        // With text typed, the button sends it instead.
        rule.onNodeWithContentDescription("Message").performTextInput("hi")
        rule.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("hi"), sentMessages)
        working = false
        rule.onNodeWithContentDescription("Press Enter").assertIsDisplayed()
    }

    @Test
    fun promptCardOffersEachOption() {
        val prompt = ScreenPrompt(
            ScreenPrompt.Kind.MENU,
            "Do you want to make this edit to TerminalScreen.kt?",
            listOf(
                PromptOption(1, "Yes", selected = true),
                PromptOption(2, "Yes, allow all edits during this session", detail = "shift+tab"),
                PromptOption(3, "No, and tell Claude what to do differently"),
            ),
        )
        val chosen = mutableListOf<PromptOption>()
        var collapsed = false
        rule.setContent { RexTheme { PromptCard(prompt, onChoose = { chosen += it }, onCollapse = { collapsed = true }) } }
        rule.onNodeWithText("Do you want to make this edit to TerminalScreen.kt?").assertIsDisplayed()
        rule.onNodeWithText("shift+tab").assertIsDisplayed()
        rule.onNodeWithText("No, and tell Claude what to do differently").performClick()
        assertEquals(listOf(prompt.options[2]), chosen)
        rule.onNodeWithContentDescription("Hide the answers").performClick()
        assertEquals(true, collapsed)
    }

    @Test
    fun yesNoPromptGetsYesAndNoButtons() {
        val prompt = ScreenPrompt(ScreenPrompt.Kind.YES_NO, "Overwrite file? [y/N]", emptyList())
        val chosen = mutableListOf<String>()
        rule.setContent { RexTheme { PromptCard(prompt, onChoose = { chosen += it.label }, onCollapse = {}) } }
        rule.onNodeWithText("Yes").assertIsDisplayed()
        rule.onNodeWithText("No").performClick()
        assertEquals(listOf("No"), chosen)
    }

    @Test
    fun claudeBarSetsEffortAndCyclesMode() {
        val events = mutableListOf<String>()
        rule.setContent {
            RexTheme {
                ClaudeBar(
                    mode = "plan mode", effort = "medium",
                    onCycleMode = { events += "mode" }, onEffort = { events += "effort $it" }, onCommand = { events += it },
                )
            }
        }
        rule.onNodeWithText("plan").performClick()
        rule.onNodeWithText("medium").performClick()
        rule.onNodeWithText("xhigh").performClick()
        rule.onNodeWithText("/compact").performScrollTo().performClick()
        assertEquals(listOf("mode", "effort xhigh", "/compact"), events)
    }

    @Test
    fun controlsLeaveTheTerminalItsRoom() {
        rule.setContent {
            RexTheme {
                Column(Modifier.testTag("controls")) {
                    ClaudeBar(mode = "accept edits on", effort = "high", onCycleMode = {}, onEffort = {}, onCommand = {})
                    KeyBar(controls, directTyping = false, onPaste = {}, onToggleTyping = {})
                    Composer(TextFieldValue(), {}, hint = "Message Claude…", working = false, controls = controls, onSend = {})
                }
            }
        }
        val height = rule.onNodeWithTag("controls").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("controls are $height tall", height <= 142.dp)
    }

    @Test
    fun longPromptScrollsInsideItsCard() {
        val prompt = ScreenPrompt(ScreenPrompt.Kind.MENU, "Pick a session to resume", (1..12).map { PromptOption(it, "Session $it", detail = "2 hours ago") })
        val chosen = mutableListOf<Int?>()
        rule.setContent {
            RexTheme {
                Box(Modifier.height(400.dp), contentAlignment = Alignment.BottomCenter) {
                    PromptCard(prompt, onChoose = { chosen += it.number }, onCollapse = {}, modifier = Modifier.testTag("card"))
                }
            }
        }
        val height = rule.onNodeWithTag("card").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("card is $height tall", height <= 400.dp * 0.65f)
        rule.onNodeWithText("Session 12").performScrollTo().performClick()
        assertEquals(listOf(12), chosen)
    }

    @Test
    fun altCapitalFromAnEmptyBoxIsLowercase() {
        rule.setContent {
            RexTheme {
                var value by remember { mutableStateOf(TextFieldValue()) }
                Composer(value, { value = it }, hint = "", working = false, controls = controls, onSend = {})
            }
        }
        // The keyboard capitalises the start of the empty box on its own: alt+p, not alt+shift+p.
        controls.modifiers.alt = true
        rule.onNodeWithContentDescription("Message").performTextInput("P")
        rule.waitForIdle()
        assertEquals(listOf("bytes 27,112"), sink.sent)
        // Mid-sentence, a capital is the user's own and stays.
        rule.onNodeWithContentDescription("Message").performTextInput("go to")
        controls.modifiers.alt = true
        rule.onNodeWithContentDescription("Message").performTextInput("Q")
        rule.waitForIdle()
        assertEquals(listOf("bytes 27,112", "bytes 27,81"), sink.sent)
        // Ctrl doesn't depend on case.
        rule.onNodeWithContentDescription("Message").performTextInput(". ")
        controls.modifiers.ctrl = true
        rule.onNodeWithContentDescription("Message").performTextInput("C")
        rule.waitForIdle()
        assertEquals(listOf("bytes 27,112", "bytes 27,81", "bytes 3"), sink.sent)
    }

    @Test
    fun sendWaitsWhileAMenuIsOpen() {
        val sentMessages = mutableListOf<String>()
        var value by mutableStateOf(TextFieldValue("use a temp dir"))
        rule.setContent {
            RexTheme {
                Composer(value, { value = it }, hint = "Pick an option above", working = false, controls = controls, onSend = { sentMessages += it }, menuOpen = true)
            }
        }
        // Its Enter would pick the menu's highlighted option, so Send with text does nothing.
        rule.onNodeWithContentDescription("Send").assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), sentMessages)
        // The empty box's Enter is a plain key press, and stays.
        value = TextFieldValue()
        rule.onNodeWithText("Pick an option above").assertIsDisplayed()
        rule.onNodeWithContentDescription("Press Enter").assertIsEnabled().performClick()
        assertEquals(listOf(""), sentMessages)
    }

    @Test
    fun claudeBarIsDimmedAndInertDuringAMenu() {
        val events = mutableListOf<String>()
        rule.setContent {
            RexTheme {
                ClaudeBar(
                    mode = "auto mode", effort = "high",
                    onCycleMode = { events += "mode" }, onEffort = { events += "effort $it" }, onCommand = { events += it },
                    enabled = false,
                )
            }
        }
        rule.onNodeWithText("auto").assertIsNotEnabled().performClick()
        rule.onNodeWithText("high").assertIsNotEnabled().performClick()
        rule.onNodeWithText("low").assertDoesNotExist()
        rule.onNodeWithText("/compact").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), events)
        rule.onNodeWithContentDescription("Permission mode: auto mode. Answer the prompt first").assertExists()
    }

    @Test
    fun suggestionsThatSendAreInertDuringAMenu() {
        val picked = mutableListOf<String>()
        rule.setContent {
            RexTheme {
                SlashSuggestions(listOf(Suggestion("/effort", "/effort ", send = false), Suggestion("/model", "/model", send = true)), sendEnabled = false) {
                    picked += it.label
                }
            }
        }
        rule.onNodeWithText("/model").assertIsNotEnabled().performClick()
        // Filling the box is harmless.
        rule.onNodeWithText("/effort").assertIsEnabled().performClick()
        assertEquals(listOf("/effort"), picked)
    }

    @Test
    fun navigationKeysComeFirst() {
        var shiftTab by mutableStateOf(true)
        rule.setContent { RexTheme { KeyBar(controls, directTyping = false, onPaste = {}, onToggleTyping = {}, shiftTab = shiftTab) } }
        fun left(label: String) = rule.onNodeWithText(label).getUnclippedBoundsInRoot().left
        val order = listOf("esc", "^C", "↑", "↓", "←", "→", "⏎", "tab", "⇧⇥", "⌫", "⇧", "ctrl", "alt", "/")
        assertEquals(order, order.sortedBy { left(it).value })
        // At phone width the arrows and Enter show without scrolling the bar.
        val toggle = rule.onNodeWithContentDescription("Type straight into the terminal").getUnclippedBoundsInRoot().left
        assertTrue(rule.onNodeWithText("⏎").getUnclippedBoundsInRoot().right <= toggle)
        // With Claude's strip there, its mode chip is the Shift+Tab.
        shiftTab = false
        rule.onNodeWithContentDescription("Shift Tab").assertDoesNotExist()
        assertTrue(left("⌫") < left("ctrl"))
    }

    @Test
    fun promptCardShowsWhatTheQuestionIsAbout() {
        val prompt = ScreenPrompt(
            ScreenPrompt.Kind.MENU, "Do you want to proceed?",
            listOf(PromptOption(1, "Yes", selected = true), PromptOption(2, "No")),
            context = listOf("Bash command", "rm -rf build && make"),
        )
        rule.setContent { RexTheme { PromptCard(prompt, onChoose = {}, onCollapse = {}) } }
        rule.onNodeWithText("Bash command\nrm -rf build && make").assertIsDisplayed()
        rule.onNodeWithText("Do you want to proceed?").assertIsDisplayed()
        assertTrue(
            rule.onNodeWithText("Bash command", substring = true).getUnclippedBoundsInRoot().bottom <=
                rule.onNodeWithText("Do you want to proceed?").getUnclippedBoundsInRoot().top,
        )
    }

    @Test
    fun longContextIsCutToFourLines() {
        val prompt = ScreenPrompt(
            ScreenPrompt.Kind.MENU, "Do you want to create notes.txt?",
            listOf(PromptOption(1, "Yes", selected = true), PromptOption(2, "No")),
            context = listOf("Create file", "notes.txt") + (1..6).map { "line $it of the new file" },
        )
        rule.setContent { RexTheme { PromptCard(prompt, onChoose = {}, onCollapse = {}) } }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("Create file", substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(4, layouts.single().lineCount)
        assertTrue(layouts.single().hasVisualOverflow)
    }

    @Test
    fun multiSelectOptionsShowTheirTicks() {
        val prompt = ScreenPrompt(
            ScreenPrompt.Kind.MENU, "Which fruits do you like?",
            listOf(
                PromptOption(1, "Apple", checked = true),
                PromptOption(2, "Banana", checked = false),
                PromptOption(3, "Cherry", selected = true, checked = true),
                PromptOption(null, "Submit"),
            ),
        )
        val chosen = mutableListOf<String>()
        rule.setContent { RexTheme { PromptCard(prompt, onChoose = { chosen += it.label }, onCollapse = {}) } }
        rule.onNodeWithText("Apple").assertIsOn()
        rule.onNodeWithText("Banana").assertIsOff()
        rule.onNodeWithText("Cherry").assertIsOn()
        rule.onNodeWithText("Banana").performClick()
        rule.onNodeWithText("Submit").performClick()
        assertEquals(listOf("Banana", "Submit"), chosen)
    }
}
