package com.jeffarese.rexmobile.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Restarts Claude Code in a terminal in its classic renderer, so the phone scrolls the conversation
 * itself. In fullscreen, Claude Code draws on the alternate screen and the terminal keeps no history
 * of it: every scroll goes to Claude as wheel events and waits for it to redraw. The classic renderer
 * writes the conversation into the terminal's history, like any other output.
 *
 * It quits Claude and resumes the same conversation with CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1 on
 * that one command, so the user's settings and every other session stay as they were. The mode and
 * effort on Claude's footer go along; a model given on Claude's command line can't, as Rex doesn't
 * tell its arguments. That needs Claude idle with nothing typed, and started from a shell, which
 * takes over when it quits instead of the pane closing.
 */
class ClassicSwitch(private val link: Link, private val scope: CoroutineScope, private val timing: Timing = Timing()) {
    interface Link {
        /** The screen as last read. */
        fun info(): ScreenInfo

        /** Rex's com.superlogical.terminal.process result: the processes running in the terminal. */
        suspend fun process(): JsonObject?

        fun type(text: String)

        fun key(key: RemoteKey)

        /** Every line of the history and screen, oldest first, with wrapped rows joined. */
        fun lines(): List<String>
    }

    class Timing(
        val beforeEnter: Long = 500,
        val poll: Long = 200,
        /** How long the screen stays still once the shell has drawn its prompt. */
        val settle: Long = 800,
        val quit: Long = 20_000,
        val resume: Long = 45_000,
    )

    sealed interface State {
        data object Idle : State

        data class Working(val step: String) : State

        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _dismissed = MutableStateFlow(false)
    /** The user waved the offer away for this terminal. */
    val dismissed: StateFlow<Boolean> = _dismissed

    private var job: Job? = null

    fun dismiss() {
        _dismissed.value = true
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = try {
                switch()
            } catch (e: Exception) {
                State.Failed("Couldn't switch: ${e.message ?: "the server didn't answer"}")
            }
        }
    }

    private suspend fun switch(): State {
        val info = link.info()
        if (!info.fullscreen) return State.Idle
        refusal(info)?.let { return State.Failed(it) }
        _state.value = State.Working("Checking how Claude was started…")
        if (!startedFromShell(link.process())) {
            return State.Failed("Claude was started without a shell here, so quitting it would close the pane.")
        }
        // Read the footer again: the check took a round trip.
        val now = link.info()
        refusal(now)?.let { return State.Failed(it) }
        val flags = resumeFlags(now)

        _state.value = State.Working("Quitting Claude…")
        val seen = resumeIds(link.lines()).size
        link.type("/exit")
        delay(timing.beforeEnter)
        link.key(RemoteKey.ENTER)
        val id = waitFor(timing.quit) { if (!link.info().fullscreen) resumeIds(link.lines()).drop(seen).lastOrNull() else null }
            ?: return State.Failed(
                if (link.info().fullscreen) "Claude didn't quit. Try again once it's idle."
                else "Claude quit without its resume line. Run claude --resume to pick the conversation.",
            )

        _state.value = State.Working("Resuming the conversation…")
        // The shell draws its prompt after Claude's goodbye, and a line editor can drop what was typed before it.
        settle()
        link.type(resumeCommand(id, flags))
        link.key(RemoteKey.ENTER)
        val back = waitFor(timing.resume) { link.info().takeIf { it.claudeMode != null || it.claudeInput != null } }
            ?: return State.Failed("Claude didn't come back. Its conversation is $id.")
        return if (back.fullscreen) State.Failed("Claude came back in fullscreen.") else State.Idle
    }

    /** Waits for the screen to stay the same for [Timing.settle], or up to [Timing.quit]. */
    private suspend fun settle() {
        var last = link.lines()
        var still = 0L
        var waited = 0L
        while (still < timing.settle && waited < timing.quit) {
            delay(timing.poll)
            waited += timing.poll
            val now = link.lines()
            if (now == last) still += timing.poll else still = 0
            last = now
        }
    }

    private suspend fun <T> waitFor(timeout: Long, read: () -> T?): T? {
        var waited = 0L
        while (waited <= timeout) {
            read()?.let { return it }
            delay(timing.poll)
            waited += timing.poll
        }
        return null
    }

    companion object {
        private val SHELLS = setOf("zsh", "bash", "fish", "sh", "dash", "ksh", "tcsh", "csh", "nu", "elvish", "xonsh")
        private val RESUME = Regex("""\bclaude (?:--resume|-r) ([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\b""")
        private val LEVELS = setOf("low", "medium", "high", "xhigh", "max")
        private val MODES = mapOf(
            "auto mode" to "auto",
            "manual mode" to "manual",
            "plan mode" to "plan",
            "accept edits" to "acceptEdits",
            "bypass permissions" to "bypassPermissions",
        )

        /** Why Claude can't quit now, from what its screen shows; null when it can. */
        fun refusal(info: ScreenInfo): String? = when {
            info.interruptible -> "Claude is working. Switch once it has finished."
            info.prompt != null -> "Answer Claude's question first."
            info.claudeInput == null -> "Claude's message box isn't showing. Close what's open in Claude first."
            info.claudeInput.isNotEmpty() -> "Send or clear what's typed in Claude's message box first."
            else -> null
        }

        /** Whether Claude runs in the foreground with a shell under it, which takes over when it quits. */
        fun startedFromShell(process: JsonObject?): Boolean {
            val foreground = process?.get("foreground") as? JsonObject ?: return false
            val claude = listOf("argv0", "name", "invoked_path", "script").any { k ->
                foreground.string(k)?.substringAfterLast('/')?.let { it == "claude" || it == "cli.js" && k == "script" } == true
            }
            val parent = (process["ancestors"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return false
            val shell = (parent.string("name") ?: parent.string("argv0")?.removePrefix("-"))?.substringAfterLast('/')
            return claude && shell in SHELLS
        }

        /** The conversations Claude said how to resume, in the order they appear. */
        fun resumeIds(lines: List<String>): List<String> = lines.mapNotNull { RESUME.find(it)?.groupValues?.get(1) }

        /** Command-line flags that bring back the mode and effort the footer shows. */
        fun resumeFlags(info: ScreenInfo): List<String> = listOfNotNull(
            MODES[info.claudeMode]?.let { "--permission-mode $it" },
            info.claudeEffort?.takeIf { it in LEVELS }?.let { "--effort $it" },
        )

        fun resumeCommand(id: String, flags: List<String>): String =
            (listOf("CLAUDE_CODE_DISABLE_ALTERNATE_SCREEN=1 claude --resume $id") + flags).joinToString(" ")

        private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
