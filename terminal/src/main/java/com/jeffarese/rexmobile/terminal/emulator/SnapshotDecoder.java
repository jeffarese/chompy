package com.jeffarese.rexmobile.terminal.emulator;

/**
 * Restores a terminal from the Ghostty snapshot that starts a Rex stream, as its bytes arrive.
 * <p>
 * A snapshot holds the screen first and then the history, newest first. Once the screen is in
 * ({@link #READY}) {@link TerminalSession#showSnapshot} shows it, and once all of it is in
 * ({@link #COMPLETE}) {@link TerminalSession#restoreHistory} adds the history.
 * <p>
 * Main thread only. {@link #close()} frees it; the terminal it restored stays with the session.
 */
public final class SnapshotDecoder implements AutoCloseable {

    /** Not a snapshot this terminal can read, such as one from a newer snapshot format. */
    public static final int FAILED = Ghostty.SNAPSHOT_FAILED;
    /** Not enough has arrived to show anything. */
    public static final int PARTIAL = Ghostty.SNAPSHOT_PARTIAL;
    /** The screen has arrived, and can be shown. */
    public static final int READY = Ghostty.SNAPSHOT_READY;
    /** All of the snapshot has arrived. */
    public static final int COMPLETE = Ghostty.SNAPSHOT_COMPLETE;

    private long mHandle;
    private int mState = PARTIAL;
    private boolean mShown;
    private boolean mFinished;

    public SnapshotDecoder() {
        mHandle = Ghostty.decoderNew();
        if (mHandle == 0) mState = FAILED;
    }

    /** Add bytes of the snapshot that arrived, and say how much of it is in. */
    public int feed(byte[] data, int offset, int length) {
        if (mState == FAILED || length == 0) return mState;
        mState = Ghostty.decoderFeed(mHandle, data, offset, length);
        return mState;
    }

    public int state() {
        return mState;
    }

    /** The bytes up to the screen's end, which it took to show something; 0 until they arrive. */
    public long readyBytes() {
        return Ghostty.decoderReadyBytes(mHandle);
    }

    /** The terminal restored through its screen, or 0 if it can't be. */
    long takeTerminal(int scrollback) {
        if (mState < READY || mShown) return 0;
        long terminal = Ghostty.decoderReady(mHandle, scrollback);
        if (terminal == 0) {
            mState = FAILED;
        } else {
            mShown = true;
        }
        return terminal;
    }

    /** Restore up to {@code maxPages} pages of history: the rows restored, or -1 when done, or -2 if they can't be. */
    int next(int maxPages) {
        if (mFinished) return Ghostty.SNAPSHOT_FINISHED;
        if (mState != COMPLETE || !mShown) return Ghostty.SNAPSHOT_ERROR;
        int rows = Ghostty.decoderNext(mHandle, maxPages);
        if (rows == Ghostty.SNAPSHOT_FINISHED) mFinished = true;
        if (rows == Ghostty.SNAPSHOT_ERROR) mState = FAILED;
        return rows;
    }

    @Override
    public void close() {
        if (mHandle == 0) return;
        Ghostty.decoderFree(mHandle);
        mHandle = 0;
    }
}
