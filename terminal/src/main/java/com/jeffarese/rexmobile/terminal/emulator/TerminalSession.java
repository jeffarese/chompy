package com.jeffarese.rexmobile.terminal.emulator;

import java.util.UUID;

/**
 * A terminal session whose program runs on a remote Rex server.
 * <p>
 * Modified from Termux's local-process session (Apache License 2.0): there is no subprocess and no
 * pty. Output from the server reaches the emulator through {@link #feed(byte[], int)}, and everything
 * the user types goes to the {@link RemoteInput} given at construction.
 * <p>
 * The remote terminal decides the size. {@link #updateSize(int, int)}, which the view calls with the
 * size that fits it, only reports that wish to {@link RemoteInput#onViewSize(int, int)}; the emulator
 * keeps the remote size set with {@link #setRemoteSize(int, int)}.
 * <p>
 * All methods must be called on the main thread.
 */
public class TerminalSession extends TerminalOutput {

    /** Where typed input and size wishes go. */
    public interface RemoteInput {
        void write(byte[] data, int offset, int count);

        void onViewSize(int columns, int rows);
    }

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;
    TerminalSessionClient mClient;
    private final RemoteInput mInput;
    private final Integer mTranscriptRows;
    private final byte[] mUtf8InputBuffer = new byte[5];

    private int mRemoteColumns;
    private int mRemoteRows;

    /** Whether the emulator is processing output, so what it writes now is its own answer to a query in it. */
    private boolean mFeeding;

    /** Whether output ended partway through a synchronized update, so the screen update waits for its end. */
    private boolean mUpdateHeld;

    public String mSessionName;

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client, RemoteInput input) {
        mTranscriptRows = transcriptRows;
        mClient = client;
        mInput = input;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    /** The view's wish for a size. The emulator keeps the remote size. */
    public void updateSize(int columns, int rows) {
        mInput.onViewSize(columns, rows);
        if (mEmulator == null && mRemoteColumns == 0) {
            // Until the server reports a size, show something rather than nothing.
            mEmulator = new TerminalEmulator(this, Math.max(4, columns), Math.max(4, rows), mTranscriptRows, mClient);
        }
    }

    /** Set the size the remote terminal has. */
    public void setRemoteSize(int columns, int rows) {
        if (columns <= 0 || rows <= 0) return;
        mRemoteColumns = columns;
        mRemoteRows = rows;
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(this, columns, rows, mTranscriptRows, mClient);
        } else if (mEmulator.mColumns != columns || mEmulator.mRows != rows) {
            mEmulator.resize(columns, rows);
        }
        notifyScreenUpdate();
    }

    public int getRemoteColumns() {
        return mRemoteColumns;
    }

    public int getRemoteRows() {
        return mRemoteRows;
    }

    /** Process output from the remote program. */
    public void feed(byte[] data, int length) {
        if (mEmulator == null) {
            int cols = mRemoteColumns > 0 ? mRemoteColumns : 80;
            int rows = mRemoteRows > 0 ? mRemoteRows : 24;
            mEmulator = new TerminalEmulator(this, cols, rows, mTranscriptRows, mClient);
        }
        mFeeding = true;
        try {
            mEmulator.append(data, length);
        } finally {
            mFeeding = false;
        }
        if (mEmulator.isSynchronizedUpdateActive()) {
            // The program is partway through drawing a frame: keep showing the last whole one until it ends, so a
            // frame split across network messages isn't drawn half old and half new.
            mUpdateHeld = true;
            return;
        }
        mUpdateHeld = false;
        notifyScreenUpdate();
    }

    /** True while a screen update waits for a synchronized update to end. See {@link #releaseHeldUpdate()}. */
    public boolean isUpdateHeld() {
        return mUpdateHeld;
    }

    /** Show the screen as it is, for a synchronized update that has taken too long to end. */
    public void releaseHeldUpdate() {
        if (!mUpdateHeld) return;
        mUpdateHeld = false;
        notifyScreenUpdate();
    }

    /** Clear everything, ready for a fresh stream. */
    public void clearForFreshStream() {
        mUpdateHeld = false;
        if (mEmulator != null) {
            replaceEmulator(new TerminalEmulator(this, mEmulator.mColumns, mEmulator.mRows, mTranscriptRows, mClient));
            notifyScreenUpdate();
        }
    }

    /**
     * Show the terminal a stream's snapshot restores, in place of what was shown, once its screen has
     * arrived. Its history follows with {@link #restoreHistory}. Returns false if the snapshot can't
     * be read, leaving the screen as it was.
     */
    public boolean showSnapshot(SnapshotDecoder snapshot) {
        long terminal = snapshot.takeTerminal(TerminalEmulator.getTerminalTranscriptRows(mTranscriptRows));
        if (terminal == 0) return false;
        mUpdateHeld = false;
        TerminalEmulator restored = new TerminalEmulator(this, terminal, mTranscriptRows, mClient);
        restored.applyProgramColors();
        replaceEmulator(restored);
        notifyScreenUpdate();
        return true;
    }

    /**
     * Restore up to {@code maxPages} pages of a shown snapshot's history, older rows above those
     * already there. Returns the rows restored, -1 once the history is all there or as long as it
     * may be, or -2 if it can't be read, which leaves what was restored.
     * <p>
     * Restore all of it before feeding output or resizing: libghostty-vt can't add older pages above
     * a terminal resized since, and adds them back above one whose history output cleared.
     */
    public int restoreHistory(SnapshotDecoder snapshot, int maxPages) {
        if (mEmulator == null) return Ghostty.SNAPSHOT_ERROR;
        final int rows = snapshot.next(maxPages);
        if (rows <= 0) return rows;
        mEmulator.getScreen().changed(0);
        notifyScreenUpdate();
        return rows;
    }

    /** Free the native terminal, for a session that is done with. What still reads it reads a blank screen. */
    public void release() {
        if (mEmulator != null) mEmulator.release();
    }

    private void replaceEmulator(TerminalEmulator emulator) {
        if (mEmulator != null) mEmulator.release();
        mEmulator = emulator;
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Send typed input to the remote program.
     * <p>
     * The emulator also writes here to answer queries in the output, such as device attributes, the cursor position,
     * modes, colors or the Kitty keyboard flags. The server's own terminal has already answered those, so a second
     * answer from here would reach the program later as if typed. Writes made while output is fed are dropped; keys,
     * mouse events and pastes come from outside {@link #feed(byte[], int)} and still go through.
     */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mFeeding) return;
        mInput.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= 0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= 0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= 0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        if (mClient != null) mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        if (mEmulator == null) return;
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** A remote session ends when its stream ends, not here. */
    public void finishIfRunning() {
    }

    public boolean isRunning() {
        return true;
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        if (mClient != null) mClient.onTitleChanged(this);
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mClient != null) mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mClient != null) mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        if (mClient != null) mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        if (mClient != null) mClient.onColorsChanged(this);
    }
}
