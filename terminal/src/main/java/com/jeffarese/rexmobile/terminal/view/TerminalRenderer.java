package com.jeffarese.rexmobile.terminal.view;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import com.jeffarese.rexmobile.terminal.emulator.TerminalBuffer;
import com.jeffarese.rexmobile.terminal.emulator.TerminalEmulator;
import com.jeffarese.rexmobile.terminal.emulator.TerminalRow;
import com.jeffarese.rexmobile.terminal.emulator.TextStyle;

import java.util.Arrays;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];
    /**
     * Widths of other BMP code points, measured once each in pages of 256 made on demand. Box drawing, bullets and
     * spinner glyphs fill most screens, and measuring them again on every frame is a native call per cell.
     */
    private final float[][] bmpMeasures = new float[256][];

    /** Draws the underlines a font can't (double, curly, dotted and dashed) and overlines. */
    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mLineThickness;
    private final DashPathEffect mDotted, mDashed;
    private final Path mCurl = new Path();

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        mLineThickness = Math.max(1f, textSize / 14f);
        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(mLineThickness);
        mDotted = new DashPathEffect(new float[]{mLineThickness, mLineThickness}, 0);
        mDashed = new DashPathEffect(new float[]{mLineThickness * 3, mLineThickness * 2}, 0);

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        render(mEmulator, canvas, topRow, 0, selectionY1, selectionY2, selectionX1, selectionX2);
    }

    /**
     * Render with {@code topRow} pushed up by {@code offsetPx} pixels, for scrolling smoothly between rows. A part of
     * one more row then shows at the bottom, so it is drawn too.
     */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow, int offsetPx,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = Math.min(mEmulator.mRows, topRow + mEmulator.mRows + (offsetPx > 0 ? 1 : 0));
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        final boolean translated = offsetPx != 0;
        if (translated) {
            canvas.save();
            canvas.translate(0, -offsetPx);
        }

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            drawRow(mEmulator, canvas, lineObject, heightOffset, cursorX, selx1, selx2, reverseVideo, cursorShape);
        }

        if (translated) canvas.restore();
    }

    private void drawRow(TerminalEmulator mEmulator, Canvas canvas, TerminalRow lineObject, float heightOffset,
                         int cursorX, int selx1, int selx2, boolean reverseVideo, int cursorShape) {
        final int columns = mEmulator.mColumns;
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final char[] line = lineObject.mText;
        final int charsUsedInLine = lineObject.getSpaceUsed();

        long lastRunStyle = 0;
        boolean lastRunInsideCursor = false;
        boolean lastRunInsideSelection = false;
        int lastRunStartColumn = -1;
        int lastRunStartIndex = 0;
        boolean lastRunFontWidthMismatch = false;
        int currentCharIndex = 0;
        float measuredWidthForRun = 0.f;

        for (int column = 0; column < columns; ) {
            // The terminal decides each cell's width, and its text is a whole grapheme cluster.
            final int cellWidth = Math.max(1, lineObject.getWidth(column));
            currentCharIndex = lineObject.findStartOfColumn(column);
            final int charsForCell = lineObject.findStartOfColumn(Math.min(columns, column + cellWidth)) - currentCharIndex;
            final char charAtIndex = charsForCell > 0 ? line[currentCharIndex] : ' ';
            final int codePoint = charsForCell > 1 && Character.isHighSurrogate(charAtIndex)
                ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
            final boolean insideCursor = (cursorX == column || (cellWidth == 2 && cursorX == column + 1));
            final boolean insideSelection = column >= selx1 && column <= selx2;
            final long style = lineObject.getStyle(column);

            // Check if the measured text width for this cell is not the same as the terminal gives it.
            // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
            // smileys which android font renders as wide.
            // If this is detected, we draw this cell scaled to match the width the terminal expects.
            final float measuredCodePointWidth = (charsForCell == 1 && codePoint < asciiMeasures.length) ? asciiMeasures[codePoint] :
                measureCell(line, currentCharIndex, charsForCell);
            final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - cellWidth) > 0.01;

            if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                if (column == 0) {
                    // Skip first column as there is nothing to draw, just record the current style.
                } else {
                    final int columnWidthSinceLastRun = column - lastRunStartColumn;
                    final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                    int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                    boolean invertCursorTextColor = false;
                    if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                        invertCursorTextColor = true;
                    }
                    drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                        lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                        cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                }
                measuredWidthForRun = 0.f;
                lastRunStyle = style;
                lastRunInsideCursor = insideCursor;
                lastRunInsideSelection = insideSelection;
                lastRunStartColumn = column;
                lastRunStartIndex = currentCharIndex;
                lastRunFontWidthMismatch = fontWidthMismatch;
            }
            measuredWidthForRun += measuredCodePointWidth;
            column += cellWidth;
            currentCharIndex += charsForCell;
        }
        currentCharIndex = charsUsedInLine;

        final int columnWidthSinceLastRun = columns - lastRunStartColumn;
        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
        boolean invertCursorTextColor = false;
        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
            invertCursorTextColor = true;
        }
        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
            measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
    }

    /** The width of a cell's text: one character, or a whole grapheme cluster such as an emoji sequence. */
    private float measureCell(char[] line, int index, int chars) {
        if (chars <= 0) return 0f;
        if (chars == 1) return measureCodePoint(line[index], line, index, 1);
        if (chars == 2 && Character.isHighSurrogate(line[index]))
            return measureCodePoint(Character.toCodePoint(line[index], line[index + 1]), line, index, 2);
        return mTextPaint.measureText(line, index, chars);
    }

    private float measureCodePoint(int codePoint, char[] line, int index, int chars) {
        if (codePoint > 0xFFFF) return mTextPaint.measureText(line, index, chars);
        float[] page = bmpMeasures[codePoint >> 8];
        if (page == null) {
            page = new float[256];
            Arrays.fill(page, -1f);
            bmpMeasures[codePoint >> 8] = page;
        }
        float width = page[codePoint & 0xFF];
        if (width < 0) {
            width = mTextPaint.measureText(line, index, chars);
            page[codePoint & 0xFF] = width;
        }
        return width;
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final int underline = TextStyle.decodeUnderlineStyle(effect);
        final boolean overline = (effect & TextStyle.CHARACTER_ATTRIBUTE_OVERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4.;
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline == TextStyle.UNDERLINE_SINGLE);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT.
            canvas.drawText(text, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, mTextPaint);
            if (underline > TextStyle.UNDERLINE_SINGLE || overline) drawLines(canvas, left, right, y, underline, overline, foreColor);
        }

        if (savedMatrix) canvas.restore();
    }

    /** The underline styles a font doesn't draw, below the baseline, and an overline at the top of the row. */
    private void drawLines(Canvas canvas, float left, float right, float y, int underline, boolean overline, int color) {
        mLinePaint.setColor(color);
        mLinePaint.setPathEffect(null);
        final float t = mLineThickness;
        if (overline) canvas.drawLine(left, y - mFontLineSpacing + t / 2, right, y - mFontLineSpacing + t / 2, mLinePaint);
        final float under = y - mFontLineSpacingAndAscent + t * 1.5f;
        switch (underline) {
            case TextStyle.UNDERLINE_DOUBLE:
                canvas.drawLine(left, under, right, under, mLinePaint);
                canvas.drawLine(left, under + t * 2, right, under + t * 2, mLinePaint);
                break;
            case TextStyle.UNDERLINE_CURLY: {
                // A wave a cell long, crest to crest.
                final float half = mFontWidth / 2;
                mCurl.rewind();
                mCurl.moveTo(left, under);
                for (float x = left; x < right; x += half) {
                    mCurl.rQuadTo(half / 2, ((x - left) / half % 2 < 1 ? -t : t) * 1.5f, half, 0);
                }
                canvas.drawPath(mCurl, mLinePaint);
                break;
            }
            case TextStyle.UNDERLINE_DOTTED:
            case TextStyle.UNDERLINE_DASHED:
                mLinePaint.setPathEffect(underline == TextStyle.UNDERLINE_DOTTED ? mDotted : mDashed);
                canvas.drawLine(left, under, right, under, mLinePaint);
                break;
            default:
                break;
        }
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
