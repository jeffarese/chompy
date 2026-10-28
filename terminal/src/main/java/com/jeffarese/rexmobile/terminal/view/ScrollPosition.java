package com.jeffarese.rexmobile.terminal.view;

/**
 * Where a {@link TerminalView} is scrolled to, kept in pixels so scrolling moves smoothly instead of a row at a time.
 * <p>
 * The view draws from {@link #getTopRow()} (0 is the top of the live screen, negative rows are history) with that row
 * pushed up by {@link #getOffsetPx()}, which is always less than a row and exactly 0 at the live bottom. Positions are
 * measured from the bottom: 0 there, negative up in history. Kept apart from the view so the arithmetic can be tested
 * without measuring fonts.
 */
public final class ScrollPosition {

    private int mTopRow;
    private float mOffset;
    /** History rows at the last {@link #followOutput}, to tell new lines from a cleared or reflowed history. */
    private int mLastHistoryRows;

    /** The first row drawn, from minus the history rows up to 0. */
    public int getTopRow() {
        return mTopRow;
    }

    /** How far {@link #getTopRow()} is pushed up, in pixels: from 0 up to a row height. */
    public float getOffsetPx() {
        return mOffset;
    }

    /** {@link #getOffsetPx()} in whole pixels, as drawn, so rows and backgrounds stay on the pixel grid. */
    public int getOffsetPixels() {
        return (int) mOffset;
    }

    public boolean isAtBottom() {
        return mTopRow == 0;
    }

    /** Rows scrolled up into history, counting a part row as one: 0 only at the live bottom. */
    public int getRowsAboveBottom() {
        return -mTopRow;
    }

    /** Distance from the bottom in pixels: 0 there, negative in history. */
    public float getPosition(int lineHeight) {
        return mTopRow * (float) lineHeight + mOffset;
    }

    /** Move to {@code position} pixels from the bottom, within the history there is. */
    public void setPosition(float position, int lineHeight, int historyRows) {
        if (lineHeight <= 0) return;
        float top = -(float) Math.max(0, historyRows) * lineHeight;
        if (!(position < 0)) position = 0; // Also catches NaN.
        if (position < top) position = top;
        int row = (int) Math.floor(position / lineHeight);
        float offset = position - row * (float) lineHeight;
        if (offset >= lineHeight) {
            row++;
            offset = 0;
        } else if (offset < 0) {
            offset = 0;
        }
        if (row >= 0) {
            row = 0;
            offset = 0;
        }
        mTopRow = row;
        mOffset = offset;
    }

    /** Scroll by {@code dy} pixels, positive towards the bottom. Returns how far it actually moved. */
    public float scrollBy(float dy, int lineHeight, int historyRows) {
        float before = getPosition(lineHeight);
        setPosition(before + dy, lineHeight, historyRows);
        return getPosition(lineHeight) - before;
    }

    /** Show {@code row} at the top, lined up on whole rows if that moves the view. */
    public void setTopRow(int row, int historyRows) {
        row = Math.max(-Math.max(0, historyRows), Math.min(0, row));
        if (row != mTopRow) {
            mTopRow = row;
            mOffset = 0;
        }
    }

    public void toBottom() {
        mTopRow = 0;
        mOffset = 0;
        mLastHistoryRows = 0;
    }

    /** Line up on whole rows, for when the row height changes and an old pixel offset means nothing. */
    public void alignToRow() {
        mOffset = 0;
    }

    /** Stay within the history there is. */
    public void clamp(int historyRows) {
        if (mTopRow < -historyRows) {
            mTopRow = -Math.max(0, historyRows);
            mOffset = 0;
        }
        if (mTopRow >= 0) {
            mTopRow = 0;
            mOffset = 0;
        }
    }

    /**
     * Follow output that moved {@code scrolledRows} lines up into history. At the bottom, stay at the bottom. Scrolled
     * up, keep the same lines in view by moving up with them, unless the history was cleared or reflowed meanwhile:
     * then those lines are gone and the distance from the bottom is kept instead.
     *
     * @param pinned keep the same lines in view even at the bottom, as text selection needs.
     */
    public void followOutput(int scrolledRows, int historyRows, int maxHistoryRows, boolean pinned) {
        boolean historyKept = historyRows >= Math.min(mLastHistoryRows + scrolledRows, maxHistoryRows);
        if ((pinned || mTopRow != 0) && historyKept) mTopRow -= scrolledRows;
        mLastHistoryRows = historyRows;
        clamp(historyRows);
    }
}
