package com.jeffarese.rexmobile.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.jeffarese.rexmobile.terminal.PromptOption
import com.jeffarese.rexmobile.terminal.ScreenPrompt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalInputTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun ctrlMakesControlCharacters() {
        assertArrayEquals(bytes(3), typedBytes("c", ctrl = true))
        assertArrayEquals(bytes(3), typedBytes("C", ctrl = true))
        assertArrayEquals(bytes(31), typedBytes("/", ctrl = true))
        assertArrayEquals(bytes(0), typedBytes(" ", ctrl = true))
        assertArrayEquals(bytes(27), typedBytes("[", ctrl = true))
        // The key bar's ^C is already a control character.
        assertArrayEquals(bytes(3), typedBytes("\u0003", ctrl = true))
        // Nothing to map: the character itself.
        assertArrayEquals(bytes('.'.code), typedBytes(".", ctrl = true))
    }

    @Test
    fun altPrefixesEscape() {
        assertArrayEquals(bytes(27, 'b'.code), typedBytes("b", alt = true))
        assertArrayEquals(bytes(27, 2), typedBytes("b", ctrl = true, alt = true))
        assertArrayEquals("é".toByteArray(), typedBytes("é"))
    }

    @Test
    fun newlineIsEnter() {
        assertArrayEquals(bytes(13), typedBytes("\n"))
    }

    @Test
    fun insertedCharFindsOneTypedCharacter() {
        val old = TextFieldValue("hel", TextRange(3))
        assertEquals("c", insertedChar(old, TextFieldValue("helc", TextRange(4))))
        assertEquals("x", insertedChar(TextFieldValue("ab", TextRange(1)), TextFieldValue("axb", TextRange(2))))
        assertEquals("😀", insertedChar(old, TextFieldValue("hel😀", TextRange(5))))
        assertNull(insertedChar(old, TextFieldValue("he", TextRange(2))))
        assertNull(insertedChar(old, TextFieldValue("hello", TextRange(5))))
        assertNull(insertedChar(old, TextFieldValue("Hela", TextRange(4))))
    }

    @Test
    fun slashSuggestionsMatchCommands() {
        assertNull(slashSuggestions("hello"))
        assertEquals(listOf("/compact", "/context", "/cost", "/config"), slashSuggestions("/co")!!.map { it.label })
        // Commands containing the text come after those starting with it.
        assertEquals(listOf("/model", "/memory"), slashSuggestions("/mo")!!.map { it.label })
        assertEquals(CLAUDE_COMMANDS.size, slashSuggestions("/")!!.size)
        assertNull(slashSuggestions("/zzz"))
        assertNull(slashSuggestions("/compact keep the tests"))
    }

    @Test
    fun commandsWithArgumentsFillTheBox() {
        val effort = slashSuggestions("/eff")!!.single()
        assertEquals("/effort ", effort.text)
        assertEquals(false, effort.send)
        val model = slashSuggestions("/mod")!!.first()
        assertEquals("/model", model.text)
        assertEquals(true, model.send)
    }

    @Test
    fun effortLevelsAfterEffort() {
        assertEquals(EFFORT_LEVELS + listOf("ultracode on", "ultracode off"), slashSuggestions("/effort ")!!.map { it.label })
        val m = slashSuggestions("/effort m")!!
        assertEquals(listOf("medium", "max"), m.map { it.label })
        assertEquals("/effort max", m[1].text)
        assertEquals(true, m[1].send)
        assertEquals(listOf("ultracode on"), slashSuggestions("/effort ultracode on")!!.map { it.label })
    }

    @Test
    fun effortSetByReadsTheLevel() {
        assertEquals("high", effortSetBy("/effort high"))
        assertEquals("xhigh", effortSetBy("  /effort xhigh "))
        assertNull(effortSetBy("/effort ultracode on"))
        assertNull(effortSetBy("/effort loud"))
        assertNull(effortSetBy("set /effort high"))
    }

    @Test
    fun findsLinksNewestFirst() {
        val lines = listOf(
            "See https://example.com/a.",
            "Docs (https://example.com/docs) and https://example.com/a",
            "⏺ Opened https://github.com/acme/app/pull/12, ready for review",
        )
        assertEquals(
            listOf("https://github.com/acme/app/pull/12", "https://example.com/a", "https://example.com/docs"),
            findLinks(lines, columns = 80),
        )
    }

    @Test
    fun joinsALinkTheProgramWrapped() {
        // A 40-column screen: the link reaches the edge and carries on, indented, below.
        val first = "⏺ PR: https://github.com/acme/rex-mobi"
        val lines = listOf(first.padEnd(40), "  le/pull/1234", "", "Next steps")
        assertEquals(listOf("https://github.com/acme/rex-mobile/pull/1234"), findLinks(lines, columns = first.length))
        // Short of the edge, the next line is something else.
        assertEquals(listOf("https://example.com/x"), findLinks(listOf("go to https://example.com/x", "  later"), columns = 80))
    }

    @Test
    fun workingOnlyWhileConnected() {
        assertEquals(true, agentWorking(live = true, state = "working", interruptible = false))
        assertEquals(true, agentWorking(live = true, state = null, interruptible = true))
        assertEquals(false, agentWorking(live = true, state = "blocked", interruptible = false))
        // A dropped connection leaves the last status and screen read: the screen may turn off.
        assertEquals(false, agentWorking(live = false, state = "working", interruptible = true))
    }

    @Test
    fun stripKeepsTheLastModeWhileAPromptCoversTheFooter() {
        assertEquals("plan mode", stripValue(read = "plan mode", last = "auto mode", covered = false))
        assertEquals("auto mode", stripValue(read = null, last = "auto mode", covered = false))
        // Under a prompt, the banner's effort may be out of date: the last known one wins.
        assertEquals("high", stripValue(read = "medium", last = "high", covered = true))
        assertEquals("medium", stripValue(read = "medium", last = null, covered = true))
        assertNull(stripValue(read = null, last = null, covered = true))
    }

    @Test
    fun aHighlightedTypedAnswerLetsTheMessageBoxSend() {
        fun menu(selected: Int) = ScreenPrompt(
            ScreenPrompt.Kind.MENU, "Which colour?",
            listOf("Red", "Green", "Blue", "Type something.").mapIndexed { i, l -> PromptOption(i + 1, l, selected = i == selected) },
        )
        assertEquals(false, menu(0).awaitsText())
        assertEquals(true, menu(3).awaitsText())
        val multi = ScreenPrompt(
            ScreenPrompt.Kind.MENU, "Which fruits?",
            listOf(PromptOption(1, "Apple", checked = false), PromptOption(4, "Type something", selected = true, checked = false)),
        )
        assertEquals(true, multi.awaitsText())
    }
}
