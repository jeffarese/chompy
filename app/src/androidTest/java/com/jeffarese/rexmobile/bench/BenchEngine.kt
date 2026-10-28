package com.jeffarese.rexmobile.bench

import android.graphics.Canvas
import android.graphics.Typeface
import com.jeffarese.rexmobile.terminal.emulator.TerminalSession
import com.jeffarese.rexmobile.terminal.emulator.TerminalSessionClient
import com.jeffarese.rexmobile.terminal.emulator.TextStyle
import com.jeffarese.rexmobile.terminal.view.TerminalRenderer

/**
 * Everything the engine benchmarks ask of the terminal engine, in one place: when the engine
 * changes, only this file does. This one is for libghostty-vt, drawn by the vendored Termux renderer.
 */
object BenchEngine {
    const val NAME = "libghostty"

    /** A terminal of [columns] by [rows] that keeps [historyRows] rows above the screen, fed by [feed]. */
    fun newSession(columns: Int, rows: Int, historyRows: Int): TerminalSession =
        TerminalSession(historyRows + rows, NoopClient, object : TerminalSession.RemoteInput {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun onViewSize(columns: Int, rows: Int) = Unit
        }).apply { setRemoteSize(columns, rows) }

    /** Parse output into the terminal. */
    fun feed(session: TerminalSession, chunk: ByteArray) = session.feed(chunk, chunk.size)

    /** Rows held above the screen, in the history. */
    fun historyRows(session: TerminalSession): Int = session.emulator.screen.activeTranscriptRows

    /** The memory the engine says it holds outside the Java heap, or null if it doesn't say. */
    fun engineBytes(session: TerminalSession): Long? = session.emulator.residentBytes

    /** Compress the history as far as the engine can, as the app does while a stream is quiet. */
    fun compact(session: TerminalSession) {
        session.emulator.compressHistory(true)
    }

    /** Free what the engine holds outside the Java heap. */
    fun release(session: TerminalSession) = session.emulator.release()

    /** The text of a screen row, without trailing spaces. */
    fun rowText(session: TerminalSession, row: Int): String {
        val e = session.emulator
        return e.screen.getSelectedText(0, row, e.mColumns - 1, row).trimEnd()
    }

    /** Draws a terminal's screen as the app's terminal view does. */
    class Renderer(textSizePx: Int, typeface: Typeface) {
        internal val inner = TerminalRenderer(textSizePx, typeface)
        val cellWidthPx: Float get() = inner.fontWidth
        val cellHeightPx: Int get() = inner.fontLineSpacing
    }

    /** Draw one frame of the screen, background first as the view's is. */
    fun render(renderer: Renderer, session: TerminalSession, canvas: Canvas) {
        val e = session.emulator
        canvas.drawColor(e.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
        renderer.inner.render(e, canvas, 0, -1, -1, -1, -1)
    }

    private object NoopClient : TerminalSessionClient {
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
}
