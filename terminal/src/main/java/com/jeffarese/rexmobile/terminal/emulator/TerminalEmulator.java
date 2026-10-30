package com.jeffarese.rexmobile.terminal.emulator;

/**
 * A terminal emulator backed by libghostty-vt, the terminal of Ghostty and of the Rex server itself.
 * <p>
 * libghostty-vt parses the output and keeps the screen, the scrollback and every mode; this class
 * keeps Termux's interface to it, which the view, its renderer and the app read, and the colors,
 * which the app's theme sets.
 * <p>
 * Replies to queries in the output (device attributes, cursor position and the like) are never
 * sent: the server's own terminal has answered them already.
 * <p>
 * All methods must be called on the thread that owns the terminal, the main thread in the app.
 * {@link #release()} frees the native terminal.
 */
public final class TerminalEmulator {

    public static final int MOUSE_LEFT_BUTTON = 0;

    /** Mouse moving while having left mouse button pressed. */
    public static final int MOUSE_LEFT_BUTTON_MOVED = 32;
    public static final int MOUSE_WHEELUP_BUTTON = 64;
    public static final int MOUSE_WHEELDOWN_BUTTON = 65;

    /** Used for invalid data - http://en.wikipedia.org/wiki/Replacement_character#Replacement_character */
    public static final int UNICODE_REPLACEMENT_CHAR = 0xFFFD;

    public static final int TERMINAL_TRANSCRIPT_ROWS_MIN = 100;
    public static final int TERMINAL_TRANSCRIPT_ROWS_MAX = 50000;
    public static final int DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000;

    /* The supported terminal cursor styles. */
    public static final int TERMINAL_CURSOR_STYLE_BLOCK = 0;
    public static final int TERMINAL_CURSOR_STYLE_UNDERLINE = 1;
    public static final int TERMINAL_CURSOR_STYLE_BAR = 2;
    public static final int DEFAULT_TERMINAL_CURSOR_STYLE = TERMINAL_CURSOR_STYLE_BLOCK;

    // DEC private modes read from the terminal.
    private static final int MODE_CURSOR_KEYS = 1;
    private static final int MODE_REVERSE_VIDEO = 5;
    private static final int MODE_KEYPAD_KEYS = 66;
    private static final int MODE_BUTTON_EVENT_MOUSE = 1002;
    private static final int MODE_ANY_EVENT_MOUSE = 1003;
    private static final int MODE_SGR_MOUSE = 1006;
    private static final int MODE_BRACKETED_PASTE = 2004;
    private static final int MODE_SYNCHRONIZED_OUTPUT = 2026;

    /** The number of character rows and columns in the terminal screen. */
    public int mRows, mColumns;

    public final TerminalColors mColors = new TerminalColors();

    private long mHandle;
    private final TerminalBuffer mScreen;
    private final TerminalOutput mSession;
    private TerminalSessionClient mClient;
    private final int mTranscriptRows;

    /** Rows scrolled into history since {@link #clearScrollCounter()}, for the view to keep its place. */
    private int mScrollCounter;
    private boolean mCursorBlinkingEnabled;
    private boolean mCursorBlinkState;
    private String mTitle;
    private int[] mChangedColors;
    /** The colors the program had set when they were last applied, 0 where it set none. */
    private int[] mProgramColors;
    private boolean mHadProgramColors;

    public TerminalEmulator(TerminalOutput session, int columns, int rows, Integer transcriptRows, TerminalSessionClient client) {
        this(session, Ghostty.newTerminal(columns, rows, getTerminalTranscriptRows(transcriptRows)), transcriptRows, client);
    }

    /** Take over a native terminal, such as one restored from a snapshot. */
    TerminalEmulator(TerminalOutput session, long handle, Integer transcriptRows, TerminalSessionClient client) {
        if (handle == 0) throw new IllegalStateException("libghostty-vt couldn't make a terminal");
        mSession = session;
        mHandle = handle;
        mClient = client;
        mTranscriptRows = getTerminalTranscriptRows(transcriptRows);
        mColumns = (int) Ghostty.getLong(handle, Ghostty.COLUMNS);
        mRows = (int) Ghostty.getLong(handle, Ghostty.ROWS);
        mScreen = new TerminalBuffer(this, mColumns, mRows, mTranscriptRows);
        mCursorBlinkState = true;
        // A restored terminal has its title already, without an event for it.
        mTitle = Ghostty.title(handle);
    }

    static int getTerminalTranscriptRows(Integer transcriptRows) {
        if (transcriptRows == null || transcriptRows < TERMINAL_TRANSCRIPT_ROWS_MIN || transcriptRows > TERMINAL_TRANSCRIPT_ROWS_MAX)
            return DEFAULT_TERMINAL_TRANSCRIPT_ROWS;
        return transcriptRows;
    }

    /** Free the native terminal. Nothing may be called after. */
    public void release() {
        if (mHandle == 0) return;
        Ghostty.free(mHandle);
        mHandle = 0;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super.finalize();
        }
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        setCursorBlinkState(true);
    }

    public TerminalBuffer getScreen() {
        return mScreen;
    }

    /** Process output from the remote program. */
    public void append(byte[] buffer, int length) {
        Ghostty.write(mHandle, buffer, 0, length);
        afterChange(Ghostty.takeScrolled(mHandle));
        final int events = Ghostty.takeEvents(mHandle);
        if ((events & Ghostty.EVENT_TITLE) != 0) {
            final String old = mTitle;
            mTitle = Ghostty.title(mHandle);
            mSession.titleChanged(old, mTitle);
        }
        if ((events & Ghostty.EVENT_BELL) != 0) mSession.onBell();
        if (mayEndOsc(buffer, length)) applyProgramColors();
    }

    private void afterChange(int scrolled) {
        if (scrolled > 0) mScrollCounter += scrolled;
        // When the rows can't be followed, move a view scrolled back to the top of what is there.
        else if (scrolled < 0) mScrollCounter += mScreen.getActiveTranscriptRows() + mRows;
        mScreen.changed(scrolled);
    }

    /**
     * Whether the output may end an OSC sequence, the only way a program changes colors: a change
     * takes effect at its end, BEL or ST, which may come in a later read than its start.
     */
    private static boolean mayEndOsc(byte[] buffer, int length) {
        for (int i = 0; i < length; i++) {
            if (buffer[i] == 7) return true;
            if (buffer[i] == '\\' && (i == 0 || buffer[i - 1] == 27)) return true;
        }
        return false;
    }

    /**
     * Put the colors a program set with OSC 4, 10, 11 or 12, such as its background, over the
     * current ones, as after a theme is applied.
     */
    public void applyProgramColors() {
        if (mChangedColors == null) mChangedColors = new int[TextStyle.NUM_INDEXED_COLORS];
        final boolean changed = Ghostty.changedColors(mHandle, mChangedColors);
        if (!changed && !mHadProgramColors) return;
        for (int i = 0; i < TextStyle.NUM_INDEXED_COLORS; i++) {
            if (mChangedColors[i] != 0) mColors.mCurrentColors[i] = mChangedColors[i];
            // One the program set and then reset, such as with OSC 104, is the scheme's again.
            else if (mProgramColors != null && mProgramColors[i] != 0) mColors.reset(i);
        }
        if (mProgramColors == null) mProgramColors = new int[TextStyle.NUM_INDEXED_COLORS];
        System.arraycopy(mChangedColors, 0, mProgramColors, 0, TextStyle.NUM_INDEXED_COLORS);
        mHadProgramColors = changed;
        mSession.onColorsChanged();
    }

    public void resize(int columns, int rows) {
        if (columns == mColumns && rows == mRows) return;
        Ghostty.resize(mHandle, columns, rows);
        mColumns = columns;
        mRows = rows;
        mScreen.resized(columns, rows);
    }

    public void reset() {
        Ghostty.reset(mHandle);
        mColors.reset();
        mTitle = null;
        mScreen.forget();
        mScrollCounter += mRows;
    }

    /** Rows read for {@link TerminalBuffer}: see {@link Ghostty#readRows}. */
    int readRows(int first, int count, char[] text, int[] starts, long[] styles, boolean[] wraps) {
        return Ghostty.readRows(mHandle, first, count, text, starts, styles, wraps);
    }

    /** The screen rows that changed, read for {@link TerminalBuffer}: see {@link Ghostty#readScreen}. */
    int readScreen(boolean all, int[] rows, char[] text, int[] starts, long[] styles, boolean[] wraps) {
        return Ghostty.readScreen(mHandle, all, rows, text, starts, styles, wraps);
    }

    long nativeLong(int key) {
        return Ghostty.getLong(mHandle, key);
    }

    private boolean decMode(int number) {
        return Ghostty.mode(mHandle, number, false);
    }

    public int getCursorRow() {
        return (int) Ghostty.getLong(mHandle, Ghostty.CURSOR_Y);
    }

    public int getCursorCol() {
        return (int) Ghostty.getLong(mHandle, Ghostty.CURSOR_X);
    }

    /**
     * Get the terminal cursor style: the program's choice, or the app's when the program made none. Asked every frame,
     * and only looked up again after output.
     */
    public int getCursorStyle() {
        switch ((int) Ghostty.getLong(mHandle, Ghostty.CURSOR_STYLE)) {
            case 0:
                return TERMINAL_CURSOR_STYLE_BAR;
            case 2:
                return TERMINAL_CURSOR_STYLE_UNDERLINE;
            default:
                Integer chosen = mClient == null ? null : mClient.getTerminalCursorStyle();
                return chosen == null ? DEFAULT_TERMINAL_CURSOR_STYLE : chosen;
        }
    }

    public boolean shouldCursorBeVisible() {
        if (!isCursorEnabled())
            return false;
        else
            return mCursorBlinkingEnabled ? mCursorBlinkState : true;
    }

    public void setCursorBlinkingEnabled(boolean cursorBlinkingEnabled) {
        this.mCursorBlinkingEnabled = cursorBlinkingEnabled;
    }

    public void setCursorBlinkState(boolean cursorBlinkState) {
        this.mCursorBlinkState = cursorBlinkState;
    }

    public boolean isCursorEnabled() {
        return Ghostty.getLong(mHandle, Ghostty.CURSOR_VISIBLE) != 0;
    }

    public boolean isReverseVideo() {
        return decMode(MODE_REVERSE_VIDEO);
    }

    public boolean isAlternateBufferActive() {
        return Ghostty.getLong(mHandle, Ghostty.ALTERNATE_SCREEN) != 0;
    }

    public boolean isCursorKeysApplicationMode() {
        return decMode(MODE_CURSOR_KEYS);
    }

    public boolean isKeypadApplicationMode() {
        return decMode(MODE_KEYPAD_KEYS);
    }

    public boolean isBracketedPasteMode() {
        return decMode(MODE_BRACKETED_PASTE);
    }

    /** If mouse events are being sent as escape codes to the terminal. */
    public boolean isMouseTrackingActive() {
        return Ghostty.getLong(mHandle, Ghostty.MOUSE_TRACKING) != 0;
    }

    /** Whether a program is partway through a synchronized update (mode 2026), so its frame isn't whole yet. */
    public boolean isSynchronizedUpdateActive() {
        return decMode(MODE_SYNCHRONIZED_OUTPUT);
    }

    /** The Kitty keyboard protocol flags the program asked for, 0 when it uses legacy keys. */
    public int getKittyKeyboardFlags() {
        return (int) Ghostty.getLong(mHandle, Ghostty.KITTY_KEYBOARD_FLAGS);
    }

    /**
     * The bytes an Android key sends with {@code mods} ({@link Keys}) held, encoded by libghostty-vt in the form the
     * program asked for: cursor and keypad modes, modifyOtherKeys or the Kitty keyboard protocol. Null for a key
     * that types text, which {@link #encodeCodePoint} encodes, and for one that sends nothing.
     */
    public byte[] encodeKey(int keyCode, int mods) {
        int key = Keys.forKeyCode(keyCode, (mods & Keys.NUM_LOCK) != 0);
        if (key == Keys.UNIDENTIFIED) return null;
        // Num lock only chose the key: the Kitty protocol would add it to the modifiers it reports.
        return Ghostty.encodeKey(mHandle, key, mods & ~Keys.NUM_LOCK, 0, Keys.keypadText(key), 0);
    }

    /**
     * The bytes a character sends when typed with {@code mods} held, ctrl or alt making it a key press the way
     * {@link #encodeKey} encodes one. Null for a control character.
     */
    public byte[] encodeCodePoint(int codePoint, int mods) {
        if (codePoint < 0x20 || codePoint == 0x7f) return null;
        boolean shift = (mods & Keys.SHIFT) != 0;
        int unshifted = Keys.unshifted(codePoint, shift);
        // Shift is used up by the character it changed, so Shift+A types "A" rather than reporting shift.
        int consumed = unshifted != codePoint ? mods & Keys.SHIFT : 0;
        return Ghostty.encodeKey(mHandle, Keys.forCodePoint(codePoint, shift), mods & ~Keys.NUM_LOCK, consumed, codePoint, unshifted);
    }

    /** Physical memory the screen and scrollback take, compressed history at its compressed size. */
    public long getResidentBytes() {
        return Ghostty.getLong(mHandle, Ghostty.RESIDENT_BYTES);
    }

    /** Changes whenever the terminal has history that compressing might shrink. */
    public long getCompressionActivity() {
        return Ghostty.compressionActivity(mHandle);
    }

    /**
     * Compress history: one short step, for when the terminal is idle, or {@code all} of it. Reading
     * compressed rows restores them. Returns true while more remains to compress.
     */
    public boolean compressHistory(boolean all) {
        return Ghostty.compress(mHandle, all);
    }

    /** Whether the DEC private mode numbered {@code number}, as in {@code CSI ? n h}, is set. */
    public boolean isDecModeSet(int number) {
        return decMode(number);
    }

    public int getScrollCounter() {
        return mScrollCounter;
    }

    public void clearScrollCounter() {
        mScrollCounter = 0;
    }

    /**
     * Mouse events in the program's own encoding: SGR (mode 1006) or the original byte encoding.
     * Movement is only sent to a program that tracks it (mode 1002 or 1003).
     */
    public void sendMouseEvent(int mouseButton, int column, int row, boolean pressed) {
        if (column < 1) column = 1;
        if (column > mColumns) column = mColumns;
        if (row < 1) row = 1;
        if (row > mRows) row = mRows;

        if (mouseButton == MOUSE_LEFT_BUTTON_MOVED && !decMode(MODE_BUTTON_EVENT_MOUSE) && !decMode(MODE_ANY_EVENT_MOUSE)) {
            // Do not send tracking.
        } else if (decMode(MODE_SGR_MOUSE)) {
            mSession.write(String.format("\033[<%d;%d;%d" + (pressed ? 'M' : 'm'), mouseButton, column, row));
        } else {
            mouseButton = pressed ? mouseButton : 3; // 3 for release of all buttons.
            // Input reaches Rex as UTF-8 text, so a byte of 0x80 or more can't be sent as itself: the
            // original encoding stops at column and row 95, past which a report would arrive garbled.
            boolean out_of_bounds = column > 127 - 32 || row > 127 - 32;
            if (!out_of_bounds) {
                byte[] data = {'\033', '[', 'M', (byte) (32 + mouseButton), (byte) (32 + column), (byte) (32 + row)};
                mSession.write(data, 0, data.length);
            }
        }
    }

    public void paste(String text) {
        // First: Always remove escape key and C1 control characters [0x80,0x9F]:
        text = text.replaceAll("(\u001B|[\u0080-\u009F])", "");
        // Second: Replace all newlines (\n) or CRLF (\r\n) with carriage returns (\r).
        text = text.replaceAll("\r?\n", "\r");

        // Then: Implement bracketed paste mode if enabled:
        boolean bracketed = isBracketedPasteMode();
        if (bracketed) mSession.write("\033[200~");
        mSession.write(text);
        if (bracketed) mSession.write("\033[201~");
    }

    public String getSelectedText(int x1, int y1, int x2, int y2) {
        return mScreen.getSelectedText(x1, y1, x2, y2);
    }

    /** Get the terminal session's title (null if not set). */
    public String getTitle() {
        return mTitle;
    }

    @Override
    public String toString() {
        return "TerminalEmulator[size=" + mColumns + "x" + mRows + "]";
    }
}
