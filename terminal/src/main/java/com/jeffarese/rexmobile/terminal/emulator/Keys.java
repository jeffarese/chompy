package com.jeffarese.rexmobile.terminal.emulator;

import android.view.KeyEvent;

/**
 * Android's keys as libghostty-vt's key encoder takes them, for {@link TerminalEmulator#encodeKey} and
 * {@link TerminalEmulator#encodeCodePoint}: modifiers as {@code GhosttyMods} bits and keys as {@code GhosttyKey}
 * codes, both from {@code ghostty/vt/key/event.h} at the commit in {@code native/GHOSTTY_COMMIT}. KeysTest checks
 * every key against the encoder, so a Ghostty that numbers them differently fails it.
 */
public final class Keys {

    public static final int SHIFT = 1;
    public static final int CTRL = 1 << 1;
    public static final int ALT = 1 << 2;
    public static final int NUM_LOCK = 1 << 5;

    private Keys() {}

    public static int mods(boolean ctrl, boolean alt, boolean shift) {
        return (ctrl ? CTRL : 0) | (alt ? ALT : 0) | (shift ? SHIFT : 0);
    }

    static final int UNIDENTIFIED = 0;
    static final int BACKQUOTE = 1, BACKSLASH = 2, BRACKET_LEFT = 3, BRACKET_RIGHT = 4, COMMA = 5;
    /** DIGIT_0 to DIGIT_9 follow one another, as do A to Z, NUMPAD_0 to NUMPAD_9 and F1 to F12. */
    static final int DIGIT_0 = 6;
    static final int EQUAL = 16;
    static final int A = 20;
    static final int MINUS = 46, PERIOD = 47, QUOTE = 48, SEMICOLON = 49, SLASH = 50;
    static final int BACKSPACE = 53, ENTER = 58, SPACE = 63, TAB = 64;
    static final int DELETE = 68, END = 69, HOME = 71, INSERT = 72, PAGE_DOWN = 73, PAGE_UP = 74;
    static final int ARROW_DOWN = 75, ARROW_LEFT = 76, ARROW_RIGHT = 77, ARROW_UP = 78;
    static final int NUM_LOCK_KEY = 79;
    static final int NUMPAD_0 = 80;
    static final int NUMPAD_ADD = 90, NUMPAD_COMMA = 94, NUMPAD_DECIMAL = 95, NUMPAD_DIVIDE = 96, NUMPAD_ENTER = 97,
        NUMPAD_EQUAL = 98, NUMPAD_MULTIPLY = 104, NUMPAD_PAREN_LEFT = 105, NUMPAD_PAREN_RIGHT = 106,
        NUMPAD_SUBTRACT = 107;
    static final int NUMPAD_UP = 109, NUMPAD_DOWN = 110, NUMPAD_RIGHT = 111, NUMPAD_LEFT = 112, NUMPAD_BEGIN = 113,
        NUMPAD_HOME = 114, NUMPAD_END = 115, NUMPAD_INSERT = 116, NUMPAD_DELETE = 117, NUMPAD_PAGE_UP = 118,
        NUMPAD_PAGE_DOWN = 119;
    static final int ESCAPE = 120;
    static final int F1 = 121;
    static final int PRINT_SCREEN = 148, SCROLL_LOCK = 149, PAUSE = 150;

    /**
     * The key for an Android key code that sends a sequence rather than text, or {@link #UNIDENTIFIED} for one that
     * types text (a letter, a digit, space or a symbol), which goes through {@link TerminalEmulator#encodeCodePoint}
     * with the character the keyboard layout gives it. Without num lock the keypad's digits move the cursor.
     */
    static int forKeyCode(int keyCode, boolean numLock) {
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) return F1 + keyCode - KeyEvent.KEYCODE_F1;
        if (numLock && keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9)
            return NUMPAD_0 + keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return ARROW_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return ARROW_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return ARROW_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return ARROW_RIGHT;
            // KEYCODE_HOME is the system's and never reaches an app. Some keyboards send MOVE_HOME for Fn+Left.
            case KeyEvent.KEYCODE_MOVE_HOME: return HOME;
            case KeyEvent.KEYCODE_MOVE_END: return END;
            case KeyEvent.KEYCODE_PAGE_UP: return PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return PAGE_DOWN;
            case KeyEvent.KEYCODE_INSERT: return INSERT;
            case KeyEvent.KEYCODE_FORWARD_DEL: return DELETE;
            case KeyEvent.KEYCODE_DEL: return BACKSPACE;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER: return ENTER;
            case KeyEvent.KEYCODE_TAB: return TAB;
            // Back only gets here when the view's client maps it to Esc.
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BACK: return ESCAPE;
            case KeyEvent.KEYCODE_SYSRQ: return PRINT_SCREEN;
            case KeyEvent.KEYCODE_SCROLL_LOCK: return SCROLL_LOCK;
            case KeyEvent.KEYCODE_BREAK: return PAUSE;
            case KeyEvent.KEYCODE_NUM_LOCK: return NUM_LOCK_KEY;
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return NUMPAD_ENTER;
            case KeyEvent.KEYCODE_NUMPAD_ADD: return NUMPAD_ADD;
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return NUMPAD_SUBTRACT;
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return NUMPAD_MULTIPLY;
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return NUMPAD_DIVIDE;
            case KeyEvent.KEYCODE_NUMPAD_EQUALS: return NUMPAD_EQUAL;
            case KeyEvent.KEYCODE_NUMPAD_COMMA: return NUMPAD_COMMA;
            case KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN: return NUMPAD_PAREN_LEFT;
            case KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN: return NUMPAD_PAREN_RIGHT;
            case KeyEvent.KEYCODE_NUMPAD_DOT: return numLock ? NUMPAD_DECIMAL : NUMPAD_DELETE;
            case KeyEvent.KEYCODE_NUMPAD_0: return NUMPAD_INSERT;
            case KeyEvent.KEYCODE_NUMPAD_1: return NUMPAD_END;
            case KeyEvent.KEYCODE_NUMPAD_2: return NUMPAD_DOWN;
            case KeyEvent.KEYCODE_NUMPAD_3: return NUMPAD_PAGE_DOWN;
            case KeyEvent.KEYCODE_NUMPAD_4: return NUMPAD_LEFT;
            case KeyEvent.KEYCODE_NUMPAD_5: return NUMPAD_BEGIN;
            case KeyEvent.KEYCODE_NUMPAD_6: return NUMPAD_RIGHT;
            case KeyEvent.KEYCODE_NUMPAD_7: return NUMPAD_HOME;
            case KeyEvent.KEYCODE_NUMPAD_8: return NUMPAD_UP;
            case KeyEvent.KEYCODE_NUMPAD_9: return NUMPAD_PAGE_UP;
            default: return UNIDENTIFIED;
        }
    }

    /** The character a keypad key types, which the Kitty protocol sends as text, or 0 for none. */
    static int keypadText(int key) {
        if (key >= NUMPAD_0 && key <= NUMPAD_0 + 9) return '0' + key - NUMPAD_0;
        switch (key) {
            case NUMPAD_ADD: return '+';
            case NUMPAD_SUBTRACT: return '-';
            case NUMPAD_MULTIPLY: return '*';
            case NUMPAD_DIVIDE: return '/';
            case NUMPAD_EQUAL: return '=';
            case NUMPAD_COMMA: return ',';
            case NUMPAD_DECIMAL: return '.';
            case NUMPAD_PAREN_LEFT: return '(';
            case NUMPAD_PAREN_RIGHT: return ')';
            default: return 0;
        }
    }

    /** The US keyboard's shifted symbols, each above the character at the same place in {@link #UNSHIFTED}. */
    private static final String SHIFTED = "~!@#$%^&*()_+{}|:\"<>?";
    private static final String UNSHIFTED = "`1234567890-=[]\\;',./";

    /**
     * The character on the same key without shift: a capital's small letter, and with {@code shift} held, the
     * character under a symbol on a US keyboard. Without shift a symbol is its own key, as on a phone's keyboard.
     */
    static int unshifted(int codePoint, boolean shift) {
        if (codePoint >= 'A' && codePoint <= 'Z') return codePoint - 'A' + 'a';
        int i = shift && codePoint < 128 ? SHIFTED.indexOf(codePoint) : -1;
        return i < 0 ? codePoint : UNSHIFTED.charAt(i);
    }

    /** The key a US keyboard types a character with, {@link #UNIDENTIFIED} for one it has no key for. */
    static int forCodePoint(int codePoint, boolean shift) {
        int c = unshifted(codePoint, shift);
        if (c >= 'a' && c <= 'z') return A + c - 'a';
        if (c >= '0' && c <= '9') return DIGIT_0 + c - '0';
        switch (c) {
            case ' ': return SPACE;
            case '`': return BACKQUOTE;
            case '-': return MINUS;
            case '=': return EQUAL;
            case '[': return BRACKET_LEFT;
            case ']': return BRACKET_RIGHT;
            case '\\': return BACKSLASH;
            case ';': return SEMICOLON;
            case '\'': return QUOTE;
            case ',': return COMMA;
            case '.': return PERIOD;
            case '/': return SLASH;
            default: return UNIDENTIFIED;
        }
    }
}
