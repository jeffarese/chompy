// JNI bridge between com.jeffarese.rexmobile.terminal.emulator.Ghostty and libghostty-vt.
//
// The terminal state lives in libghostty-vt; Java reads it a range of rows at a time and keeps
// Termux's renderer and selection code on top. Rows are numbered as Termux numbers them: 0 to
// rows - 1 is the active screen and negative rows are scrollback, -1 being the newest.
//
// Every call happens on the thread that owns the terminal (the main thread in the app).

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include <ghostty/vt.h>

// Events collected by callbacks while output is written, taken by Java afterwards.
enum { EVENT_BELL = 1, EVENT_TITLE = 2 };

// Termux TextStyle bits (see TextStyle.java).
enum {
    STYLE_BOLD = 1,
    STYLE_ITALIC = 1 << 1,
    STYLE_UNDERLINE = 1 << 2,
    STYLE_BLINK = 1 << 3,
    STYLE_INVERSE = 1 << 4,
    STYLE_INVISIBLE = 1 << 5,
    STYLE_STRIKETHROUGH = 1 << 6,
    STYLE_PROTECTED = 1 << 7,
    STYLE_DIM = 1 << 8,
    STYLE_TRUECOLOR_FG = 1 << 9,
    STYLE_TRUECOLOR_BG = 1 << 10,
    STYLE_UNDERLINE_SHIFT = 11, // 3 bits: GhosttySgrUnderline
    STYLE_OVERLINE = 1 << 14,
    COLOR_INDEX_FOREGROUND = 256,
    COLOR_INDEX_BACKGROUND = 257,
};

typedef struct {
    GhosttyTerminal terminal;
    // The row at the top of the active screen when output was last written, to count the rows
    // that output moved into scrollback.
    GhosttyTrackedGridRef anchor;
    GhosttyRenderState render;
    int events;
    // Made on the first key pressed.
    GhosttyKeyEncoder keys;
    GhosttyKeyEvent key;
} Term;

static void on_bell(GhosttyTerminal t, void* userdata) {
    (void)t;
    ((Term*)userdata)->events |= EVENT_BELL;
}

static void on_title(GhosttyTerminal t, void* userdata) {
    (void)t;
    ((Term*)userdata)->events |= EVENT_TITLE;
}

static void set_scrollback(GhosttyTerminal t, size_t lines) {
    ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &lines);
    // The default byte limit is far below a long conversation; the line limit decides instead.
    ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, NULL);
}

static size_t scrollback_rows(GhosttyTerminal t) {
    size_t n = 0;
    ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &n);
    return n;
}

static void anchor_at_top(Term* term) {
    GhosttyPoint top = {.tag = GHOSTTY_POINT_TAG_ACTIVE, .value = {.coordinate = {.x = 0, .y = 0}}};
    if (term->anchor) {
        ghostty_tracked_grid_ref_set(term->anchor, term->terminal, top);
    } else {
        ghostty_terminal_grid_ref_track(term->terminal, top, &term->anchor);
    }
}

static Term* wrap(GhosttyTerminal t, jint scrollback) {
    Term* term = calloc(1, sizeof(Term));
    if (!term) return NULL;
    term->terminal = t;
    ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_USERDATA, term);
    ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_BELL, (const void*)on_bell);
    ghostty_terminal_set(t, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, (const void*)on_title);
    // No WRITE_PTY: the server's own terminal has answered every query in the output already.
    set_scrollback(t, (size_t)scrollback);
    ghostty_render_state_new(NULL, &term->render);
    anchor_at_top(term);
    return term;
}

// Every function taking a handle returns a harmless default for 0, which a released emulator or a
// closed decoder holds, so a reference kept past either reads nothing rather than freed memory.
#define TERM(h) ((Term*)(intptr_t)(h))
#define JNI(name) Java_com_jeffarese_rexmobile_terminal_emulator_Ghostty_##name

JNIEXPORT jlong JNICALL JNI(newTerminal)(JNIEnv* env, jclass c, jint columns, jint rows, jint scrollback) {
    GhosttyTerminal t = NULL;
    if (ghostty_terminal_new(NULL, &t, (uint16_t)columns, (uint16_t)rows) != GHOSTTY_SUCCESS) return 0;
    Term* term = wrap(t, scrollback);
    if (!term) ghostty_terminal_free(t);
    return (jlong)(intptr_t)term;
}

JNIEXPORT void JNICALL JNI(free)(JNIEnv* env, jclass c, jlong h) {
    if (!h) return;
    Term* term = TERM(h);
    if (term->anchor) ghostty_tracked_grid_ref_free(term->anchor);
    if (term->render) ghostty_render_state_free(term->render);
    if (term->key) ghostty_key_event_free(term->key);
    if (term->keys) ghostty_key_encoder_free(term->keys);
    ghostty_terminal_free(term->terminal);
    free(term);
}

static size_t utf8(uint32_t cp, char* out) {
    if (cp < 0x80) {
        out[0] = (char)cp;
        return 1;
    }
    if (cp < 0x800) {
        out[0] = (char)(0xC0 | (cp >> 6));
        out[1] = (char)(0x80 | (cp & 0x3F));
        return 2;
    }
    if (cp < 0x10000) {
        out[0] = (char)(0xE0 | (cp >> 12));
        out[1] = (char)(0x80 | ((cp >> 6) & 0x3F));
        out[2] = (char)(0x80 | (cp & 0x3F));
        return 3;
    }
    out[0] = (char)(0xF0 | (cp >> 18));
    out[1] = (char)(0x80 | ((cp >> 12) & 0x3F));
    out[2] = (char)(0x80 | ((cp >> 6) & 0x3F));
    out[3] = (char)(0x80 | (cp & 0x3F));
    return 4;
}

// The bytes a key press sends, in the form the program asked for: cursor and keypad modes,
// modifyOtherKeys or the Kitty keyboard protocol, as the server's own terminal would send them.
// key is a GhosttyKey and mods and consumed are GhosttyMods, consumed being those that made the
// text; text is the code point the key types before ctrl or alt change it (0 for none) and
// unshifted the one it types without shift. Null if the key sends nothing.
JNIEXPORT jbyteArray JNICALL JNI(encodeKey)(
    JNIEnv* env, jclass c, jlong h, jint key, jint mods, jint consumed, jint text, jint unshifted) {
    if (!h) return NULL;
    Term* term = TERM(h);
    if (!term->keys && ghostty_key_encoder_new(NULL, &term->keys) != GHOSTTY_SUCCESS) {
        term->keys = NULL;
        return NULL;
    }
    if (!term->key && ghostty_key_event_new(NULL, &term->key) != GHOSTTY_SUCCESS) {
        term->key = NULL;
        return NULL;
    }
    ghostty_key_encoder_setopt_from_terminal(term->keys, term->terminal);
    // Alt is always alt. This only changes anything in the host library the unit tests load, as
    // libghostty-vt reads the option key as macOS does only when built for macOS.
    GhosttyOptionAsAlt alt = GHOSTTY_OPTION_AS_ALT_TRUE;
    ghostty_key_encoder_setopt(term->keys, GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT, &alt);

    char typed[4];
    size_t typed_len = text > 0 ? utf8((uint32_t)text, typed) : 0;
    GhosttyKeyEvent e = term->key;
    ghostty_key_event_set_action(e, GHOSTTY_KEY_ACTION_PRESS);
    ghostty_key_event_set_key(e, (GhosttyKey)key);
    ghostty_key_event_set_mods(e, (GhosttyMods)mods);
    ghostty_key_event_set_consumed_mods(e, (GhosttyMods)consumed);
    ghostty_key_event_set_utf8(e, typed_len ? typed : NULL, typed_len);
    ghostty_key_event_set_unshifted_codepoint(e, (uint32_t)unshifted);

    char out[128];
    size_t n = 0;
    GhosttyResult r = ghostty_key_encoder_encode(term->keys, e, out, sizeof out, &n);
    // The event keeps the text's address, which is about to go.
    ghostty_key_event_set_utf8(e, NULL, 0);
    if (r != GHOSTTY_SUCCESS || n == 0) return NULL;
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)n);
    if (bytes) (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)n, (const jbyte*)out);
    return bytes;
}

JNIEXPORT void JNICALL JNI(write)(JNIEnv* env, jclass c, jlong h, jbyteArray data, jint offset, jint length) {
    if (!h) return;
    jbyte* bytes = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (!bytes) return;
    ghostty_terminal_vt_write(TERM(h)->terminal, (const uint8_t*)bytes + offset, (size_t)length);
    (*env)->ReleasePrimitiveArrayCritical(env, data, bytes, JNI_ABORT);
}

JNIEXPORT jint JNICALL JNI(takeEvents)(JNIEnv* env, jclass c, jlong h) {
    if (!h) return 0;
    Term* term = TERM(h);
    int e = term->events;
    term->events = 0;
    return e;
}

// Rows the output written since the last call moved into scrollback, or -1 if that can't be told,
// such as after a reset or when the old top row has been pruned from scrollback.
JNIEXPORT jint JNICALL JNI(takeScrolled)(JNIEnv* env, jclass c, jlong h) {
    if (!h) return 0;
    Term* term = TERM(h);
    jint scrolled = -1;
    GhosttyPointCoordinate was;
    if (term->anchor && ghostty_tracked_grid_ref_has_value(term->anchor) &&
        ghostty_tracked_grid_ref_point(term->anchor, GHOSTTY_POINT_TAG_SCREEN, &was) == GHOSTTY_SUCCESS) {
        long top = (long)scrollback_rows(term->terminal);
        long moved = top - (long)was.y;
        scrolled = moved >= 0 ? (jint)moved : -1;
    }
    anchor_at_top(term);
    return scrolled;
}

JNIEXPORT void JNICALL JNI(resize)(JNIEnv* env, jclass c, jlong h, jint columns, jint rows) {
    if (!h) return;
    Term* term = TERM(h);
    ghostty_terminal_resize(term->terminal, (uint16_t)columns, (uint16_t)rows, 0, 0);
    anchor_at_top(term);
}

JNIEXPORT void JNICALL JNI(reset)(JNIEnv* env, jclass c, jlong h) {
    if (!h) return;
    Term* term = TERM(h);
    ghostty_terminal_reset(term->terminal);
    anchor_at_top(term);
}

// Keys for getLong, as numbered in Ghostty.java.
enum {
    KEY_COLUMNS = 0,
    KEY_ROWS,
    KEY_CURSOR_X,
    KEY_CURSOR_Y,
    KEY_CURSOR_VISIBLE,
    KEY_CURSOR_STYLE,
    KEY_CURSOR_BLINKING,
    KEY_ALTERNATE_SCREEN,
    KEY_MOUSE_TRACKING,
    KEY_SCROLLBACK_ROWS,
    KEY_KITTY_KEYBOARD_FLAGS,
    KEY_RESIDENT_BYTES,
};

JNIEXPORT jlong JNICALL JNI(getLong)(JNIEnv* env, jclass c, jlong h, jint key) {
    if (!h) return 0;
    Term* term = TERM(h);
    GhosttyTerminal t = term->terminal;
    switch (key) {
        case KEY_COLUMNS: {
            uint16_t v = 0;
            ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_COLS, &v);
            return v;
        }
        case KEY_ROWS: {
            uint16_t v = 0;
            ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_ROWS, &v);
            return v;
        }
        case KEY_CURSOR_X: {
            uint16_t v = 0;
            ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_CURSOR_X, &v);
            return v;
        }
        case KEY_CURSOR_Y: {
            uint16_t v = 0;
            ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &v);
            return v;
        }
        case KEY_CURSOR_VISIBLE: {
            bool v = false;
            ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_CURSOR_VISIBLE, &v);
            return v;
        }
        case KEY_CURSOR_STYLE:
        case KEY_CURSOR_BLINKING: {
            if (!term->render || ghostty_render_state_update(term->render, t) != GHOSTTY_SUCCESS) return 0;
            if (key == KEY_CURSOR_BLINKING) {
                bool v = false;
                ghostty_render_state_get(term->render, GHOSTTY_RENDER_STATE_DATA_CURSOR_BLINKING, &v);
                return v;
            }
            GhosttyRenderStateCursorVisualStyle v = GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK;
            ghostty_render_state_get(term->render, GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE, &v);
            return v;
        }
        case KEY_ALTERNATE_SCREEN: {
            GhosttyTerminalScreen v = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
            ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &v);
            return v == GHOSTTY_TERMINAL_SCREEN_ALTERNATE;
        }
        case KEY_MOUSE_TRACKING: {
            bool v = false;
            ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &v);
            return v;
        }
        case KEY_SCROLLBACK_ROWS:
            return (jlong)scrollback_rows(t);
        case KEY_KITTY_KEYBOARD_FLAGS: {
            uint8_t v = 0;
            ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_KITTY_KEYBOARD_FLAGS, &v);
            return v;
        }
        case KEY_RESIDENT_BYTES: {
            GhosttyTerminalMemoryUsage m = GHOSTTY_INIT_SIZED(GhosttyTerminalMemoryUsage);
            if (ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_MEMORY_USAGE, &m) != GHOSTTY_SUCCESS) return -1;
            return (jlong)(m.primary_resident_bytes + m.alternate_resident_bytes);
        }
    }
    return 0;
}

JNIEXPORT jboolean JNICALL JNI(mode)(JNIEnv* env, jclass c, jlong h, jint number, jboolean ansi) {
    if (!h) return JNI_FALSE;
    GhosttyTerminalModeConfig config = {.mode = ghostty_mode_new((uint16_t)number, ansi), .value = false};
    if (ghostty_terminal_get(TERM(h)->terminal, GHOSTTY_TERMINAL_DATA_MODE, &config) != GHOSTTY_SUCCESS) return JNI_FALSE;
    return config.value ? JNI_TRUE : JNI_FALSE;
}

// A token that changes whenever the terminal has history compressing might shrink, so the app restarts
// its idle timer only then.
JNIEXPORT jlong JNICALL JNI(compressionActivity)(JNIEnv* env, jclass c, jlong h) {
    if (!h) return 0;
    uint64_t token = 0;
    ghostty_terminal_compression_activity(TERM(h)->terminal, &token);
    return (jlong)token;
}

// Compress history pages: one short step, or every page that can be. True while more remains.
JNIEXPORT jboolean JNICALL JNI(compress)(JNIEnv* env, jclass c, jlong h, jboolean full) {
    if (!h) return JNI_FALSE;
    GhosttyTerminalCompressionResult result = GHOSTTY_TERMINAL_COMPRESSION_RESULT_COMPLETE;
    GhosttyTerminalCompressionMode mode = full ? GHOSTTY_TERMINAL_COMPRESSION_MODE_FULL : GHOSTTY_TERMINAL_COMPRESSION_MODE_INCREMENTAL;
    if (ghostty_terminal_compress(TERM(h)->terminal, mode, &result) != GHOSTTY_SUCCESS) return JNI_FALSE;
    return result == GHOSTTY_TERMINAL_COMPRESSION_RESULT_PENDING ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL JNI(title)(JNIEnv* env, jclass c, jlong h) {
    if (!h) return NULL;
    GhosttyString s = {0};
    if (ghostty_terminal_get(TERM(h)->terminal, GHOSTTY_TERMINAL_DATA_TITLE, &s) != GHOSTTY_SUCCESS || s.len == 0) return NULL;
    char* z = malloc(s.len + 1);
    if (!z) return NULL;
    memcpy(z, s.ptr, s.len);
    z[s.len] = 0;
    jstring out = (*env)->NewStringUTF(env, z);
    free(z);
    return out;
}

static jint argb(GhosttyColorRgb c) {
    return (jint)(0xff000000u | ((uint32_t)c.r << 16) | ((uint32_t)c.g << 8) | c.b);
}

static int same(GhosttyColorRgb a, GhosttyColorRgb b) {
    return a.r == b.r && a.g == b.g && a.b == b.b;
}

// The colors programs have changed with OSC 4, 10, 11 and 12, as Termux keeps them: 256 palette
// entries, then foreground, background and cursor, each 0xAARRGGBB, or 0 where a color is as the
// terminal started. Returns whether any is changed.
JNIEXPORT jboolean JNICALL JNI(changedColors)(JNIEnv* env, jclass c, jlong h, jintArray out) {
    if (!h) return JNI_FALSE;
    GhosttyTerminal t = TERM(h)->terminal;
    jint colors[259] = {0};
    jboolean any = JNI_FALSE;
    GhosttyColorRgb palette[256], defaults[256];
    if (ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE, palette) == GHOSTTY_SUCCESS &&
        ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE_DEFAULT, defaults) == GHOSTTY_SUCCESS) {
        for (int i = 0; i < 256; i++) {
            if (same(palette[i], defaults[i])) continue;
            colors[i] = argb(palette[i]);
            any = JNI_TRUE;
        }
    }
    const GhosttyTerminalData keys[3][2] = {
        {GHOSTTY_TERMINAL_DATA_COLOR_FOREGROUND, GHOSTTY_TERMINAL_DATA_COLOR_FOREGROUND_DEFAULT},
        {GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND, GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND_DEFAULT},
        {GHOSTTY_TERMINAL_DATA_COLOR_CURSOR, GHOSTTY_TERMINAL_DATA_COLOR_CURSOR_DEFAULT},
    };
    for (int i = 0; i < 3; i++) {
        GhosttyColorRgb now, initial;
        if (ghostty_terminal_get(t, keys[i][0], &now) != GHOSTTY_SUCCESS) continue;
        if (ghostty_terminal_get(t, keys[i][1], &initial) == GHOSTTY_SUCCESS && same(now, initial)) continue;
        colors[256 + i] = argb(now);
        any = JNI_TRUE;
    }
    (*env)->SetIntArrayRegion(env, out, 0, 259, colors);
    return any;
}

static int64_t encode_color(GhosttyStyleColor color, int64_t none, int* truecolor) {
    switch (color.tag) {
        case GHOSTTY_STYLE_COLOR_PALETTE:
            return color.value.palette;
        case GHOSTTY_STYLE_COLOR_RGB:
            *truecolor = 1;
            return ((int64_t)color.value.rgb.r << 16) | ((int64_t)color.value.rgb.g << 8) | color.value.rgb.b;
        default:
            return none;
    }
}

static int64_t encode_style(const GhosttyStyle* s) {
    int64_t effect = 0;
    if (s->bold) effect |= STYLE_BOLD;
    if (s->italic) effect |= STYLE_ITALIC;
    if (s->underline != GHOSTTY_SGR_UNDERLINE_NONE)
        effect |= STYLE_UNDERLINE | ((int64_t)(s->underline & 7) << STYLE_UNDERLINE_SHIFT);
    if (s->blink) effect |= STYLE_BLINK;
    if (s->inverse) effect |= STYLE_INVERSE;
    if (s->invisible) effect |= STYLE_INVISIBLE;
    if (s->strikethrough) effect |= STYLE_STRIKETHROUGH;
    if (s->faint) effect |= STYLE_DIM;
    if (s->overline) effect |= STYLE_OVERLINE;
    int fg_true = 0, bg_true = 0;
    int64_t fg = encode_color(s->fg_color, COLOR_INDEX_FOREGROUND, &fg_true);
    int64_t bg = encode_color(s->bg_color, COLOR_INDEX_BACKGROUND, &bg_true);
    if (fg_true) effect |= STYLE_TRUECOLOR_FG;
    if (bg_true) effect |= STYLE_TRUECOLOR_BG;
    return effect | (fg << 40) | (bg << 16);
}

static int64_t with_background(int64_t style, int64_t bg, int truecolor) {
    style &= ~((int64_t)0xffffff << 16);
    style &= ~(int64_t)STYLE_TRUECOLOR_BG;
    return style | (bg << 16) | (truecolor ? STYLE_TRUECOLOR_BG : 0);
}

typedef struct {
    jchar* chars;
    size_t len, cap;
} Chars;

static int put(Chars* out, uint32_t cp) {
    if (out->len + 2 > out->cap) {
        size_t cap = out->cap ? out->cap * 2 : 4096;
        jchar* grown = realloc(out->chars, cap * sizeof(jchar));
        if (!grown) return 0;
        out->chars = grown;
        out->cap = cap;
    }
    if (cp >= 0x10000) {
        cp -= 0x10000;
        out->chars[out->len++] = (jchar)(0xD800 + (cp >> 10));
        out->chars[out->len++] = (jchar)(0xDC00 + (cp & 0x3FF));
    } else {
        out->chars[out->len++] = (jchar)cp;
    }
    return 1;
}

// Read rows [first, first + count) into Termux's shapes: the text of every column in turn (a space
// for an empty cell, nothing for the right half of a wide character), where each column's text
// starts (count * (columns + 1) offsets, the last of each row being its end), each column's style,
// and whether each row wraps onto the next. Rows outside the terminal come back blank.
JNIEXPORT jcharArray JNICALL JNI(readRows)(
    JNIEnv* env, jclass c, jlong h, jint first, jint count, jintArray starts, jlongArray styles, jbooleanArray wraps) {
    if (!h) return NULL;
    GhosttyTerminal t = TERM(h)->terminal;
    uint16_t cols = 0, rows = 0;
    ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_COLS, &cols);
    ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
    long history = (long)scrollback_rows(t);

    jint* start = malloc(sizeof(jint) * (size_t)count * (cols + 1));
    jlong* style = malloc(sizeof(jlong) * (size_t)count * cols);
    jboolean* wrap = calloc((size_t)count, sizeof(jboolean));
    Chars out = {0};
    uint32_t grapheme[32];
    const int64_t blank = encode_style(&(GhosttyStyle){.size = sizeof(GhosttyStyle)});
    int ok = start && style && wrap;

    for (jint r = 0; ok && r < count; r++) {
        long y = history + first + r;
        int inside = y >= 0 && y < history + rows;
        jint* rs = start + (size_t)r * (cols + 1);
        jlong* st = style + (size_t)r * cols;
        // Found once a row: a screen point is found by walking the history from its top. The row's
        // cells are in the same page, so each is the row's reference at its column.
        GhosttyGridRef first_cell = GHOSTTY_INIT_SIZED(GhosttyGridRef);
        if (inside) {
            GhosttyPoint pt = {.tag = GHOSTTY_POINT_TAG_SCREEN, .value = {.coordinate = {.x = 0, .y = (uint32_t)y}}};
            if (ghostty_terminal_grid_ref(t, pt, &first_cell) == GHOSTTY_SUCCESS) {
                GhosttyRow row;
                bool w = false;
                if (ghostty_grid_ref_row(&first_cell, &row) == GHOSTTY_SUCCESS) ghostty_row_get(row, GHOSTTY_ROW_DATA_WRAP, &w);
                wrap[r] = w;
            } else {
                inside = 0;
            }
        }
        for (uint16_t x = 0; ok && x < cols; x++) {
            rs[x] = (jint)out.len;
            st[x] = blank;
            if (!inside) {
                ok = put(&out, ' ');
                continue;
            }
            GhosttyGridRef ref = first_cell;
            ref.x = (uint16_t)(first_cell.x + x);
            GhosttyCell cell;
            if (ghostty_grid_ref_cell(&ref, &cell) != GHOSTTY_SUCCESS) {
                ok = put(&out, ' ');
                continue;
            }
            GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
            ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &wide);
            GhosttyStyle s = GHOSTTY_INIT_SIZED(GhosttyStyle);
            if (ghostty_grid_ref_style(&ref, &s) == GHOSTTY_SUCCESS) st[x] = encode_style(&s);
            GhosttyCellContentTag tag = GHOSTTY_CELL_CONTENT_CODEPOINT;
            ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CONTENT_TAG, &tag);
            if (tag == GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE) {
                uint8_t index = 0;
                ghostty_cell_get(cell, GHOSTTY_CELL_DATA_COLOR_PALETTE, &index);
                st[x] = with_background(st[x], index, 0);
            } else if (tag == GHOSTTY_CELL_CONTENT_BG_COLOR_RGB) {
                GhosttyColorRgb rgb = {0};
                ghostty_cell_get(cell, GHOSTTY_CELL_DATA_COLOR_RGB, &rgb);
                st[x] = with_background(st[x], ((int64_t)rgb.r << 16) | ((int64_t)rgb.g << 8) | rgb.b, 1);
            }
            // The right half of a wide character has no text of its own.
            if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL) continue;
            uint32_t cp = 0;
            ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CODEPOINT, &cp);
            if (cp == 0 || wide == GHOSTTY_CELL_WIDE_SPACER_HEAD) {
                ok = put(&out, ' ');
                continue;
            }
            size_t n = 0;
            if (tag == GHOSTTY_CELL_CONTENT_CODEPOINT_GRAPHEME &&
                ghostty_grid_ref_graphemes(&ref, grapheme, 32, &n) == GHOSTTY_SUCCESS && n > 0) {
                for (size_t i = 0; ok && i < n; i++) ok = put(&out, grapheme[i]);
            } else {
                ok = put(&out, cp);
            }
        }
        rs[cols] = (jint)out.len;
    }

    jcharArray text = NULL;
    if (ok) {
        text = (*env)->NewCharArray(env, (jsize)out.len);
        if (text) {
            (*env)->SetCharArrayRegion(env, text, 0, (jsize)out.len, out.chars);
            (*env)->SetIntArrayRegion(env, starts, 0, count * (cols + 1), start);
            (*env)->SetLongArrayRegion(env, styles, 0, count * cols, style);
            (*env)->SetBooleanArrayRegion(env, wraps, 0, count, wrap);
        }
    }
    free(out.chars);
    free(start);
    free(style);
    free(wrap);
    return text;
}

// --- Snapshots ---------------------------------------------------------------------------------
//
// A stream's snapshot arrives over the network a piece at a time. The decoder reads from a buffer
// that grows as it does, and is only asked for what has arrived in full: READY once its record is
// in, the history once the whole snapshot is, so it never reads past the end of what is there.

// The snapshot format version libghostty-vt at GHOSTTY_COMMIT writes and reads. Ghostty may change the
// format until a tagged release; a Rex built on a later Ghostty may send another version, which the
// app then redraws the old way.
enum { SNAPSHOT_VERSION = 1, TAG_READY = 5, TAG_FINISH = 6, ENVELOPE = 10, RECORD_HEADER = 10 };

typedef struct {
    uint8_t* bytes;
    size_t len, cap;
    size_t read;     // how far the decoder has read
    size_t scanned;  // the start of the first record not yet seen whole
    size_t ready_end, finish_end;  // just past READY and FINISH, once they have arrived
    GhosttySnapshotDecoder decoder;
    int started, failed, finished;
    size_t limit;         // the restored terminal's history limit, in rows
    size_t primary_rows;  // history rows restored to the primary screen
} Decoder;

static bool decoder_read(void* userdata, uint8_t* buf, size_t len, size_t* out_len) {
    Decoder* d = userdata;
    size_t n = d->len - d->read;
    if (n > len) n = len;
    memcpy(buf, d->bytes + d->read, n);
    d->read += n;
    *out_len = n;
    return true;
}

static void scan_records(Decoder* d) {
    if (d->scanned == 0) {
        // Told apart from the first byte that differs, so a stream that doesn't start with a snapshot
        // this can read is redrawn without waiting for more of it.
        size_t magic = d->len < 8 ? d->len : 8;
        if (memcmp(d->bytes, "GHOSTSNP", magic) != 0) {
            d->failed = 1;
            return;
        }
        if (d->len < ENVELOPE) return;
        if ((d->bytes[8] | d->bytes[9] << 8) != SNAPSHOT_VERSION) {
            d->failed = 1;
            return;
        }
        d->scanned = ENVELOPE;
    }
    while (!d->finish_end && d->len - d->scanned >= RECORD_HEADER) {
        const uint8_t* p = d->bytes + d->scanned;
        uint16_t tag = (uint16_t)(p[0] | p[1] << 8);
        uint32_t payload = (uint32_t)p[2] | (uint32_t)p[3] << 8 | (uint32_t)p[4] << 16 | (uint32_t)p[5] << 24;
        size_t end = d->scanned + RECORD_HEADER + payload;
        if (end > d->len) return;
        d->scanned = end;
        if (tag == TAG_READY) d->ready_end = end;
        if (tag == TAG_FINISH) d->finish_end = end;
    }
}

JNIEXPORT jlong JNICALL JNI(decoderNew)(JNIEnv* env, jclass c) {
    Decoder* d = calloc(1, sizeof(Decoder));
    if (!d) return 0;
    GhosttyReader reader = {.read = decoder_read, .userdata = d};
    if (ghostty_snapshot_decoder_new(NULL, &d->decoder, reader) != GHOSTTY_SUCCESS) {
        free(d);
        return 0;
    }
    bool compress = true;
    ghostty_snapshot_decoder_set(d->decoder, GHOSTTY_SNAPSHOT_DECODER_OPT_COMPRESS_HISTORY, &compress);
    return (jlong)(intptr_t)d;
}

JNIEXPORT void JNICALL JNI(decoderFree)(JNIEnv* env, jclass c, jlong h) {
    if (!h) return;
    Decoder* d = (Decoder*)(intptr_t)h;
    ghostty_snapshot_decoder_free(d->decoder);
    free(d->bytes);
    free(d);
}

// Add bytes that arrived. Returns 0, or 1 once the renderable part (through READY) is in, or 2 once
// the whole snapshot is, or -1 if this isn't a snapshot the decoder can read.
JNIEXPORT jint JNICALL JNI(decoderFeed)(JNIEnv* env, jclass c, jlong h, jbyteArray data, jint offset, jint length) {
    if (!h) return -1;
    Decoder* d = (Decoder*)(intptr_t)h;
    if (d->failed) return -1;
    if (d->len + (size_t)length > d->cap) {
        size_t cap = d->cap ? d->cap : 64 * 1024;
        while (cap < d->len + (size_t)length) cap *= 2;
        uint8_t* grown = realloc(d->bytes, cap);
        if (!grown) {
            d->failed = 1;
            return -1;
        }
        d->bytes = grown;
        d->cap = cap;
    }
    (*env)->GetByteArrayRegion(env, data, offset, length, (jbyte*)d->bytes + d->len);
    d->len += (size_t)length;
    scan_records(d);
    if (d->failed) return -1;
    return d->finish_end ? 2 : d->ready_end ? 1 : 0;
}

// The terminal restored through READY, its history still to come, or 0 if READY hasn't arrived or
// the snapshot can't be read (see decoderFailed).
JNIEXPORT jlong JNICALL JNI(decoderReady)(JNIEnv* env, jclass c, jlong h, jint scrollback) {
    if (!h) return 0;
    Decoder* d = (Decoder*)(intptr_t)h;
    if (d->failed || d->started || !d->ready_end) return 0;
    d->started = 1;
    d->limit = scrollback > 0 ? (size_t)scrollback : 0;
    GhosttyTerminal t = NULL;
    if (ghostty_snapshot_decoder_ready(d->decoder, &t) != GHOSTTY_SUCCESS || !t) {
        d->failed = 1;
        return 0;
    }
    Term* term = wrap(t, scrollback);
    if (!term) {
        ghostty_terminal_free(t);
        d->failed = 1;
    }
    return (jlong)(intptr_t)term;
}

// Restore up to maxPages history pages: the rows they added, or -1 once the history is all there, or
// -2 if it can't be read. Rows restored before the end or a failure are counted first; the next call
// reports that. Pages come newest first, so once the primary screen, where the conversation's
// history is, has had as many rows as the terminal keeps, older ones would only be dropped: that is
// the end too. It is counted there rather than on the active screen, which may be a fullscreen
// program's alternate screen, with no history of its own.
JNIEXPORT jint JNICALL JNI(decoderNext)(JNIEnv* env, jclass c, jlong h, jint maxPages) {
    if (!h) return -2;
    Decoder* d = (Decoder*)(intptr_t)h;
    if (d->finished) return -1;
    if (d->failed || !d->started || !d->finish_end) return -2;
    jint rows = 0;
    for (jint i = 0; i < maxPages; i++) {
        GhosttyResult r = ghostty_snapshot_decoder_next(d->decoder);
        if (r == GHOSTTY_NO_VALUE) {
            d->finished = 1;
            return rows > 0 ? rows : -1;
        }
        if (r != GHOSTTY_SUCCESS) {
            d->failed = 1;
            return rows > 0 ? rows : -2;
        }
        GhosttySnapshotDecoderProgressKind kind = GHOSTTY_SNAPSHOT_DECODER_PROGRESS_KIND_PAGE;
        ghostty_snapshot_decoder_get(d->decoder, GHOSTTY_SNAPSHOT_DECODER_DATA_PROGRESS_KIND, &kind);
        if (kind == GHOSTTY_SNAPSHOT_DECODER_PROGRESS_KIND_PAGE) {
            size_t n = 0;
            GhosttyTerminalScreen screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
            ghostty_snapshot_decoder_get(d->decoder, GHOSTTY_SNAPSHOT_DECODER_DATA_PROGRESS_ROWS, &n);
            ghostty_snapshot_decoder_get(d->decoder, GHOSTTY_SNAPSHOT_DECODER_DATA_PROGRESS_SCREEN, &screen);
            rows += (jint)n;
            if (screen == GHOSTTY_TERMINAL_SCREEN_PRIMARY) d->primary_rows += n;
            if (d->limit && d->primary_rows >= d->limit) {
                d->finished = 1;
                return rows;
            }
        }
    }
    return rows;
}

JNIEXPORT jlong JNICALL JNI(decoderReadyBytes)(JNIEnv* env, jclass c, jlong h) {
    if (!h) return 0;
    return (jlong)((Decoder*)(intptr_t)h)->ready_end;
}
