package com.jeffarese.rexmobile.terminal.emulator;

/**
 * A row of a terminal as read from libghostty-vt: what each column shows and its style.
 * <p>
 * The text of every column is in {@link #mText}, one column after another: a space for an empty
 * cell, the whole grapheme cluster for one that holds text, and nothing for the right half of a
 * wide character. {@link #findStartOfColumn(int)} gives where a column's text begins. Widths come
 * from libghostty-vt, so the screen lays text out exactly as the server's terminal does.
 */
public final class TerminalRow {

    private final int mColumns;
    /** The text of each column in turn; see the class comment. */
    public char[] mText;
    private int mSpaceUsed;
    boolean mLineWrap;
    final long[] mStyle;
    /** Where each column's text starts in {@link #mText}, and at {@code mColumns} where the row's ends. */
    private final int[] mStart;

    public TerminalRow(int columns, long style) {
        mColumns = columns;
        mText = new char[columns];
        mStyle = new long[columns];
        mStart = new int[columns + 1];
        clear(style);
    }

    /** Fill this row from {@link Ghostty#readRows}'s answer for the row starting at {@code row} of it. */
    void fill(char[] text, int[] starts, long[] styles, boolean[] wraps, int row) {
        final int startsAt = row * (mColumns + 1);
        final int from = starts[startsAt];
        final int to = starts[startsAt + mColumns];
        mSpaceUsed = to - from;
        if (mText.length < mSpaceUsed) mText = new char[mSpaceUsed];
        System.arraycopy(text, from, mText, 0, mSpaceUsed);
        for (int column = 0; column <= mColumns; column++) mStart[column] = starts[startsAt + column] - from;
        System.arraycopy(styles, row * mColumns, mStyle, 0, mColumns);
        mLineWrap = wraps[row];
    }

    /** Where the text of {@code column} starts in {@link #mText}, or where the row's ends for the column past the last. */
    public int findStartOfColumn(int column) {
        if (column >= mColumns) return mSpaceUsed;
        return mStart[column];
    }

    /** The column whose text holds the character at {@code index} of {@link #mText}, or the number of columns past its end. */
    public int findColumnOfIndex(int index) {
        for (int column = 0; column < mColumns; column++) {
            if (mStart[column + 1] > index) return column;
        }
        return mColumns;
    }

    /** How many columns the text starting at {@code column} takes: 1, 2 for a wide character, or 0 for the right half of one. */
    public int getWidth(int column) {
        if (mStart[column] == mStart[column + 1]) return 0;
        return (column + 1 < mColumns && mStart[column + 1] == mStart[column + 2]) ? 2 : 1;
    }

    /** Clear this row to blanks of the given style. */
    public void clear(long style) {
        if (mText.length < mColumns) mText = new char[mColumns];
        for (int column = 0; column < mColumns; column++) {
            mText[column] = ' ';
            mStart[column] = column;
            mStyle[column] = style;
        }
        mStart[mColumns] = mColumns;
        mSpaceUsed = mColumns;
        mLineWrap = false;
    }

    public int getSpaceUsed() {
        return mSpaceUsed;
    }

    public boolean isBlank() {
        for (int charIndex = 0; charIndex < mSpaceUsed; charIndex++)
            if (mText[charIndex] != ' ') return false;
        return true;
    }

    public final long getStyle(int column) {
        return mStyle[column];
    }

    public boolean getLineWrap() {
        return mLineWrap;
    }
}
