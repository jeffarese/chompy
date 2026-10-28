package com.jeffarese.rexmobile.terminal

import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider

/**
 * Attach a view to [terminal] as the terminal screen does: only a terminal on screen reads it for
 * prompts and Claude Code's footer. Main thread.
 */
internal fun showOnScreen(terminal: RemoteTerminal): TerminalViewport =
    TerminalViewport(ApplicationProvider.getApplicationContext(), Typeface.MONOSPACE).also {
        it.attach(terminal.session)
        terminal.view = it.terminalView
    }
