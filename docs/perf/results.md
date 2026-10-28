# Terminal results

How the phone's terminal, libghostty-vt, performs. Measured on 28 October 2026 with the harness in
[README.md](README.md), which says how to run each benchmark.

## Environment

| | |
|---|---|
| App | 0.3.0 |
| Engine | libghostty-vt at Ghostty `f54340ad`, the Rex server's commit; Zig 0.17.0, `ReleaseFast`; NDK 30.0.16248370, API 26, arm64-v8a |
| Rex server | `0.0.1793124232-gee4f8c`, API revision 7, on the Mac below |
| Mac | Apple M5 Max, 18 cores, 64 GB, macOS 26.7.1 |
| Live benchmarks | Robolectric (SDK 34) on the Mac's JDK 21, to the Mac's server over Tailscale, with the Mac's build of the same library |
| Emulator | AVD `rexmobile-test`: google_apis arm64-v8a, API 35, 4 vCPUs, 2 GB, 1080×2400 at 420 dpi, headless; app heap limit 192 MB |
| Engine benchmarks | `benchmark` build type (not debuggable, so compiled as in a release) |

The live benchmarks run on the Mac's JVM, so their times leave out the phone's CPU; the engine
benchmarks on the emulator give the phone-side costs.

## 1. Opening a pane

A 160×48 block, its scrollback numbered 150-column lines of random base64 (about the worst case for
compression). The link's speed is shared by the data and control connections, as on a phone.
Medians of 3 runs, wall-clock ms. Input opens with the first screen in every case.

| Scrollback | Link | First screen | Fully loaded | History on the phone |
|---|---|---:|---:|---:|
| fresh shell | unlimited | 27 | 27 | – |
| fresh shell | 512 KB/s | 55 | 55 | – |
| fresh shell | 48 KB/s | 229 | 229 | – |
| 2,000 lines | unlimited | 38 | 54 | 1,986 rows |
| 2,000 lines | 512 KB/s | 183 | 730 | 1,986 rows |
| 2,000 lines | 48 KB/s | 1,442 | 6,377 | 1,986 rows |
| 14,000 lines | unlimited | 53 | 123 | 3,888 rows |
| 14,000 lines | 512 KB/s | 196 | 4,675 | 3,888 rows |
| 14,000 lines | 48 KB/s | 1,479 | 43,529 | 3,888 rows |

- **The first screen doesn't depend on the history.** A fresh stream restores the server's own
  Ghostty snapshot, which holds the screen before the history, and the phone shows it as soon as
  that part is in: 13 KB of 2.1 MB for 14,000 lines. Then the history follows.
- **Fully loaded is the link's speed.** At 48 KB/s, 14,000 lines take as long as the stream's
  1.64 MB takes to arrive. The phone receives 1.66 MB in all.
- **The screen at 48 KB/s takes about 1.3 s, whether it needs 12 KB or 38 KB.** That points to the
  size of the server's first snapshot message (about 60 KB) rather than to the screen. A smaller
  first message could bring it to a few hundred ms.
- The phone keeps up to 4,000 rows. Ghostty drops the oldest history a page at a time, so it holds
  between one page under that and the limit.

## 2. Fidelity

An 80×30 corpus of hard cases: emoji sequences, flags, wide characters at the right edge,
combining marks, tabs, line drawing, DEC graphics, a scroll region, insert and delete, REP, a
hyperlink, underline styles and colors. The phone's rows are compared with the server's, live and
after a fresh stream.

| | Live | After a fresh stream |
|---|---:|---:|
| Rows that differ, of 26 | 0 | 0 |
| Probe styles that differ, of 9 | 2 | 2 |
| Modes that differ, of 12, and the Kitty keyboard flags | 0 | 0 |

Curly, double, dotted and dashed underlines, overline, truecolor, 256 colors, bold, italic and
strikethrough all match. The two that differ are an underline's own color (SGR 58) and an OSC 8
hyperlink: the engine keeps both, but the view doesn't draw them yet.

## 3. On the phone

### Parsing

4 MB fed in 16 KB chunks into a 100×40 terminal with 4,000 rows of history. Median of 3 rounds of
7 passes, with the range.

| Output | MB/s |
|---|---:|
| Plain ASCII lines | 123.8 (122.9–190.9) |
| SGR-heavy (256-color, truecolor) | 83.3 (75.0–111.6) |
| Unicode (wide, emoji, combining) | 100.1 (79.8–148.4) |
| Full-screen redraws in synchronized updates | 99.6 (71.7–128.2) |

Unicode-heavy output parses about as fast as the rest.

### Drawing

A 60×40 screen drawn into a 1080×2400 software bitmap, 500 frames a round, 3 rounds.

| Screen | Median | p95 |
|---|---:|---:|
| Styled, like Claude Code's | 1.08 ms | 1.21 ms |
| Plain text | 0.82 ms | 0.89 ms |

Well under 2 ms of a 16 ms frame. Rows are read from the engine only after they change.

### Restoring a snapshot

Snapshots the server wrote, 160×48, restored as the app does: the screen, then the history up to
the 4,000-row limit, compressed as it goes. Medians of 15.

| Snapshot | Screen | History |
|---|---:|---:|
| 2,000 lines (303 KB) | 0.08 ms | 3.2 ms |
| 14,000 lines (2.1 MB) | 0.12 ms | 6.7 ms |

### Memory held by history

150 columns, a 40-row screen. The engine's own count of the pages it maps, as filled and once the
history is compressed. None of it is on the Java heap, so a long history adds nothing to garbage
collection.

| History rows | Lines | As filled | Compressed |
|---:|---|---:|---:|
| 4,000 | plain | 5.62 MB | 2.48 MB |
| 4,000 | a color every 10 characters | 7.30 MB | 3.72 MB |
| 14,000 | plain | 18.5 MB | 6.67 MB |
| 14,000 | a color every 10 characters | 24.2 MB | 10.5 MB |

Random base64 is close to the worst case for compression; ordinary terminal text should do better.

## 4. Size

| | Bytes |
|---|---:|
| `app-release.apk` | 11,513,762 |
| Download (apkanalyzer) | 6,746,735 |
| `lib/arm64-v8a/librexterm.so`, stored uncompressed and mapped from the APK | 2,129,256 |

The library is stripped and exports only its JNI functions; its size is Ghostty's code and Unicode
tables, built for speed.

## Not yet

- Underline colors and OSC 8 hyperlinks are kept by the engine but not drawn.
- Kitty graphics images are kept by the engine but not drawn.
- The app encodes its own keys, not with Ghostty's key encoder, so the Kitty keyboard flags it
  tracks don't change what it sends.
- arm64-v8a only.
