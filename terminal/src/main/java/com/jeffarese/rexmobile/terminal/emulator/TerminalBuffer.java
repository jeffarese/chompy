package com.jeffarese.rexmobile.terminal.emulator;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The screen and scrollback of a {@link TerminalEmulator}, read from libghostty-vt a few rows at a time.
 * <p>
 * Rows are numbered as Termux numbers them: 0 to rows - 1 is the screen and negative rows are history,
 * -1 being the newest. {@link #externalToInternalRow(int)} leaves a row number as it is, for the code
 * written against Termux's circular buffer.
 * <p>
 * After a change, the screen rows libghostty-vt marks as changed are read again, the next time any
 * is wanted. History rows only change when the terminal is resized, reset or cleared, so the last
 * few screens of them read are kept, by their position counted from the first row the terminal
 * ever had, until one of those happens.
 */
public final class TerminalBuffer {

    /** History rows read in one go: a screenful or so, for the renderer and selection read rows near each other. */
    private static final int READ_AHEAD = 64;
    /**
     * History rows kept read, for scrolling back and forth over a few screens. A row of 150 columns
     * takes over 2 KB of the Java heap, and the terminal keeps every row compressed besides.
     */
    static final int HISTORY_KEPT = 512;

    private final TerminalEmulator mEmulator;
    /** The number of rows and columns visible on the screen. */
    int mScreenRows, mColumns;
    private final int mMaxTranscriptRows;

    private TerminalRow[] mScreen;
    /** Output changed the screen since it was last read. */
    private boolean mScreenChanged = true;
    /** Some screen row has never been read, so the next read reads them all rather than the changed ones. */
    private boolean mScreenPartial = true;
    private final LinkedHashMap<Long, TerminalRow> mHistory = new LinkedHashMap<Long, TerminalRow>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, TerminalRow> eldest) {
            if (size() <= HISTORY_KEPT) return false;
            mSpareRow = eldest.getValue();
            return true;
        }
    };
    /** The last history row let go, to be filled with the next one read rather than make another. */
    private TerminalRow mSpareRow;

    // What reads fill, kept from one to the next and only ever grown, so reading a frame makes no garbage.
    private char[] mText;
    private int[] mStarts;
    private long[] mStyles;
    private boolean[] mWraps;
    private int[] mRowsRead;
    /** Rows that have moved into history since the terminal began, which makes a history row's key. */
    private long mScrolledTotal;
    private int mTranscriptRows = -1;

    TerminalBuffer(TerminalEmulator emulator, int columns, int screenRows, int maxTranscriptRows) {
        mEmulator = emulator;
        mMaxTranscriptRows = maxTranscriptRows;
        resized(columns, screenRows);
    }

    /** The terminal's size changed, which reflows its history too. */
    void resized(int columns, int screenRows) {
        mColumns = columns;
        mScreenRows = screenRows;
        mScreen = new TerminalRow[screenRows];
        mScreenChanged = true;
        mScreenPartial = true;
        mHistory.clear();
        mSpareRow = null;
        mTranscriptRows = -1;
    }

    /**
     * Output changed the screen and moved {@code scrolled} rows into history, or -1 if that can't be
     * told: history rows read so far keep their place only when output just added rows.
     */
    void changed(int scrolled) {
        mScreenChanged = true;
        int before = mTranscriptRows;
        mTranscriptRows = -1;
        if (scrolled < 0) {
            mHistory.clear();
            return;
        }
        mScrolledTotal += scrolled;
        // Cleared history (CSI 3 J, a reset) drops everything; pruning the oldest page keeps the rest in place.
        if (before > 0 && getActiveTranscriptRows() == 0) mHistory.clear();
    }

    /** Forget every row read, as after a reset. */
    void forget() {
        mScreenChanged = true;
        mScreenPartial = true;
        mHistory.clear();
        mTranscriptRows = -1;
    }

    /**
     * Let go of every row read and what reads fill, for a terminal nothing shows or when memory runs
     * short. Rows are read again when wanted.
     */
    public void dropRows() {
        Arrays.fill(mScreen, null);
        mScreenChanged = true;
        mScreenPartial = true;
        mHistory.clear();
        mSpareRow = null;
        mText = null;
        mStarts = null;
        mStyles = null;
        mWraps = null;
        mRowsRead = null;
    }

    /** History rows kept read, for tests. */
    int historyRowsKept() {
        return mHistory.size();
    }

    public String getTranscriptText() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows).trim();
    }

    public String getTranscriptTextWithoutJoinedLines() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, false).trim();
    }

    public String getTranscriptTextWithFullLinesJoined() {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, true, true).trim();
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2) {
        return getSelectedText(selX1, selY1, selX2, selY2, true);
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2, boolean joinBackLines) {
        return getSelectedText(selX1, selY1, selX2, selY2, joinBackLines, false);
    }

    public String getSelectedText(int selX1, int selY1, int selX2, int selY2, boolean joinBackLines, boolean joinFullLines) {
        final StringBuilder builder = new StringBuilder();
        final int columns = mColumns;

        if (selY1 < -getActiveTranscriptRows()) selY1 = -getActiveTranscriptRows();
        if (selY2 >= mScreenRows) selY2 = mScreenRows - 1;

        for (int row = selY1; row <= selY2; row++) {
            int x1 = (row == selY1) ? selX1 : 0;
            int x2;
            if (row == selY2) {
                x2 = selX2 + 1;
                if (x2 > columns) x2 = columns;
            } else {
                x2 = columns;
            }
            TerminalRow lineObject = allocateFullLineIfNecessary(row);
            int x1Index = lineObject.findStartOfColumn(x1);
            int x2Index = (x2 < mColumns) ? lineObject.findStartOfColumn(x2) : lineObject.getSpaceUsed();
            if (x2Index == x1Index) {
                // Selected the start of a wide character.
                x2Index = lineObject.findStartOfColumn(x2 + 1);
            }
            char[] line = lineObject.mText;
            int lastPrintingCharIndex = -1;
            int i;
            boolean rowLineWrap = lineObject.mLineWrap;
            if (rowLineWrap && x2 == columns) {
                // If the line was wrapped, we shouldn't lose trailing space:
                lastPrintingCharIndex = x2Index - 1;
            } else {
                for (i = x1Index; i < x2Index; ++i) {
                    char c = line[i];
                    if (c != ' ') lastPrintingCharIndex = i;
                }
            }

            int len = lastPrintingCharIndex - x1Index + 1;
            if (lastPrintingCharIndex != -1 && len > 0)
                builder.append(line, x1Index, len);

            boolean lineFillsWidth = lastPrintingCharIndex == x2Index - 1;
            if ((!joinBackLines || !rowLineWrap) && (!joinFullLines || !lineFillsWidth)
                && row < selY2 && row < mScreenRows - 1) builder.append('\n');
        }
        return builder.toString();
    }

    public String getWordAtLocation(int x, int y) {
        // Set y1 and y2 to the lines where the wrapped line starts and ends.
        // I.e. if a line that is wrapped to 3 lines starts at line 4, and this
        // is called with y=5, then y1 would be set to 4 and y2 would be set to 6.
        int y1 = y;
        int y2 = y;
        while (y1 > 0 && !getSelectedText(0, y1 - 1, mColumns, y, true, true).contains("\n")) {
            y1--;
        }
        while (y2 < mScreenRows && !getSelectedText(0, y, mColumns, y2 + 1, true, true).contains("\n")) {
            y2++;
        }

        // Get the text for the whole wrapped line
        String text = getSelectedText(0, y1, mColumns, y2, true, true);
        // The index of x in text
        int textOffset = (y - y1) * mColumns + x;

        if (textOffset >= text.length()) {
          // The click was to the right of the last word on the line, so
          // there's no word to return
          return "";
        }

        // Set x1 and x2 to the indices of the last space before x and the
        // first space after x in text respectively
        int x1 = text.lastIndexOf(' ', textOffset);
        int x2 = text.indexOf(' ', textOffset);
        if (x2 == -1) {
            x2 = text.length();
        }

        if (x1 == x2) {
          // The click was on a space, so there's no word to return
          return "";
        }
        return text.substring(x1 + 1, x2);
    }

    public int getActiveTranscriptRows() {
        if (mTranscriptRows < 0) mTranscriptRows = (int) Math.min(Integer.MAX_VALUE, mEmulator.nativeLong(Ghostty.SCROLLBACK_ROWS));
        return mTranscriptRows;
    }

    public int getActiveRows() {
        return getActiveTranscriptRows() + mScreenRows;
    }

    /** About the most history rows the terminal keeps before dropping the oldest. */
    public int getMaxTranscriptRows() {
        return Math.max(mMaxTranscriptRows, getActiveTranscriptRows());
    }

    /** Rows keep Termux's numbers here: the row number is its own index. */
    public int externalToInternalRow(int externalRow) {
        if (externalRow < -getActiveTranscriptRows() || externalRow > mScreenRows)
            throw new IllegalArgumentException("extRow=" + externalRow + ", mScreenRows=" + mScreenRows + ", mActiveTranscriptRows=" + getActiveTranscriptRows());
        return externalRow;
    }

    public boolean getLineWrap(int row) {
        return allocateFullLineIfNecessary(row).mLineWrap;
    }

    public long getStyleAt(int externalRow, int column) {
        return allocateFullLineIfNecessary(externalRow).getStyle(column);
    }

    /** The row numbered {@code row}, read from the terminal if it hasn't been since it last changed. */
    public TerminalRow allocateFullLineIfNecessary(int row) {
        if (row >= 0 && row < mScreenRows) {
            if (mScreenChanged) readScreen();
            // Not to be read, as from a released terminal: blank.
            if (mScreen[row] == null) mScreen[row] = new TerminalRow(mColumns, TextStyle.NORMAL);
            return mScreen[row];
        }
        TerminalRow kept = mHistory.get(mScrolledTotal + row);
        if (kept != null) return kept;
        readHistory(row);
        kept = mHistory.get(mScrolledTotal + row);
        // A row outside the terminal reads as blank and isn't kept.
        return kept != null ? kept : new TerminalRow(mColumns, TextStyle.NORMAL);
    }

    /** Read the screen rows that changed since they were last read, or all of them if some never were. */
    private void readScreen() {
        reserve(mScreenRows);
        int read;
        while ((read = mEmulator.readScreen(mScreenPartial, mRowsRead, mText, mStarts, mStyles, mWraps)) < 0) {
            mText = new char[-read + mColumns];
        }
        mScreenChanged = false;
        mScreenPartial = false;
        for (int i = 0; i < read; i++) {
            final int r = mRowsRead[i];
            if (r >= mScreenRows) continue;
            if (mScreen[r] == null) mScreen[r] = new TerminalRow(mColumns, TextStyle.NORMAL);
            mScreen[r].fill(mText, mStarts, mStyles, mWraps, i);
        }
    }

    /** Read a stretch of history around {@code row}. */
    private void readHistory(int row) {
        final int history = getActiveTranscriptRows();
        final int first = Math.max(-history, row - READ_AHEAD / 2);
        final int last = Math.min(-1, row + READ_AHEAD / 2);
        if (first > last) return;
        final int count = last - first + 1;
        reserve(count);
        int length;
        while ((length = mEmulator.readRows(first, count, mText, mStarts, mStyles, mWraps)) < 0) {
            mText = new char[-length + mColumns];
        }
        if (length == 0) return;
        for (int i = 0; i < count; i++) {
            final long key = mScrolledTotal + first + i;
            TerminalRow line = mHistory.get(key);
            if (line == null) {
                line = mSpareRow != null ? mSpareRow : new TerminalRow(mColumns, TextStyle.NORMAL);
                mSpareRow = null;
            }
            line.fill(mText, mStarts, mStyles, mWraps, i);
            mHistory.put(key, line);
        }
    }

    /** Room in what reads fill for {@code count} rows. */
    private void reserve(int count) {
        if (mWraps == null || mWraps.length < count || mStarts.length < count * (mColumns + 1)) {
            final int rows = Math.max(count, Math.max(mScreenRows, READ_AHEAD + 1));
            mStarts = new int[rows * (mColumns + 1)];
            mStyles = new long[rows * mColumns];
            mWraps = new boolean[rows];
            mRowsRead = new int[rows];
        }
        if (mText == null) mText = new char[count * mColumns];
    }
}
