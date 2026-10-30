# Terminal module

The Java sources in this module come from the `terminal-emulator` and `terminal-view` libraries of
[termux-app v0.118.1](https://github.com/termux/termux-app/tree/v0.118.1), which are released under
the Apache License 2.0 (they derive from jackpal's Android-Terminal-Emulator). They have moved from
Termux's packages, `com.termux.terminal` and `com.termux.view`, to
`com.jeffarese.rexmobile.terminal.emulator` and `com.jeffarese.rexmobile.terminal.view`.

Changes from upstream:

- The emulator is libghostty-vt from [Ghostty](https://github.com/ghostty-org/ghostty) (MIT), at
  the commit in `native/GHOSTTY_COMMIT`, the one Rex servers run. `TerminalEmulator`,
  `TerminalBuffer` and `TerminalRow` keep Termux's API for the view and the app, and read rows,
  styles and modes from it through the JNI bridge in `src/main/cpp/rexterm.c`. Termux's parser,
  `ByteQueue`, `WcWidth` and `Logger` are removed: cell widths and grapheme clusters come from
  Ghostty, so the renderer and selection draw what the server's terminal holds.
  `SnapshotDecoder` restores a terminal from the snapshot a Rex stream starts with.
  `scripts/build-libghostty.sh` builds `libghostty-vt` and the bridge; `native/ENGINE-LICENSES.txt`
  holds the licenses of Ghostty and what is built into it.
- Keys are encoded by libghostty-vt's key encoder, set from the terminal's modes, so they reach
  programs in the form they asked for, the Kitty keyboard protocol included. Termux's `KeyHandler`
  and the view's own Ctrl and Alt mapping are removed; `Keys` maps Android's key codes to Ghostty's.
- `TextStyle` carries the underline's style (double, curly, dotted, dashed) and overline, which
  `TerminalRenderer` draws.
- `TerminalSession` no longer starts a local process through JNI. It is fed output from a Rex
  server and sends typed input back through a `RemoteInput`, and the remote terminal decides the
  size. Termux's `JNI.java` and its native process code are removed.
- The emulator tracks synchronized updates (mode 2026), and `TerminalSession` holds the screen
  update until one ends, so a frame split across network messages is never drawn half done.
- `TerminalBuffer` copies only the rows Ghostty marks changed since the last frame, into buffers it
  keeps, and holds only the last 512 rows of history read for the view.
- `TerminalView` tells a `Watcher` when its window is shown or hidden and when it scrolls from
  history back to the screen, so output nobody sees can wait and history read for it can be
  compressed again. Once the app takes back gestures itself (predictive back), Android sends it no
  back key, so a selection ends on Back through Android's back callback instead.

What stays from Termux is the Android side: `TerminalView` and its gestures, scrolling and soft
keyboard connection, `TerminalRenderer`, which draws the cells on a `Canvas`, and text selection.
libghostty-vt holds a terminal's state and hands it to a renderer of the app's own. Ghostty's
renderers are part of the Ghostty app: Metal on macOS, and OpenGL 4.3 on Linux, which Android's
OpenGL ES isn't.
