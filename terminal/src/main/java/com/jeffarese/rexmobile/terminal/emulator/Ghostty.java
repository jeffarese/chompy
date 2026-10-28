package com.jeffarese.rexmobile.terminal.emulator;

/**
 * The native terminal: libghostty-vt, through the JNI bridge in {@code src/main/cpp/rexterm.c}.
 * <p>
 * Rows are numbered as Termux numbers them: 0 to rows - 1 is the active screen and negative rows
 * are scrollback, -1 being the newest. Handles are only used on the thread that owns them.
 */
final class Ghostty {
    static {
        try {
            System.loadLibrary("rexterm");
        } catch (UnsatisfiedLinkError e) {
            loadCopy(e);
        }
    }

    /**
     * A JVM binds a library to one classloader, and Robolectric gives each test sandbox its own, so
     * the unit tests load a copy of the library in each sandbox after the first. Android never does.
     */
    private static void loadCopy(UnsatisfiedLinkError e) {
        String message = e.getMessage();
        if (message == null || !message.contains("already loaded in another classloader")) throw e;
        String name = System.mapLibraryName("rexterm");
        for (String dir : System.getProperty("java.library.path", "").split(java.io.File.pathSeparator)) {
            java.io.File library = new java.io.File(dir, name);
            if (!library.isFile()) continue;
            try {
                java.io.File copy = java.io.File.createTempFile("rexterm", name);
                copy.deleteOnExit();
                java.nio.file.Files.copy(library.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.load(copy.getAbsolutePath());
                return;
            } catch (java.io.IOException io) {
                throw new UnsatisfiedLinkError(message + "; and no copy could be made: " + io);
            }
        }
        throw e;
    }

    private Ghostty() {
    }

    // Keys for getLong.
    static final int COLUMNS = 0;
    static final int ROWS = 1;
    static final int CURSOR_X = 2;
    static final int CURSOR_Y = 3;
    static final int CURSOR_VISIBLE = 4;
    /** One of the GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE values: bar 0, block 1, underline 2, hollow block 3. */
    static final int CURSOR_STYLE = 5;
    static final int CURSOR_BLINKING = 6;
    static final int ALTERNATE_SCREEN = 7;
    static final int MOUSE_TRACKING = 8;
    static final int SCROLLBACK_ROWS = 9;
    static final int KITTY_KEYBOARD_FLAGS = 10;
    static final int RESIDENT_BYTES = 11;

    // Events from takeEvents.
    static final int EVENT_BELL = 1;
    static final int EVENT_TITLE = 2;

    // What decoderFeed has seen arrive.
    static final int SNAPSHOT_FAILED = -1;
    static final int SNAPSHOT_PARTIAL = 0;
    static final int SNAPSHOT_READY = 1;
    static final int SNAPSHOT_COMPLETE = 2;

    // decoderNext's answers besides a row count.
    static final int SNAPSHOT_FINISHED = -1;
    static final int SNAPSHOT_ERROR = -2;

    static native long newTerminal(int columns, int rows, int scrollback);

    static native void free(long terminal);

    static native void write(long terminal, byte[] data, int offset, int length);

    static native int takeEvents(long terminal);

    /** Rows moved into scrollback since the last call, or -1 if that can't be told. */
    static native int takeScrolled(long terminal);

    static native void resize(long terminal, int columns, int rows);

    static native void reset(long terminal);

    static native long getLong(long terminal, int key);

    static native boolean mode(long terminal, int number, boolean ansi);

    static native long compressionActivity(long terminal);

    static native boolean compress(long terminal, boolean full);

    static native String title(long terminal);

    static native boolean changedColors(long terminal, int[] out);

    /** See rexterm.c: the bytes a press of {@code key} ({@link Keys}) sends with {@code mods} held, null for none. */
    static native byte[] encodeKey(long terminal, int key, int mods, int consumedMods, int text, int unshifted);

    /** See rexterm.c: the text of rows [first, first + count), with each column's start and style, and each row's wrap. */
    static native char[] readRows(long terminal, int first, int count, int[] starts, long[] styles, boolean[] wraps);

    static native long decoderNew();

    static native void decoderFree(long decoder);

    static native int decoderFeed(long decoder, byte[] data, int offset, int length);

    static native long decoderReady(long decoder, int scrollback);

    static native int decoderNext(long decoder, int maxPages);

    /** The bytes through READY, 0 until they arrive. */
    static native long decoderReadyBytes(long decoder);
}
