package com.jeffarese.rexmobile.terminal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Restarting Claude Code in its classic renderer: what it types into the terminal, and when it won't. */
class ClassicSwitchTest {
    private val fast = ClassicSwitch.Timing(beforeEnter = 1, poll = 1, settle = 3, quit = 300, resume = 300)
    private val idle = ScreenInfo(claudeMode = "manual mode", claudeEffort = "high", claudeInput = "", fullscreen = true)
    private val older = "11111111-2222-3333-4444-555555555555"
    private val id = "e0af63ff-b104-4fbc-88fc-a985c2bdac7e"

    /** As Rex reported it for Claude Code started from zsh in a Rex pane. */
    private val fromZsh = fromShell("zsh")

    private fun fromShell(name: String) = json(
        """{"last_exit":null,"child":{"pid":59564,"user_id":0,"user":"root","name":"login","path":"/usr/bin/login"},
        "foreground":{"pid":59639,"user_id":502,"name":"2.1.311","path":"/Users/j/.local/share/claude/versions/2.1.311",
        "invoked_path":"/Users/j/.local/bin/claude","argv0":"claude","cwd":"/tmp/x"},
        "ancestors":[{"pid":59567,"user_id":502,"name":"$name","path":"/bin/$name","argv0":"-$name","cwd":"/tmp/x"},
        {"pid":59564,"user_id":0,"name":"login","path":"/usr/bin/login"}],"home":"/Users/j"}""",
    )

    /** Claude Code as the pane's own program: quitting it ends the pane. */
    private val direct = json(
        """{"last_exit":null,"child":{"pid":1,"user_id":502,"name":"2.1.311","argv0":"claude"},
        "foreground":{"pid":1,"user_id":502,"name":"2.1.311","argv0":"claude"}}""",
    )

    /**
     * A terminal running Claude Code that quits on /exit and comes back when resumed, unless told otherwise.
     * Claude's goodbye shows [goodbyeLate] reads of the screen after it leaves the alternate screen.
     */
    private inner class Fake(
        var info: ScreenInfo = idle,
        private val process: JsonObject? = fromZsh,
        val quits: Boolean = true,
        val goodbyeLate: Int = 0,
    ) : ClassicSwitch.Link {
        val typed = mutableListOf<String>()
        /** The main screen and its history, an older conversation's resume lines among them. */
        val lines = mutableListOf(" ~ ❯ claude", "Resume this session with:", "claude --resume $older", " ~ ❯ claude --resume $older")
        private var goodbyeIn: Int? = null

        override fun info() = info

        override suspend fun process() = process

        override fun type(text: String) {
            typed += text
        }

        override fun key(key: RemoteKey) {
            assertEquals(RemoteKey.ENTER, key)
            val line = typed.last()
            typed += "⏎"
            if (line == "/exit" && quits) {
                info = ScreenInfo()
                goodbyeIn = goodbyeLate
            } else if (line.contains("claude --resume")) {
                info = ScreenInfo(claudeMode = "manual mode", claudeInput = "")
            }
        }

        override fun lines() = read()

        override fun screen() = read().takeLast(10)

        /** The alternate screen keeps no history: the main one's shows again once Claude leaves it. */
        private fun read(): List<String> {
            if (info.fullscreen) return listOf("Claude Code's conversation")
            goodbyeIn?.let { n ->
                goodbyeIn = n - 1
                if (n == 0) lines += listOf("", "Resume this session with:", "claude --resume $id", " ~/x ❯")
            }
            return lines.toList()
        }
    }

    private fun run(fake: Fake): ClassicSwitch.State = runBlocking {
        val switch = ClassicSwitch(fake, this, fast)
        switch.start()
        switch
    }.state.value

    @Test
    fun quitsAndResumesTheSameConversationWithItsModeAndEffort() {
        val fake = Fake()
        assertEquals(ClassicSwitch.State.Idle, run(fake))
        assertEquals(
            listOf("/exit", "⏎", "CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1 claude --resume $id --permission-mode manual --effort high", "⏎"),
            fake.typed,
        )
    }

    @Test
    fun waitsForClaudesResumeLineAfterTheOlderOnesInHistory() {
        val fake = Fake(goodbyeLate = 2)
        assertEquals(ClassicSwitch.State.Idle, run(fake))
        assertEquals("CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1 claude --resume $id --permission-mode manual --effort high", fake.typed[2])
    }

    @Test
    fun shellsWithoutVariablesBeforeACommandGetThemThroughEnv() {
        val fake = Fake(process = fromShell("tcsh"))
        assertEquals(ClassicSwitch.State.Idle, run(fake))
        assertEquals("env CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1 claude --resume $id --permission-mode manual --effort high", fake.typed[2])
        assertEquals("CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1 claude --resume $id", ClassicSwitch.resumeCommand(id, emptyList(), "fish"))
    }

    @Test
    fun onlyTheFooterLeadsToFlags() {
        assertEquals(
            listOf("--permission-mode acceptEdits"),
            ClassicSwitch.resumeFlags(ScreenInfo(claudeMode = "accept edits", claudeEffort = "loud")),
        )
        assertEquals(emptyList<String>(), ClassicSwitch.resumeFlags(ScreenInfo(claudeMode = "don't ask")))
    }

    @Test
    fun waitsForClaudeToBeIdleWithNothingTyped() {
        for ((info, message) in listOf(
            idle.copy(interruptible = true) to "Claude is working",
            idle.copy(prompt = ScreenPrompt(ScreenPrompt.Kind.MENU, "Proceed?", listOf(PromptOption(1, "Yes", selected = true)))) to "Answer",
            idle.copy(claudeInput = "half a thought") to "Send or clear",
            idle.copy(claudeInput = null) to "message box isn't showing",
        )) {
            val fake = Fake(info)
            val state = run(fake)
            assertTrue("$state", state is ClassicSwitch.State.Failed && message in state.message)
            assertEquals(emptyList<String>(), fake.typed)
        }
    }

    @Test
    fun leavesClaudeRunningWithoutAShellToFallBackOn() {
        val fake = Fake(process = direct)
        val state = run(fake)
        assertTrue("$state", state is ClassicSwitch.State.Failed && "without a shell" in state.message)
        assertEquals(emptyList<String>(), fake.typed)
        assertEquals("zsh", ClassicSwitch.shell(fromZsh))
        assertNull(ClassicSwitch.shell(direct))
        assertNull(ClassicSwitch.shell(null))
    }

    @Test
    fun saysSoWhenClaudeDoesntQuit() {
        val fake = Fake(quits = false)
        val state = run(fake)
        assertTrue("$state", state is ClassicSwitch.State.Failed && "didn't quit" in state.message)
        assertEquals(listOf("/exit", "⏎"), fake.typed)
    }

    @Test
    fun nothingToDoOutsideFullscreen() {
        val fake = Fake(idle.copy(fullscreen = false))
        assertEquals(ClassicSwitch.State.Idle, run(fake))
        assertEquals(emptyList<String>(), fake.typed)
    }

    @Test
    fun dismissingClearsAFailure() = runBlocking {
        val switch = ClassicSwitch(Fake(idle.copy(interruptible = true)), this, fast)
        switch.start()
        switch.state.first { it is ClassicSwitch.State.Failed }
        switch.dismiss()
        assertEquals(ClassicSwitch.State.Idle, switch.state.value)
        assertTrue(switch.dismissed.value)
    }

    @Test
    fun resumeLinesInOrder() {
        assertEquals(
            listOf(older, id),
            ClassicSwitch.resumeIds(listOf("claude --resume $older", "nothing", "  claude -r $id   ", "claude --resume not-an-id")),
        )
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
}
