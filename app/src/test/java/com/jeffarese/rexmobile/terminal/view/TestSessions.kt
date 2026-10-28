package com.jeffarese.rexmobile.terminal.view

import com.jeffarese.rexmobile.terminal.emulator.TerminalSession
import com.jeffarese.rexmobile.terminal.emulator.TerminalSessionClient
import java.io.ByteArrayOutputStream

/** A remote session with no server: output is fed by the test, and what is typed collects in [typed]. */
internal class TestSession(
    columns: Int, rows: Int, transcriptRows: Int = 500,
    private val onViewSize: (columns: Int, rows: Int) -> Unit = { _, _ -> },
    onUpdate: () -> Unit = {},
) {
    val typed = ByteArrayOutputStream()

    val session = TerminalSession(transcriptRows, NoopClient(onUpdate), object : TerminalSession.RemoteInput {
        override fun write(data: ByteArray, offset: Int, count: Int) = typed.write(data, offset, count)
        override fun onViewSize(columns: Int, rows: Int) = this@TestSession.onViewSize(columns, rows)
    }).apply { setRemoteSize(columns, rows) }

    val emulator get() = session.emulator!!

    fun print(text: String) {
        val bytes = text.toByteArray()
        session.feed(bytes, bytes.size)
    }

    /** Numbered lines, each ending in a newline. */
    fun printLines(from: Int, count: Int) = print((from until from + count).joinToString("") { "line $it\r\n" })

    /** The text of [rows] rows from [top], trimmed. */
    fun text(top: Int, rows: Int = emulator.mRows): List<String> =
        (top until top + rows).map { emulator.screen.getSelectedText(0, it, emulator.mColumns, it).trim() }

    val history get() = emulator.screen.activeTranscriptRows
}

private class NoopClient(private val onUpdate: () -> Unit) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) = onUpdate()
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
