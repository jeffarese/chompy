package com.jeffarese.rexmobile.ui

import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.jeffarese.rexmobile.RexMobileApp
import com.jeffarese.rexmobile.terminal.PromptOption
import com.jeffarese.rexmobile.terminal.ScreenInfo
import com.jeffarese.rexmobile.terminal.ScreenPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The controls under a Claude session while Claude asks something, and after. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class InputPanelTest {
    @get:Rule val rule = createComposeRule()

    private val sent = mutableListOf<String>()
    private val sink = object : TerminalSink {
        override fun key(code: Int, ctrl: Boolean, alt: Boolean, shift: Boolean) {
            sent += "key $code" + (if (shift) " shift" else "")
        }
        override fun bytes(bytes: ByteArray) {
            sent += "bytes " + bytes.joinToString(",")
        }
        override fun paste(text: String) {
            sent += "paste $text"
        }
        override fun choose(prompt: ScreenPrompt, option: PromptOption) {
            sent += "choose ${option.label}"
        }
    }

    private val permission = ScreenPrompt(
        ScreenPrompt.Kind.MENU, "Do you want to create hello.txt?",
        listOf(
            PromptOption(1, "Yes", selected = true),
            PromptOption(2, "Yes, allow all edits during this session", detail = "shift+tab"),
            PromptOption(3, "No, and tell Claude what to do differently"),
        ),
    )

    @Test
    fun aMenuOnScreenHoldsWhatWouldAnswerIt() {
        val model = ApplicationProvider.getApplicationContext<RexMobileApp>().model
        model.settings.claudeStripShown.value = true
        val info = mutableStateOf(ScreenInfo(claudeMode = "auto mode", claudeEffort = "high"))
        rule.setContent {
            RexTheme {
                InputPanel(
                    blockId = "b1", model = model, controls = TerminalControls(sink, StickyModifiers()), info = info,
                    isClaude = true, working = false, directTyping = false, hint = "Message Claude…",
                    onPaste = {}, onToggleTyping = {},
                )
            }
        }
        rule.onNodeWithText("auto").assertIsEnabled()
        // Claude's strip has the mode chip, so the key bar leaves out Shift+Tab.
        rule.onNodeWithContentDescription("Shift Tab").assertDoesNotExist()

        // The permission prompt hides Claude's footer: the strip keeps the last mode and effort, dimmed and inert.
        info.value = ScreenInfo(prompt = permission, claudeEffort = "medium")
        rule.onNodeWithText("Pick an option above").assertIsDisplayed()
        rule.onNodeWithText("auto").assertIsNotEnabled().performClick()
        rule.onNodeWithText("high").assertIsNotEnabled().performClick()
        rule.onNodeWithText("/compact").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Message").performTextInput("use a temp dir")
        rule.onNodeWithContentDescription("Send").assertIsNotEnabled().performClick()
        // Nothing reached the terminal, and no effort was recorded as set.
        assertEquals(emptyList<String>(), sent)
        assertEquals("high", model.efforts["b1"])

        // Once the prompt is answered, everything works again and the message is still there.
        info.value = ScreenInfo(claudeMode = "auto mode")
        rule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        assertEquals(listOf("paste use a temp dir", "key ${KeyEvent.KEYCODE_ENTER}"), sent)
        rule.onNodeWithText("high").assertIsEnabled().performClick()
        rule.onNodeWithText("low").performClick()
        assertEquals("low", model.efforts["b1"])
        rule.onNodeWithText("auto").performClick()
        assertEquals("key ${KeyEvent.KEYCODE_TAB} shift", sent.last())
    }

    @Test
    fun slashCommandsThatSendWaitForTheMenu() {
        val model = ApplicationProvider.getApplicationContext<RexMobileApp>().model
        model.settings.claudeStripShown.value = true
        val info = mutableStateOf(ScreenInfo(prompt = permission))
        rule.setContent {
            RexTheme {
                InputPanel(
                    blockId = "b2", model = model, controls = TerminalControls(sink, StickyModifiers()), info = info,
                    isClaude = true, working = false, directTyping = false, hint = "Message Claude…",
                    onPaste = {}, onToggleTyping = {},
                )
            }
        }
        rule.onNodeWithContentDescription("Message").performTextInput("/effort h")
        rule.onNodeWithText("high").assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), sent)
        assertEquals(null, model.efforts["b2"])
    }

    @Test
    fun allCommandsOpenOverTheTerminal() {
        val model = ApplicationProvider.getApplicationContext<RexMobileApp>().model
        model.settings.claudeStripShown.value = true
        val info = mutableStateOf(ScreenInfo(claudeMode = "auto mode"))
        var terminalTapped = false
        val overlay = CommandsOverlay()
        rule.setContent {
            RexTheme {
                // As the terminal screen lays them out: the panel shows over the terminal.
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().clickable { terminalTapped = true }) {
                        overlay.content?.let { panel -> Box(Modifier.align(Alignment.BottomCenter).keepTapsOffTerminal()) { panel() } }
                    }
                    InputPanel(
                        blockId = "b3", model = model, controls = TerminalControls(sink, StickyModifiers()), info = info,
                        isClaude = true, working = false, directTyping = false, hint = "Message Claude…",
                        onPaste = {}, onToggleTyping = {}, overlay = overlay,
                    )
                }
            }
        }
        rule.onNodeWithText("Commands").assertDoesNotExist()
        rule.onNodeWithContentDescription("Show all commands").performClick()
        rule.onNodeWithText("Commands").assertIsDisplayed()
        // A command the strip has no room for, picked from the panel, which then closes.
        rule.onNodeWithText("/doctor").assertIsDisplayed().performClick()
        assertEquals(listOf("paste /doctor", "key ${KeyEvent.KEYCODE_ENTER}"), sent)
        assertFalse(terminalTapped)
        rule.onNodeWithText("Commands").assertDoesNotExist()

        // One that takes arguments goes in the box, and the panel then shows what matches it.
        rule.onNodeWithContentDescription("Show all commands").performClick()
        rule.onNodeWithText("/add-dir").performClick()
        rule.onNodeWithText("/add-dir ").assertIsDisplayed()
        assertEquals(2, sent.size)
        rule.onNodeWithContentDescription("Show all commands").performClick()
        rule.onNodeWithText("Commands").assertIsDisplayed()
        rule.onNodeWithContentDescription("Hide all commands").performClick()
        rule.onNodeWithText("Commands").assertDoesNotExist()
        assertFalse(terminalTapped)
    }

    @Test
    fun inputWaitsUntilTheScreenIsKnown() {
        val model = ApplicationProvider.getApplicationContext<RexMobileApp>().model
        val info = mutableStateOf(ScreenInfo())
        var waiting by mutableStateOf<String?>("Waiting for the connection…")
        rule.setContent {
            RexTheme {
                InputPanel(
                    blockId = "b5", model = model, controls = TerminalControls(sink, StickyModifiers()), info = info,
                    isClaude = false, working = false, directTyping = false, hint = "Run a command…",
                    onPaste = {}, onToggleTyping = {}, waiting = waiting,
                )
            }
        }
        rule.onNodeWithText("Waiting for the connection…").assertIsDisplayed()
        rule.onNodeWithContentDescription("Message").performTextInput("ls")
        // Send and the keys do nothing, and the message stays in the box.
        rule.onNodeWithContentDescription("Send").assertIsNotEnabled().performClick()
        rule.onNodeWithContentDescription("Escape").assertIsNotEnabled().performClick()
        rule.onNodeWithContentDescription("Control C").assertIsNotEnabled()
        // The keyboard's button isn't a key for the terminal: it still works.
        rule.onNodeWithContentDescription("Type straight into the terminal").assertIsEnabled()
        assertEquals(emptyList<String>(), sent)

        waiting = null
        rule.onNodeWithContentDescription("Escape").assertIsEnabled()
        rule.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        assertEquals(listOf("paste ls", "key ${KeyEvent.KEYCODE_ENTER}"), sent)
    }

    @Test
    fun quittingClaudeAsksFirst() {
        val model = ApplicationProvider.getApplicationContext<RexMobileApp>().model
        model.settings.claudeStripShown.value = true
        val info = mutableStateOf(ScreenInfo(claudeMode = "auto mode"))
        val overlay = CommandsOverlay()
        rule.setContent {
            RexTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        overlay.content?.let { panel -> Box(Modifier.align(Alignment.BottomCenter)) { panel() } }
                    }
                    InputPanel(
                        blockId = "b6", model = model, controls = TerminalControls(sink, StickyModifiers()), info = info,
                        isClaude = true, working = false, directTyping = false, hint = "Message Claude…",
                        onPaste = {}, onToggleTyping = {}, overlay = overlay,
                    )
                }
            }
        }
        // From every command: it asks, and Cancel sends nothing.
        rule.onNodeWithContentDescription("Show all commands").performClick()
        rule.onNodeWithText("/exit").performScrollTo().performClick()
        rule.onNodeWithText("Quit Claude?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Quit Claude?").assertDoesNotExist()
        assertEquals(emptyList<String>(), sent)

        // From what the box suggests: Quit sends it, and the box empties.
        rule.onNodeWithContentDescription("Message").performTextInput("/exi")
        rule.onNodeWithText("/exit").performClick()
        rule.onNodeWithText("Quit").performClick()
        assertEquals(listOf("paste /exit", "key ${KeyEvent.KEYCODE_ENTER}"), sent)
        rule.onNodeWithText("/exi").assertDoesNotExist()
    }

    @Test
    fun typingACommandWithTheStripHiddenLeavesTheTerminalItsSize() {
        val model = ApplicationProvider.getApplicationContext<RexMobileApp>().model
        val info = mutableStateOf(ScreenInfo(claudeMode = "auto mode"))
        val overlay = CommandsOverlay()
        rule.setContent {
            RexTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().testTag("terminal")) {
                        overlay.content?.let { panel -> Box(Modifier.align(Alignment.BottomCenter)) { panel() } }
                    }
                    InputPanel(
                        blockId = "b7", model = model, controls = TerminalControls(sink, StickyModifiers()), info = info,
                        isClaude = true, working = false, directTyping = false, hint = "Message Claude…",
                        onPaste = {}, onToggleTyping = {}, overlay = overlay,
                    )
                }
            }
        }
        val before = rule.onNodeWithTag("terminal").getUnclippedBoundsInRoot().height
        rule.onNodeWithContentDescription("Message").performTextInput("/comp")
        rule.onNodeWithText("/compact").assertIsDisplayed()
        assertEquals(before, rule.onNodeWithTag("terminal").getUnclippedBoundsInRoot().height)
        rule.onNodeWithText("/compact").performClick()
        assertEquals(listOf("paste /compact", "key ${KeyEvent.KEYCODE_ENTER}"), sent)
    }

    @Test
    fun claudesStripWaitsForItsButton() {
        val model = ApplicationProvider.getApplicationContext<RexMobileApp>().model
        val info = mutableStateOf(ScreenInfo(claudeMode = "auto mode"))
        rule.setContent {
            RexTheme {
                InputPanel(
                    blockId = "b4", model = model, controls = TerminalControls(sink, StickyModifiers()), info = info,
                    isClaude = true, working = false, directTyping = false, hint = "Message Claude…",
                    onPaste = {}, onToggleTyping = {},
                )
            }
        }
        // Hidden at first, so the key bar has Shift+Tab for the mode instead.
        rule.onNodeWithText("auto").assertDoesNotExist()
        rule.onNodeWithContentDescription("Shift Tab").assertExists()
        // A command being typed still brings up its suggestions.
        rule.onNodeWithContentDescription("Message").performTextInput("/comp")
        rule.onNodeWithText("/compact").assertIsDisplayed()

        rule.onNodeWithContentDescription("Show Claude's controls").performClick()
        assertTrue(model.settings.claudeStripShown.value)
        rule.onNodeWithContentDescription("Shift Tab").assertDoesNotExist()
        rule.onNodeWithContentDescription("Hide Claude's controls").performClick()
        assertFalse(model.settings.claudeStripShown.value)
    }
}
