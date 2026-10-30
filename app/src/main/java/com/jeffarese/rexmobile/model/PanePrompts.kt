package com.jeffarese.rexmobile.model

import android.os.SystemClock
import com.jeffarese.rexmobile.rex.FormatResult
import com.jeffarese.rexmobile.rex.RexJson
import com.jeffarese.rexmobile.rex.int
import com.jeffarese.rexmobile.rex.str
import com.jeffarese.rexmobile.terminal.FormatTail
import com.jeffarese.rexmobile.terminal.InputQueue
import com.jeffarese.rexmobile.terminal.PromptOption
import com.jeffarese.rexmobile.terminal.QuestionTab
import com.jeffarese.rexmobile.terminal.RemoteKey
import com.jeffarese.rexmobile.terminal.RemoteTerminal
import com.jeffarese.rexmobile.terminal.ScreenInfo
import com.jeffarese.rexmobile.terminal.ScreenPrompt
import com.jeffarese.rexmobile.terminal.ScreenReader
import com.jeffarese.rexmobile.terminal.emulator.TerminalSession
import com.jeffarese.rexmobile.terminal.emulator.TerminalSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A Claude Code prompt read off a pane's screen that one press answers: a permission prompt, with
 * what it asks about, or one of Claude's questions on its own. See [PanePrompts].
 */
data class PanePrompt(
    val hostId: String,
    val sessionId: String,
    val blockId: String,
    val prompt: ScreenPrompt,
    /** When it was read, on [SystemClock.elapsedRealtime]: it has been on screen since then at least. */
    val seenAt: Long,
) {
    /** True for a permission prompt, such as to run a command; false for one of Claude's questions. */
    val permission: Boolean get() = !prompt.claudeQuestion

    /** The options that answer it, in order: without a question's "Type something." and "Chat about this". */
    val answers: List<PromptOption> get() = PanePrompts.answersOf(prompt)
}

/**
 * Reads and answers Claude Code's prompts in panes that aren't open, such as from a notification,
 * over the host's control connection: the terminals' own streams are paused while the app is away.
 *
 * A screen is read as the terminal reads it: `terminal.format` fed to an emulator of the pane's
 * size, of a long history only the end, then [ScreenReader]. The emulator keeps the styles that set
 * apart what plain text wouldn't, such as option descriptions and the question's tab.
 *
 * An answer goes only to the prompt that was read. [answer] reads the screen again first, and
 * presses nothing if the question, what it asks about or its options have changed since. It presses
 * the keys the terminal's cards press, and no sooner than Claude takes them.
 */
class PanePrompts {
    /** How an [answer] went. */
    enum class Answer {
        /** Pressed, and the prompt has gone from the screen. */
        ANSWERED,
        /** Pressed, but the prompt still shows, or the key's arrival couldn't be confirmed. */
        UNCONFIRMED,
        /** Not pressed: the screen shows another prompt now, or none. */
        CHANGED,
        /** Not pressed: the pane's screen couldn't be read. */
        UNREACHABLE,
        /** Not pressed: an answer to the same pane is already under way. */
        BUSY,
    }

    /** Panes an answer is being pressed in, one at a time each, so a double tap can't answer twice. */
    private val answering = HashSet<String>()

    /**
     * The prompt a pane shows, if it is one that a press answers; null if it shows another, or none,
     * or its screen can't be read within [timeoutMs]. An agent may say it is waiting a moment before
     * its prompt is drawn, so a screen without one is read again until then.
     */
    suspend fun read(host: HostConnection, sessionId: String, blockId: String, timeoutMs: Long = READ_MS): PanePrompt? =
        withTimeoutOrNull(timeoutMs) {
            var info = screen(host, sessionId, blockId) ?: return@withTimeoutOrNull null
            while (info.prompt == null) {
                delay(REREAD_MS)
                info = screen(host, sessionId, blockId) ?: return@withTimeoutOrNull null
            }
            info.prompt?.takeIf(::answerable)?.let { PanePrompt(host.id, sessionId, blockId, it, SystemClock.elapsedRealtime()) }
        }

    /**
     * Answer [asked] with [option], one of its [PanePrompt.answers], as its card in the terminal
     * would: the screen is read again, and only if it still shows the same prompt is the option's
     * number pressed. Claude ignores digits in the first moments its buttons show, so a prompt read
     * only just now waits for that. Then the screen is watched for the prompt to go.
     */
    suspend fun answer(host: HostConnection, asked: PanePrompt, option: PromptOption): Answer {
        val index = asked.prompt.options.indexOf(option)
        if (index < 0) return Answer.CHANGED
        if (!synchronized(answering) { answering.add(asked.blockId) }) return Answer.BUSY
        try {
            val now = withTimeoutOrNull(READ_MS) { screen(host, asked.sessionId, asked.blockId) } ?: return Answer.UNREACHABLE
            val current = now.prompt?.takeIf { it.sameAs(asked.prompt) && answerable(it) } ?: return Answer.CHANGED
            if (current.options[index] !in answersOf(current)) return Answer.CHANGED
            val keys = current.menuKeys(index) ?: return Answer.CHANGED
            val wait = asked.seenAt + ARM_MS - SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            if (!press(host, asked.sessionId, asked.blockId, keys)) return Answer.UNCONFIRMED
            // Claude goes on once it takes the answer, and the prompt goes.
            val gone = withTimeoutOrNull(CONFIRM_MS) {
                do {
                    delay(CONFIRM_EVERY_MS)
                    val after = screen(host, asked.sessionId, asked.blockId)
                } while (after == null || after.prompt?.sameAs(asked.prompt) == true)
            }
            return if (gone != null) Answer.ANSWERED else Answer.UNCONFIRMED
        } finally {
            synchronized(answering) { answering.remove(asked.blockId) }
        }
    }

    /** The pane's screen as its terminal would read it; null if it can't be read. */
    private suspend fun screen(host: HostConnection, sessionId: String, blockId: String): ScreenInfo? = quietly {
        val size = host.blockMethod(sessionId, blockId, "size", timeoutMs = CALL_MS) as? JsonObject
        val columns = size?.int("columns")
        val rows = size?.int("rows")
        if (columns == null || rows == null || columns <= 0 || rows <= 0) return@quietly null
        val vt = redraw(host, sessionId, blockId, columns, rows)
        withContext(Dispatchers.Default) { screenOf(vt, columns, rows) }
    }

    /**
     * The pane's redraw as escape sequences, as the terminal reads it, but of a long history only
     * the end: the screen's [rows] lines, each about [columns] wide.
     */
    private suspend fun redraw(host: HostConnection, sessionId: String, blockId: String, columns: Int, rows: Int): String {
        val first = RexJson.decodeFromJsonElement<FormatResult>(
            host.blockMethod(sessionId, blockId, "format", buildJsonObject {
                put("format", "vt")
                put("extra_cursor", true)
                put("extra_modes", true)
                put("extra_scrolling_region", true)
                put("extra_style", true)
                put("extra_charsets", true)
                put("extra_tabstops", true)
                put("max_bytes", HEAD_BYTES)
            }, timeoutMs = CALL_MS),
        )
        val id = first.id ?: return first.content
        val total = first.total ?: return first.content + readBlob(host, sessionId, blockId, id, first.content.toByteArray().size.toLong(), Long.MAX_VALUE, cut = false)
        val tail = rows.toLong() * (columns + RemoteTerminal.LINE_OVERHEAD_BYTES)
        return FormatTail.load(first.content, total, rows, tail) { from, until, cut -> readBlob(host, sessionId, blockId, id, from, until, cut) }
    }

    /** A format result from byte [from] to at least [until], or to its end. [cut]: [from] may fall inside a character. */
    private suspend fun readBlob(host: HostConnection, sessionId: String, blockId: String, id: Long, from: Long, until: Long, cut: Boolean): String {
        val sb = StringBuilder()
        var at = from
        while (at < until) {
            val part = host.blockMethod(sessionId, blockId, "blob_read", buildJsonObject {
                put("id", id)
                put("offset", at)
                // A screen's worth, with room for the escaping the limit counts.
                put("max_bytes", minOf(until - at, MAX_BLOB_BYTES) * 5 / 4 + 1024)
            }, timeoutMs = CALL_MS).jsonObject
            val data = part.str("data").orEmpty()
            at += FormatTail.bytesRead(data, fromAnyOffset = cut && sb.isEmpty())
            sb.append(data)
            if ((part["eof"] as? JsonPrimitive)?.content == "true" || data.isEmpty()) break
        }
        return sb.toString()
    }

    /** Press [keys] in the pane, each pressed and let go in order, as the terminal sends keys. False if one may not have gone. */
    private suspend fun press(host: HostConnection, sessionId: String, blockId: String, keys: List<RemoteKey>): Boolean =
        quietly {
            withTimeout(PRESS_MS) {
                for (key in keys) for (action in listOf("press", "release")) {
                    host.call("block.event", buildJsonObject {
                        put("session_id", sessionId)
                        put("block_id", blockId)
                        putJsonObject("event") {
                            put("type", "key")
                            put("payload", InputQueue.keyPayload(key, action))
                        }
                    }, timeoutMs = CALL_MS)
                }
            }
            true
        } == true

    companion object {
        /** How long a read of a prompt may take, as it is waited for before a notification is posted. */
        const val READ_MS = 3_000L
        /** How soon a screen without a prompt yet is read again. */
        private const val REREAD_MS = 700L
        /** Claude Code ignores the digits typed in the first 600 ms its buttons show. */
        private const val ARM_MS = 650L
        /** How long the screen is watched for an answered prompt to go, and how often it is read meanwhile. */
        private const val CONFIRM_MS = 2_500L
        private const val CONFIRM_EVERY_MS = 400L
        /** The longest a call may take. A screen's redraw is a few KB, but the link may be slow. */
        private const val CALL_MS = 3_000L
        /** The longest pressing an answer may take, every key's press and release. */
        private const val PRESS_MS = 3_000L
        /** The first part of a redraw: its opening modes, or a screen with little history whole. */
        private const val HEAD_BYTES = 8 * 1024
        private const val MAX_BLOB_BYTES = 192L * 1024
        /** Rows the emulator a screen is read into keeps above it: only its fewest. */
        private const val HISTORY_ROWS = 100

        /** The options of [prompt] that answer it, each pressed by its number. */
        internal fun answersOf(prompt: ScreenPrompt): List<PromptOption> =
            prompt.options.filter { it.role == PromptOption.Role.ANSWER && it.number in 1..9 }

        /**
         * True for a prompt that one press answers and that makes sense without the terminal: a
         * permission prompt that says what it is about, such as the command to run, or one of
         * Claude's questions on its own, with one answer to pick. A question among others, a
         * multi-select one or the review of answers takes the terminal's card.
         */
        internal fun answerable(prompt: ScreenPrompt): Boolean {
            if (prompt.kind != ScreenPrompt.Kind.MENU || prompt.multiSelect || prompt.review.isNotEmpty()) return false
            val alone = if (prompt.claudeQuestion) prompt.tabs.singleOrNull()?.state == QuestionTab.State.OPEN else prompt.context.isNotEmpty()
            return alone && answersOf(prompt).isNotEmpty()
        }

        /** [vt], a pane's redraw, as an emulator [columns] by [rows] reads it. */
        internal fun screenOf(vt: String, columns: Int, rows: Int): ScreenInfo {
            // Nothing here goes back to the pane, such as the emulator's answers to queries in the redraw.
            val session = TerminalSession(HISTORY_ROWS, Unseen, object : TerminalSession.RemoteInput {
                override fun write(data: ByteArray, offset: Int, count: Int) = Unit
                override fun onViewSize(columns: Int, rows: Int) = Unit
            })
            try {
                session.setRemoteSize(columns, rows)
                val bytes = vt.toByteArray()
                session.feed(bytes, bytes.size)
                return ScreenReader.read(session.emulator)
            } finally {
                session.release()
            }
        }
    }
}

/** The same question about the same thing with the same options, whichever is highlighted. */
private fun ScreenPrompt.sameAs(other: ScreenPrompt) =
    kind == other.kind && question == other.question && context == other.context &&
        options.map { it.label } == other.options.map { it.label } && tabs.map { it.label } == other.tabs.map { it.label }

/** What [block] gives, or null if it fails or its calls time out. Being cancelled still cancels. */
private suspend fun <T> quietly(block: suspend () -> T?): T? = try {
    block()
} catch (e: TimeoutCancellationException) {
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** An emulator nobody sees. */
private object Unseen : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) = Unit
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
