// JNI bridge between com.jeffarese.rexmobile.terminal.emulator.Ghostty and libghostty-vt.
//
// The terminal state lives in libghostty-vt; Java reads it a range of rows at a time and keeps
// Termux's renderer and selection code on top. Rows are numbered as Termux numbers them: 0 to
// rows - 1 is the active screen and negative rows are scrollback, -1 being the newest.
//
// Every call happens on the thread that owns the terminal (the main thread in the app).

#include <jni.h>
#include <limits.h>
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
    jchar* chars;
    size_t len, cap;
} Chars;

// What a read of rows fills before handing it to Java. Kept from one read to the next, and only
// ever grown, so reading a frame allocates nothing.
typedef struct {
    Chars text;
    jint* starts;
    jlong* styles;
    jboolean* wraps;
    jint* rows;
    size_t rows_cap, cols_cap;  // the rows, of up to so many columns, they have room for
} Rows;

typedef struct {
    GhosttyTerminal terminal;
    // The row at the top of the active screen when output was last written, to count the rows
    // that output moved into scrollback.
    GhosttyTrackedGridRef anchor;
    // The screen as Ghostty's renderers see it, which copies only the rows that changed since it
    // was last brought up to date: done once after output, however often the screen and the
    // cursor are read meanwhile.
    GhosttyRenderState render;
    GhosttyRenderStateRowIterator render_rows;
    GhosttyRenderStateRowCells render_cells;
    int changed;  // output, a resize or a reset since the render state was brought up to date
    // Screen rows to read from the pages as well as those the render state says changed: see write.
    long recheck_top, recheck_bottom;
    Rows read;
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
    if (ghostty_render_state_new(NULL, &term->render) != GHOSTTY_SUCCESS) term->render = NULL;
    if (ghostty_render_state_row_iterator_new(NULL, &term->render_rows) != GHOSTTY_SUCCESS) term->render_rows = NULL;
    if (ghostty_render_state_row_cells_new(NULL, &term->render_cells) != GHOSTTY_SUCCESS) term->render_cells = NULL;
    term->changed = 1;
    term->recheck_top = LONG_MAX;
    term->recheck_bottom = -1;
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
    if (term->render_cells) ghostty_render_state_row_cells_free(term->render_cells);
    if (term->render_rows) ghostty_render_state_row_iterator_free(term->render_rows);
    if (term->render) ghostty_render_state_free(term->render);
    free(term->read.text.chars);
    free(term->read.starts);
    free(term->read.styles);
    free(term->read.wraps);
    free(term->read.rows);
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
    Term* term = TERM(h);
    // A code point that joins the character before it, such as a variation selector or a combining
    // mark, changes the cell left of the cursor without Ghostty marking its row changed (unless
    // mode 2027 is on). When output splits the two, the screen is read between them, so the row the
    // cursor was on when this output came is read again from the pages.
    uint16_t y = 0;
    ghostty_terminal_get(term->terminal, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &y);
    if (y < term->recheck_top) term->recheck_top = y;
    if (y > term->recheck_bottom) term->recheck_bottom = y;
    jbyte* bytes = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (!bytes) return;
    ghostty_terminal_vt_write(term->terminal, (const uint8_t*)bytes + offset, (size_t)length);
    (*env)->ReleasePrimitiveArrayCritical(env, data, bytes, JNI_ABORT);
    term->changed = 1;
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
    term->changed = 1;
    anchor_at_top(term);
}

JNIEXPORT void JNICALL JNI(reset)(JNIEnv* env, jclass c, jlong h) {
    if (!h) return;
    Term* term = TERM(h);
    ghostty_terminal_reset(term->terminal);
    term->changed = 1;
    anchor_at_top(term);
}

// Bring the render state up to date, if the terminal changed since it last was. Ghostty's renderers
// own the screen's dirty flags, so the rows a frame copies are the ones output changed since the last.
static int update_render(Term* term) {
    if (!term->render) return 0;
    if (!term->changed) return 1;
    // The render state follows the viewport, which this app leaves on the active screen: the view
    // scrolls through history itself.
    bool active = true;
    ghostty_terminal_get(term->terminal, GHOSTTY_TERMINAL_DATA_VIEWPORT_ACTIVE, &active);
    if (!active) ghostty_terminal_scroll_viewport(term->terminal, (GhosttyTerminalScrollViewport){.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM});
    if (ghostty_render_state_update(term->render, term->terminal) != GHOSTTY_SUCCESS) return 0;
    term->changed = 0;
    return 1;
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
            // Only Ghostty's render state has them. Every frame asks, so it is brought up to date
            // only after a change, and then the rows read next need no update of their own.
            if (!update_render(term)) return 0;
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

// Room in the buffers for count rows of cols columns, growing them if they haven't enough.
static int reserve(Rows* out, size_t count, size_t cols) {
    if (count <= out->rows_cap && cols <= out->cols_cap) return 1;
    size_t rows_cap = count > out->rows_cap ? count : out->rows_cap;
    size_t cols_cap = cols > out->cols_cap ? cols : out->cols_cap;
    jint* starts = realloc(out->starts, sizeof(jint) * rows_cap * (cols_cap + 1));
    if (!starts) return 0;
    out->starts = starts;
    jlong* styles = realloc(out->styles, sizeof(jlong) * rows_cap * cols_cap);
    if (!styles) return 0;
    out->styles = styles;
    jboolean* wraps = realloc(out->wraps, sizeof(jboolean) * rows_cap);
    if (!wraps) return 0;
    out->wraps = wraps;
    jint* rows = realloc(out->rows, sizeof(jint) * rows_cap);
    if (!rows) return 0;
    out->rows = rows;
    out->rows_cap = rows_cap;
    out->cols_cap = cols_cap;
    return 1;
}

// The longest grapheme cluster read whole; a longer one shows its first code point.
enum { MAX_GRAPHEME = 32 };

static GhosttyCellContentTag content_tag(GhosttyCell cell) {
    GhosttyCellContentTag tag = GHOSTTY_CELL_CONTENT_CODEPOINT;
    ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CONTENT_TAG, &tag);
    return tag;
}

// A cell in Termux's shapes, after the cells before it in its row: its style in *st, from s or
// left as it is if s is null, and its text, a space for an empty cell and nothing for the right
// half of a wide character. The n code points in grapheme are its text when it is a cluster.
static int put_cell(Chars* out, GhosttyCell cell, GhosttyCellContentTag tag, const GhosttyStyle* s,
                    const uint32_t* grapheme, size_t n, jlong* st) {
    GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
    ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &wide);
    if (s) *st = encode_style(s);
    if (tag == GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE) {
        uint8_t index = 0;
        ghostty_cell_get(cell, GHOSTTY_CELL_DATA_COLOR_PALETTE, &index);
        *st = with_background(*st, index, 0);
    } else if (tag == GHOSTTY_CELL_CONTENT_BG_COLOR_RGB) {
        GhosttyColorRgb rgb = {0};
        ghostty_cell_get(cell, GHOSTTY_CELL_DATA_COLOR_RGB, &rgb);
        *st = with_background(*st, ((int64_t)rgb.r << 16) | ((int64_t)rgb.g << 8) | rgb.b, 1);
    }
    // The right half of a wide character has no text of its own.
    if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL) return 1;
    uint32_t cp = 0;
    ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CODEPOINT, &cp);
    if (cp == 0 || wide == GHOSTTY_CELL_WIDE_SPACER_HEAD) return put(out, ' ');
    if (tag != GHOSTTY_CELL_CONTENT_CODEPOINT_GRAPHEME || n == 0) return put(out, cp);
    for (size_t i = 0; i < n; i++) {
        if (!put(out, grapheme[i])) return 0;
    }
    return 1;
}

// Row `row` of the terminal's pages, numbered as Ghostty.java numbers rows, into the buffers as
// their row i. A row outside the terminal comes back blank.
static int put_row(GhosttyTerminal t, Rows* out, size_t i, long row, long history, uint16_t rows, uint16_t cols) {
    jint* rs = out->starts + i * (cols + 1);
    jlong* st = out->styles + i * cols;
    const int64_t blank = encode_style(&(GhosttyStyle){.size = sizeof(GhosttyStyle)});
    uint32_t grapheme[MAX_GRAPHEME];
    int inside = row >= -history && row < rows;
    out->wraps[i] = JNI_FALSE;
    // Found once a row, a screen row from the top of the screen and a history row by walking the
    // history from its top. The row's cells are in the same page, so each is the row's reference
    // at its column.
    GhosttyGridRef first_cell = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    if (inside) {
        GhosttyPoint pt = row >= 0
            ? (GhosttyPoint){.tag = GHOSTTY_POINT_TAG_ACTIVE, .value = {.coordinate = {.x = 0, .y = (uint32_t)row}}}
            : (GhosttyPoint){.tag = GHOSTTY_POINT_TAG_SCREEN, .value = {.coordinate = {.x = 0, .y = (uint32_t)(history + row)}}};
        if (ghostty_terminal_grid_ref(t, pt, &first_cell) == GHOSTTY_SUCCESS) {
            GhosttyRow gr;
            bool w = false;
            if (ghostty_grid_ref_row(&first_cell, &gr) == GHOSTTY_SUCCESS) ghostty_row_get(gr, GHOSTTY_ROW_DATA_WRAP, &w);
            out->wraps[i] = w;
        } else {
            inside = 0;
        }
    }
    for (uint16_t x = 0; x < cols; x++) {
        rs[x] = (jint)out->text.len;
        st[x] = blank;
        GhosttyGridRef ref = first_cell;
        ref.x = (uint16_t)(first_cell.x + x);
        GhosttyCell cell;
        if (!inside || ghostty_grid_ref_cell(&ref, &cell) != GHOSTTY_SUCCESS) {
            if (!put(&out->text, ' ')) return 0;
            continue;
        }
        GhosttyStyle s = GHOSTTY_INIT_SIZED(GhosttyStyle);
        int styled = ghostty_grid_ref_style(&ref, &s) == GHOSTTY_SUCCESS;
        GhosttyCellContentTag tag = content_tag(cell);
        size_t n = 0;
        if (tag == GHOSTTY_CELL_CONTENT_CODEPOINT_GRAPHEME &&
            ghostty_grid_ref_graphemes(&ref, grapheme, MAX_GRAPHEME, &n) != GHOSTTY_SUCCESS) n = 0;
        if (!put_cell(&out->text, cell, tag, styled ? &s : NULL, grapheme, n, &st[x])) return 0;
    }
    rs[cols] = (jint)out->text.len;
    return 1;
}

// Hand Java what a read put in the buffers: count rows of cols columns. Returns the text's length,
// or minus that if text hasn't room for it, which copies nothing.
static jint hand_over(JNIEnv* env, const Rows* in, size_t count, uint16_t cols,
                      jcharArray text, jintArray starts, jlongArray styles, jbooleanArray wraps) {
    jsize len = (jsize)in->text.len;
    if ((*env)->GetArrayLength(env, text) < len) return -len;
    (*env)->SetCharArrayRegion(env, text, 0, len, in->text.chars);
    (*env)->SetIntArrayRegion(env, starts, 0, (jsize)(count * (cols + 1)), in->starts);
    (*env)->SetLongArrayRegion(env, styles, 0, (jsize)(count * cols), in->styles);
    (*env)->SetBooleanArrayRegion(env, wraps, 0, (jsize)count, in->wraps);
    return len;
}

// Read rows [first, first + count) into Termux's shapes, in text and the arrays given: the text of
// every column in turn (a space for an empty cell, nothing for the right half of a wide character),
// where each column's text starts (count * (columns + 1) offsets, the last of each row being its
// end), each column's style, and whether each row wraps onto the next. Rows outside the terminal
// come back blank. Returns the text's length, minus that if text hasn't room for it, or 0 if
// nothing could be read.
//
// This is for history, which only changes as a whole: readScreen reads the screen, a changed row
// at a time.
JNIEXPORT jint JNICALL JNI(readRows)(
    JNIEnv* env, jclass c, jlong h, jint first, jint count, jcharArray text, jintArray starts, jlongArray styles,
    jbooleanArray wraps) {
    if (!h || count <= 0) return 0;
    Term* term = TERM(h);
    GhosttyTerminal t = term->terminal;
    uint16_t cols = 0, rows = 0;
    ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_COLS, &cols);
    ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_ROWS, &rows);
    long history = (long)scrollback_rows(t);

    Rows* out = &term->read;
    if (!reserve(out, (size_t)count, cols)) return 0;
    out->text.len = 0;
    for (jint r = 0; r < count; r++) {
        if (!put_row(t, out, (size_t)r, (long)first + r, history, rows, cols)) return 0;
    }
    return hand_over(env, out, (size_t)count, cols, text, starts, styles, wraps);
}

// The row the render state's iterator is on, into the buffers as their row i.
static int put_render_row(Term* term, size_t i, uint16_t cols) {
    Rows* out = &term->read;
    jint* rs = out->starts + i * (cols + 1);
    jlong* st = out->styles + i * cols;
    const int64_t blank = encode_style(&(GhosttyStyle){.size = sizeof(GhosttyStyle)});
    GhosttyRow raw;
    bool w = false;
    if (ghostty_render_state_row_get(term->render_rows, GHOSTTY_RENDER_STATE_ROW_DATA_RAW, &raw) == GHOSTTY_SUCCESS)
        ghostty_row_get(raw, GHOSTTY_ROW_DATA_WRAP, &w);
    out->wraps[i] = w;
    GhosttyRenderStateRowCells cells = term->render_cells;
    int have = ghostty_render_state_row_get(term->render_rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &cells) == GHOSTTY_SUCCESS;
    uint32_t grapheme[MAX_GRAPHEME];
    for (uint16_t x = 0; x < cols; x++) {
        rs[x] = (jint)out->text.len;
        st[x] = blank;
        GhosttyCell cell;
        if (!have || !ghostty_render_state_row_cells_next(cells) ||
            ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &cell) != GHOSTTY_SUCCESS) {
            if (!put(&out->text, ' ')) return 0;
            continue;
        }
        // A cell without a style of its own keeps the blank one.
        bool has_style = false;
        ghostty_cell_get(cell, GHOSTTY_CELL_DATA_HAS_STYLING, &has_style);
        GhosttyStyle s = GHOSTTY_INIT_SIZED(GhosttyStyle);
        int styled = has_style && ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &s) == GHOSTTY_SUCCESS;
        GhosttyCellContentTag tag = content_tag(cell);
        uint32_t n = 0;
        if (tag == GHOSTTY_CELL_CONTENT_CODEPOINT_GRAPHEME) {
            ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &n);
            if (n > MAX_GRAPHEME ||
                ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF, grapheme) != GHOSTTY_SUCCESS) n = 0;
        }
        if (!put_cell(&out->text, cell, tag, styled ? &s : NULL, grapheme, n, &st[x])) return 0;
    }
    rs[cols] = (jint)out->text.len;
    return 1;
}

static int has_row(const Rows* in, size_t count, jint y) {
    for (size_t i = 0; i < count; i++) {
        if (in->rows[i] == y) return 1;
    }
    return 0;
}

// Read the screen rows that changed since the last read, or all of them, as readRows reads rows,
// with each one's number in rows. Ghostty marks the rows output changes, so a spinner turning
// reads its own row rather than the screen. Returns how many rows were read, or minus the text's
// length if text hasn't room for it, in which case they are still unread for the next call.
JNIEXPORT jint JNICALL JNI(readScreen)(
    JNIEnv* env, jclass c, jlong h, jboolean all, jintArray rows, jcharArray text, jintArray starts, jlongArray styles,
    jbooleanArray wraps) {
    if (!h) return 0;
    Term* term = TERM(h);
    GhosttyTerminal t = term->terminal;
    // Without a render state, as when making one failed, every row is read from the pages each time.
    GhosttyRenderState render = term->render_rows && term->render_cells && update_render(term) ? term->render : NULL;
    uint16_t cols = 0, height = 0;
    ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_COLS, &cols);
    ghostty_terminal_get(t, GHOSTTY_TERMINAL_DATA_ROWS, &height);
    GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FULL;
    if (render) ghostty_render_state_get(render, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty);
    int recheck = term->recheck_top <= term->recheck_bottom;
    if (render && !all && !recheck && dirty == GHOSTTY_RENDER_STATE_DIRTY_FALSE) return 0;

    Rows* out = &term->read;
    if (!reserve(out, height, cols)) return 0;
    out->text.len = 0;
    size_t count = 0;
    if (all || !render) {
        // From the pages: a row the render state wasn't told had changed may be out of date in it.
        for (uint16_t y = 0; y < height; y++) {
            if (!put_row(t, out, count, y, 0, height, cols)) return 0;
            out->rows[count++] = y;
        }
    } else {
        GhosttyRenderStateRowIterator it = term->render_rows;
        if (ghostty_render_state_get(render, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &it) != GHOSTTY_SUCCESS) return 0;
        uint16_t y = 0;
        while (ghostty_render_state_row_iterator_next_dirty(it, &y)) {
            if (count == height || !put_render_row(term, count, cols)) return 0;
            out->rows[count++] = y;
        }
        for (long r = term->recheck_top; recheck && r <= term->recheck_bottom && r < height; r++) {
            if (has_row(out, count, (jint)r)) continue;
            if (!put_row(t, out, count, r, 0, height, cols)) return 0;
            out->rows[count++] = (jint)r;
        }
    }
    jint len = hand_over(env, out, count, cols, text, starts, styles, wraps);
    if (len < 0) return len;
    (*env)->SetIntArrayRegion(env, rows, 0, (jsize)count, out->rows);
    if (render) ghostty_render_state_clean(render);
    term->recheck_top = LONG_MAX;
    term->recheck_bottom = -1;
    return (jint)count;
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
