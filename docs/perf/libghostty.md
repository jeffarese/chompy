# libghostty-vt, against Termux

Measured on 28 October 2026, after the move from Termux's terminal emulator to libghostty-vt, with
the same benchmarks, server and machines as [the Termux baseline](baseline-termux.md). How to run
each is in [README.md](README.md).

## Environment

| | |
|---|---|
| App | branch `libghostty` from `30a445d`, 0.3.0 (uncommitted at the time) |
| Engine | libghostty-vt at Ghostty `f54340ad`, the Rex server's commit; Zig 0.17.0, `ReleaseFast`; NDK 30.0.16248370, API 26, arm64-v8a |
| Rex server | `0.0.1793124232-gee4f8c`, commit `ee4f8ca`, API revision 7, on this Mac (the baseline's) |
| Mac, live benchmarks, emulator | as in the baseline |

The live benchmarks load the Mac's build of the same library (`terminal/native/host/librexterm.dylib`)
into Robolectric, so their times still leave out the phone's CPU.

## In short

| | Termux | libghostty-vt |
|---|---:|---:|
| First screen, 14,000 lines, shared 48 KB/s link | 30.9 s | **1.5 s** |
| First screen, 2,000 lines, shared 48 KB/s link | 16.7 s | **1.4 s** |
| Loaded, 14,000 lines, shared 48 KB/s link | 72.9 s | **43.5 s** |
| Received to load 14,000 lines, shared 48 KB/s link | 3.06 MB | **1.66 MB** |
| Fidelity: rows that differ, live / after a fresh stream | 1 / 0 of 26 | **0 / 0** |
| Fidelity: probe styles that differ, live / fresh | 5 / 5 of 9 | **2 / 2** |
| Fidelity: modes that differ | 1, Kitty flags not tracked | **0**, Kitty flags match |
| Parse Unicode-heavy output, on the emulator | 1.8 MB/s | **100 MB/s** |
| Parse plain ASCII, on the emulator | 49 MB/s | **124 MB/s** |
| Draw a styled 60×40 frame, on the emulator | 0.87 ms | 1.08 ms |
| 14,000 rows of history, 150 columns | 21.9 MB of Java heap | **18.5 MB native, 6.7 MB compressed** |
| APK download size | 5.88 MB | 6.75 MB |

## 1. Load of a fresh stream

The baseline's matrix: a 160×48 block, numbered 150-column lines of random base64, the link's
speed shared by the data and control connections, medians of 3 runs (the baseline's 14,000 lines at
48 KB/s ran once). Each cell is Termux → libghostty-vt.

| Scrollback | Link | First right screen | Loaded | Control bytes | Phone history rows | Oldest line on phone |
|---|---|---:|---:|---:|---:|---:|
| fresh shell | unlimited | 57 → 27 | 57 → 27 | 3,502 → 465 | 0 | – |
| fresh shell | 512 KB/s | 88 → 55 | 88 → 55 | 3,502 → 464 | 0 | – |
| fresh shell | 48 KB/s | 364 → 229 | 364 → 229 | 3,502 → 466 | 0 | – |
| 2,000 lines | unlimited | 98 → 38 | 98 → 54 | 236,026 → 464 | 1,975 → 1,986 | L000001 |
| 2,000 lines | 512 KB/s | 1,437 → 183 | 1,437 → 730 | 236,029 → 465 | 1,975 → 1,986 | L000001 |
| 2,000 lines | 48 KB/s | 16,707 → 1,442 | 16,707 → 6,377 | 471,924 → 466 | 1,975 → 1,986 | L000001 |
| 14,000 lines | unlimited | 170 → 53 | 170 → 123 | 707,940 → 467 | 3,952 → 3,888 | L010006 → L010070 |
| 14,000 lines | 512 KB/s | 3,983 → 196 | 7,495 → 4,675 | 1,415,762 → 467 | 3,952 → 3,888 | L010006 → L010070 |
| 14,000 lines | 48 KB/s | 30,861 → 1,479 | 72,902 → 43,529 | 1,417,245 → 2,487 | 3,952 → 3,888 | L010006 → L010070 |

Input opens with the first screen in every cell, for both engines. The data connection carries
the same stream either way (243 KB and 1.64 MB, within about 1 %; a fresh shell's 7.1 KB against
8.2 KB); what changed is that the phone no longer reads the screen again over the control
connection. "Loaded" now also waits for the restored history to be all there.

`RemoteTerminal.lastLoad` from the median run (on Robolectric's clock, as in the baseline):

| Scrollback | Link | Shown | Snapshot |
|---|---|---|---|
| fresh shell | 48 KB/s | in 76 ms, after 9 KB | 9 KB in 76 ms, restored |
| 2,000 lines | unlimited | in 10 ms, after 39 KB | 317 KB in 10 ms, restored |
| 2,000 lines | 48 KB/s | in 1,259 ms, after 39 KB | 317 KB in 5,987 ms, restored |
| 14,000 lines | unlimited | in 27 ms, after 13 KB | 2,106 KB in 71 ms, restored |
| 14,000 lines | 512 KB/s | in 161 ms, after 13 KB | 2,106 KB in 4,431 ms, restored |
| 14,000 lines | 48 KB/s | in 1,330 ms, after 13 KB | 2,106 KB in 42,000 ms, restored |

### Only the stream throttled

At 48 KB/s with the control connection unthrottled (`BENCH_THROTTLE=data`, 3 runs each):

| Scrollback | First right screen | Loaded | Control bytes | Phone history rows |
|---|---:|---:|---:|---:|
| 2,000 lines | 1,243 → 1,472 | 6,472 → 6,371 | 474,421 → 466 | 1,975 → 1,978 |
| 14,000 lines | 1,339 → 1,479 | 43,601 → 43,142 | 1,415,745 → 2,622 | 3,952 → 3,884 |

This is the one case where Termux's way was quicker to a first screen, by 140–230 ms: with a fast
control connection, reading the screen on the side beats waiting for the stream. libghostty-vt
doesn't read on the side when the snapshot is one it can read, since on a shared link (the phone's
case) that read only slows the snapshot down, as the baseline's 16.7 s shows.

### What stands out

- The first screen no longer depends on the scrollback: about 1.4–1.5 s at 48 KB/s, 0.2 s at
  512 KB/s, whatever the history, where Termux's grew with it (16.7 s and 30.9 s at 48 KB/s). The
  snapshot holds the screen before the history, and the phone shows it as soon as that part is in.
- At 48 KB/s the screen takes 1.3 s whether it needs 12 KB of the snapshot or 38 KB. That points
  to how Rex sends the snapshot, in messages larger than the screen (1.3 s at 48 KB/s is about
  60 KB), rather than to the screen: a smaller first message from Rex could bring it down to a few
  hundred ms on a slow link.
- Loading 14,000 lines at 48 KB/s now takes as long as the stream's 1.64 MB takes to arrive
  (43 s), down from 73 s; the phone receives 1.66 MB, down from 3.06 MB.
- The phone keeps 3,888 rows of 14,000 lines, 64 fewer than Termux: Ghostty drops the oldest history
  a page at a time (about 300 rows at 160 columns), so it holds between one page under the limit
  and the limit.

## 2. Fidelity

The same 80×30 corpus, compared with the server's `format` text and HTML.

### Text, row by row (26 rows)

| | Termux | libghostty-vt |
|---|---:|---:|
| Live | 1 | 0 |
| After a fresh stream | 0 | 0 |

The flags row, which Termux drew 6 columns short, now matches.

### Styles of 9 probe cells

| Probe | Server | Termux, live / fresh | libghostty-vt, live and fresh |
|---|---|---|---|
| curly underline | curly | none / none | curly |
| double underline | double | none / none | double |
| red single underline | single, #ff0000 | single, no color / none | single, no color |
| dotted underline | dotted | none / none | dotted |
| OSC 8 hyperlink | https://example.com/bench | none / none | none |
| truecolor, 256-color, bold/italic/strike | | match | match |

2 of 9 differ, against Termux's 5. The engine holds the underline color and the hyperlink, but the
view's cell style has no room for them, so neither is drawn yet.

### Modes

| Mode | Server | Termux | libghostty-vt |
|---|---|---|---|
| 1000 mouse normal | on | **off** | on |
| 1003 mouse any | off | not tracked | off |
| Kitty keyboard flags | 1 | not tracked | 1 |
| 1, 7, 25, 66, 1002, 1004, 1006, 1049, 2004, 2026 | | match | match |

All 12 modes and the Kitty flags match, live and after a fresh stream.

## 3. Engine on the emulator

The emulator's speed depends on how busy the Mac is, and it was busier than for the baseline:
Termux parsed plain ASCII at 49 MB/s here against the baseline's 86. So both engines ran on the same
boot, in turn: libghostty-vt, Termux, three rounds each, each round a median of 7 passes (parse) or
500 frames (render). Compare the two columns, not this with the baseline.

### Parse throughput

The baseline's corpora: 4 MB fed in 16 KB chunks into a 100×40 terminal with 4,000 rows of history.
Median of the 3 rounds, with their range.

| Corpus | Termux MB/s | libghostty-vt MB/s | |
|---|---:|---:|---:|
| Plain ASCII lines | 49.3 (35.8–55.2) | 123.8 (122.9–190.9) | 2.5× |
| SGR-heavy (256-color, truecolor) | 56.8 (46.2–62.3) | 83.3 (75.0–111.6) | 1.5× |
| Unicode (wide, emoji, combining) | 1.79 (1.77–1.84) | 100.1 (79.8–148.4) | 56× |
| Full-screen redraw in synchronized updates | 63.8 (59.6–70.3) | 99.6 (71.7–128.2) | 1.6× |

Termux's 37-fold slowdown on Unicode output is gone: libghostty-vt parses it about as fast as the rest.

### Render

The same 60×40 screens drawn into a 1080×2400 software bitmap. Median of the 3 rounds' medians,
and of their p95s.

| Screen | Termux median ms | libghostty-vt median ms | Termux p95 | libghostty-vt p95 |
|---|---:|---:|---:|---:|
| Styled, like Claude Code's | 0.87 | 1.08 | 0.98 | 1.21 |
| Plain text | 0.70 | 0.82 | 0.76 | 0.89 |

Drawing is 0.1–0.2 ms a frame slower, at the edge of the run-to-run spread (libghostty-vt's styled
rounds were 0.79, 1.18 and 1.08 ms; Termux's 0.88, 0.87 and 0.72). The view draws through the same
Termux renderer, which now looks up each cell's width and start in the row it read from the engine,
where it used to look characters up in Termux's width table. Rows are read from the engine only
after they change, not every frame. Either way a frame is well under 2 ms of a 16 ms budget.

### Restoring a snapshot

A small C program on the emulator restored two snapshots the server wrote, 160×48, as the app
does: the screen, then history pages until the history is all there or at the 4,000-row limit,
compressing the history as it goes. Medians of 15.

| Snapshot | Screen | History |
|---|---:|---:|
| 2,000 lines (303 KB) | 0.08 ms | 3.2 ms, 1,953 rows |
| 14,000 lines (2.1 MB) | 0.12 ms | 6.7 ms, to the limit |

The app adds all of the history the moment the snapshot ends, before the output or resize that
follows it in the stream: libghostty-vt can't add older pages above a terminal resized since, and
would add them back above one whose program cleared its history. The limit is counted on the
primary screen, so history under a fullscreen program is restored in full too.

### Memory held by history (150 columns, 40-row screen)

For Termux, the Java heap after filling, less before; it holds nothing outside it. For
libghostty-vt, the engine's own count of the physical memory its pages take (what Ghostty says to
budget against), as filled and after compressing all the history it can. Its Java heap didn't grow
(it shrank by 0.6–4.4 MB as garbage collection settled), and its native heap grew by under 40 KB:
the pages are memory it maps itself.

| History rows | Lines | Termux | libghostty-vt | Compressed |
|---:|---|---:|---:|---:|
| 4,000 | plain | 6.37 MB | 5.62 MB | 2.48 MB |
| 4,000 | a color every 10 characters | 5.70 MB | 7.30 MB | 3.72 MB |
| 14,000 | plain | 21.9 MB | 18.5 MB | 6.67 MB |
| 14,000 | a color every 10 characters | 19.6 MB | 24.2 MB | 10.5 MB |

Uncompressed, libghostty-vt takes about what Termux did: 1.3–1.4 KB a row plain and 1.7–1.8 KB
styled, against Termux's 1.6 KB and 1.4 KB. Compressed, a third to a half of that. Random base64 is about the worst
case for compression; ordinary terminal text should compress further, but that wasn't measured.
The bigger change is where the memory is: none of it is on the Java heap (192 MB on this emulator),
so a long history no longer adds to garbage collection, and the app's 4,000-row limit
(`TRANSCRIPT_ROWS`) could go up.

The benchmark also records the process's PSS. For the first case it agrees with the engine's count
(5.95 MB filled, 2.38 MB compressed), but later cases come out lower than the engine's count, even
below what random base64 can compress to, because PSS also takes in the Java heap shrinking during
the run. So it isn't used here.

## 4. APK size

`./gradlew :app:assembleRelease`, as in the baseline.

| | Termux | libghostty-vt |
|---|---:|---:|
| `app-release.apk` | 9,442,869 | 11,513,762 |
| Download size (apkanalyzer) | 5,877,279 | 6,746,735 |
| `classes.dex` | 3,362,052 | 3,336,504 |
| `com.termux` code in the dex | 58,639 | 32,373 |
| of which `com.termux.terminal` | 37,374 | 8,534 |
| of which `com.termux.view` | 21,265 | 23,839 |
| `lib/arm64-v8a/librexterm.so` | – | 2,129,256 |

The APK grows by 2.07 MB, its download by 0.87 MB. The native library is stored uncompressed, so
Android maps it straight from the APK (the default from API 23; the app's minimum is 26), and
compresses to about 0.89 MB for download. It is stripped and exports only its 20 JNI functions; the 2.0 MB is
Ghostty's code and its Unicode tables, built for speed (`ReleaseFast`). A `ReleaseSmall` build
would be smaller, at some cost in speed; that wasn't tried. The APK now carries arm64-v8a only,
which also drops the other ABIs of the one native library it had before.

## What changed, besides the numbers

What the move gives that the benchmarks above don't show:

- **The same terminal as the server.** The phone runs libghostty-vt at the commit the Rex server
  runs. Cell widths, grapheme clusters, wrapping, modes and the scrollback all follow the same code,
  so what the phone draws is what the server holds, rather than Termux's guess at it (the flags row
  in the fidelity test, mode 1000, the Kitty keyboard flags).
- **No redraw, so no redraw logic.** A fresh stream no longer waits for the stream to go quiet, asks
  for `terminal.format`, reads its tail in parts, joins the parts at a line and replays them. That
  path stays only as the fallback for a snapshot the phone can't read.
- **A fallback for a newer Rex.** The snapshot format has no compatibility guarantee yet. The phone
  checks the magic from the first byte and the format version from the tenth, and Ghostty skips
  record kinds it doesn't know. A snapshot it still can't read, or whose screen is damaged, is
  redrawn the old way, with the early screen read on the side, so a Rex update can't leave a tab on
  "Loading…"; damaged history keeps the screen and the history read so far. Unit tests cover another
  format, another version, a damaged screen and damaged history.
- **History arrives compressed.** History restored from a snapshot is compressed as it is restored,
  and history from live output is compressed a step at a time once the stream has been quiet for
  2 s. Compressed rows are decompressed when read, as when scrolling back to them.
- **The screen first.** A snapshot holds the screen before the history, and the phone shows it as
  soon as that part has arrived (39 KB of 317 KB for 2,000 lines, 13 KB of 2.1 MB for 14,000), then
  adds the history once the rest has.
- **Underline styles and overline.** Double, curly, dotted and dashed underlines and overline are
  drawn; the probed styles match the server live and after a fresh stream.
- **What a program set survives a fresh stream.** Modes, the Kitty keyboard flags, the title, the
  colors a program set (OSC 4/10/11) and the cursor come back with the snapshot (unit-tested on a
  fixture). A program's colors stay over the app's theme, and go back to the theme when the program
  resets them.
- **Upstream maintenance.** Termux's emulator was vendored at v0.118.1 and changed here; Ghostty's is
  maintained, and moving to a new commit is a script (`scripts/build-libghostty.sh`).
- **Less code.** The terminal module's Java lost 3,848 lines (Termux's parser, buffer, width table
  and helpers) and gained 514, which keep Termux's API for the view and the app over a 645-line JNI
  bridge.

What it doesn't do yet:

- Underline colors (SGR 58) and OSC 8 hyperlinks are kept by the engine but not drawn; the view's
  style long has no room for them.
- Kitty graphics images are kept by the engine (built with them, as by default) but not drawn.
- The app's own keys are still encoded by Termux's `KeyHandler`, not Ghostty's key encoder, so the
  Kitty keyboard flags the phone now tracks don't change what it sends.
- The APK carries the native library for arm64-v8a only. A 32-bit or x86 phone can't install it.
