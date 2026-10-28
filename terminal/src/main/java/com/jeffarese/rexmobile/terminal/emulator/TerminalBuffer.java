package com.jeffarese.rexmobile.terminal.emulator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The screen and scrollback of a {@link TerminalEmulator}, read from libghostty-vt a few rows at a time.
 * <p>
 * Rows are numbered as Termux numbers them: 0 to rows - 1 is the screen and negative rows are history,
 * -1 being the newest. {@link #externalToInternalRow(int)} leaves a row number as it is, for the code
 * written against Termux's circular buffer.
 * <p>
 * Screen rows are read again after each change. History rows only change when the terminal is
 * resized, reset or cleared, so they are kept, by their position counted from the first row the
 * terminal ever had, until one of those happens.
 */
public final class TerminalBuffer {

    /** Rows read in one go: a screenful or so, for the renderer and selection read rows near each other. */
    private static final int READ_AHEAD = 64;
    /** History rows kept read, such as for scrolling back and forth over a long conversation. */
    private static final int HISTORY_KEPT = 4096;

    private final TerminalEmulator mEmulator;
    /** The number of rows and columns visible on the screen. */
    int mScreenRows, mColumns;
    private final int mMaxTranscriptRows;

    private TerminalRow[] mScreen;
    private boolean[] mScreenRead;
    private final LinkedHashMap<Long, TerminalRow> mHistory = new LinkedHashMap<Long, TerminalRow>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, TerminalRow> eldest) {
            return size() > HISTORY_KEPT;
        }
    };
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
        mScreenRead = new boolean[screenRows];
        mHistory.clear();
        mTranscriptRows = -1;
    }

    /**
     * Output changed the screen and moved {@code scrolled} rows into history, or -1 if that can't be
     * told: history rows read so far keep their place only when output just added rows.
     */
    void changed(int scrolled) {
        java.util.Arrays.fill(mScreenRead, false);
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
        java.util.Arrays.fill(mScreenRead, false);
        mHistory.clear();
        mTranscriptRows = -1;
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
            if (!mScreenRead[row]) read(row);
            // Not to be read, as from a released terminal: blank.
            if (mScreen[row] == null) mScreen[row] = new TerminalRow(mColumns, TextStyle.NORMAL);
            return mScreen[row];
        }
        TerminalRow kept = mHistory.get(mScrolledTotal + row);
        if (kept != null) return kept;
        read(row);
        kept = mHistory.get(mScrolledTotal + row);
        // A row outside the terminal reads as blank and isn't kept.
        return kept != null ? kept : new TerminalRow(mColumns, TextStyle.NORMAL);
    }

    /** Read the rows around {@code row} that aren't read yet: the rest of the screen for a screen row, a stretch of history for a history row. */
    private void read(int row) {
        final int history = getActiveTranscriptRows();
        int first, last;
        if (row >= 0) {
            first = row;
            last = Math.min(mScreenRows - 1, row + READ_AHEAD - 1);
            while (last > first && mScreenRead[last]) last--;
        } else {
            first = Math.max(-history, row - READ_AHEAD / 2);
            last = Math.min(-1, row + READ_AHEAD / 2);
            if (first > last) return;
        }
        final int count = last - first + 1;
        final int[] starts = new int[count * (mColumns + 1)];
        final long[] styles = new long[count * mColumns];
        final boolean[] wraps = new boolean[count];
        final char[] text = mEmulator.readRows(first, count, starts, styles, wraps);
        if (text == null) return;
        for (int i = 0; i < count; i++) {
            final int r = first + i;
            if (r >= 0) {
                if (mScreen[r] == null) mScreen[r] = new TerminalRow(mColumns, TextStyle.NORMAL);
                mScreen[r].fill(text, starts, styles, wraps, i);
                mScreenRead[r] = true;
            } else {
                final long key = mScrolledTotal + r;
                TerminalRow line = mHistory.get(key);
                if (line == null) line = new TerminalRow(mColumns, TextStyle.NORMAL);
                line.fill(text, starts, styles, wraps, i);
                mHistory.put(key, line);
            }
        }
    }
}
