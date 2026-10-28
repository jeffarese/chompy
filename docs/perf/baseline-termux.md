# Baseline: Termux engine

Measured on 28 October 2026, before the move to libghostty-vt. How to run each benchmark is in
[README.md](README.md); the raw JSON is in `app/build/bench/` after a run.

## Environment

| | |
|---|---|
| App | git `30a445d` (0.2.18), with the benchmark harness added (uncommitted at the time) |
| Rex server | `0.0.1793124232-gee4f8c`, commit `ee4f8ca`, darwin_arm64, API revision 7, on this Mac |
| Mac | `Mac17,7`, Apple M5 Max, 18 cores, 64 GB, macOS 26.7.1 |
| Live benchmarks | Robolectric (SDK 34) on the Mac's JDK 21, to a Rex server on the same Mac, over Tailscale |
| Emulator | AVD `rexmobile-test`: google_apis arm64-v8a, API 35, 4 vCPUs, 2 GB, 1080×2400 at 420 dpi; emulator 37.2.12.0, headless, cold boot; app heap limit 192 MB |
| Engine benchmarks | `benchmark` build type (debug, not debuggable) |

The live benchmarks run on the Mac's JVM, so their times leave out the phone's CPU. The engine
benchmarks give the phone-side costs.

## 1. Load of a fresh stream

A 160×48 block, scrollback of numbered 150-column lines of random base64. The link's speed is
shared by the data and control connections. Medians of 3 runs, except 14,000 lines at 48 KB/s,
which ran once (its first run took 73 s). Times are wall-clock ms from creating `RemoteTerminal`.
Bytes are received on the wire, TLS included.

| Scrollback | Link | First right screen | Input open | Loaded | Data bytes | Control bytes | Phone history rows | Oldest line on phone |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| fresh shell | unlimited | 57 | 57 | 57 | 8,173 | 3,502 | 0 | – |
| fresh shell | 512 KB/s | 88 | 88 | 88 | 8,173 | 3,502 | 0 | – |
| fresh shell | 48 KB/s | 364 | 364 | 364 | 8,173 | 3,502 | 0 | – |
| 2,000 lines | unlimited | 98 | 98 | 98 | 242,630 | 236,026 | 1,975 | L000001 |
| 2,000 lines | 512 KB/s | 1,437 | 1,437 | 1,437 | 242,630 | 236,029 | 1,975 | L000001 |
| 2,000 lines | 48 KB/s | 16,707 | 16,707 | 16,707 | 242,630 | 471,924 | 1,975 | L000001 |
| 14,000 lines | unlimited | 170 | 170 | 170 | 1,641,334 | 707,940 | 3,952 | L010006 |
| 14,000 lines | 512 KB/s | 3,983 | 3,983 | 7,495 | 1,641,333 | 1,415,762 | 3,952 | L010006 |
| 14,000 lines | 48 KB/s (1 run) | 30,861 | 30,861 | 72,902 | 1,641,333 | 1,417,245 | 3,952 | L010006 |

The server held every line in each case (oldest L000001): 2,000 lines are 311 KB as `vt`, 14,000
lines 2.13 MB. The phone keeps 4,000 rows (`TRANSCRIPT_ROWS`), so it drops the oldest 10,005 of 14,000.

`RemoteTerminal.lastLoad` from the median run:

| Scrollback | Link | Shown early | Snapshot | Redraw (`format`) |
|---|---|---:|---|---|
| fresh shell | unlimited | – | 12 KB in 0 ms | 12k characters in 12 ms |
| fresh shell | 48 KB/s | – | 12 KB in 96 ms | 12k characters in 101 ms |
| 2,000 lines | unlimited | – | 307 KB in 10 ms | 311k characters in 46 ms |
| 2,000 lines | 512 KB/s | – | 307 KB in 650 ms | 311k characters in 672 ms |
| 2,000 lines | 48 KB/s | – | 307 KB in 9,160 ms | 311k characters in 6,867 ms |
| 14,000 lines | unlimited | – | 2,101 KB in 56 ms | 653k characters in 80 ms |
| 14,000 lines | 512 KB/s | 3,790 ms | 2,101 KB in 5,194 ms | 653k characters in 1,991 ms |
| 14,000 lines | 48 KB/s | 29,754 ms | 2,101 KB in 52,356 ms | 653k characters in 18,195 ms |

`lastLoad` runs on Robolectric's clock, which the harness moves with real time in 10 ms steps.
Work done in one go doesn't move it, so its `drawn in 0 ms` means nothing here.

### Only the stream throttled

The same at 48 KB/s with the control connection unthrottled, as `LiveEarlyScreenTest` does
(`BENCH_THROTTLE=data`, 3 runs each):

| Scrollback | First right screen | Input open | Loaded | Data bytes | Control bytes | Snapshot | Redraw |
|---|---:|---:|---:|---:|---:|---|---|
| 2,000 lines | 1,243 | 1,243 | 6,472 | 243,225 | 474,421 | 310 KB in 6,093 ms | 36 ms |
| 14,000 lines | 1,339 | 1,339 | 43,601 | 1,642,102 | 1,415,745 | 2,103 KB in 42,008 ms | 119 ms |

### What stands out

- On a shared slow link the early screen loses to the snapshot it is meant to beat. At 48 KB/s
  with 2,000 lines it never shows: its read on the control connection is still going when the
  snapshot ends, so the redraw reads the same 300 KB again, and nothing shows for 16.7 s. With only
  the stream throttled it shows in 1.2 s.
- With 14,000 lines on a shared 48 KB/s link the screen shows after 31 s and loads after 73 s.
  The phone receives 3.06 MB: the 1.64 MB snapshot it throws away, and the 0.7 MB tail of the
  `format` text twice (the early screen, then the redraw).
- The snapshot is 2.1 MB for 14,000 lines, 1.64 MB on the wire: random base64 compresses only to
  78 %, by design.

## 2. Fidelity

An 80×30 block. The corpus is printed on a live stream, so the phone's own parser draws it; then a
fresh stream redraws it from `format`.

### Text, row by row (26 rows)

| | Rows that differ |
|---|---:|
| Live | 1 |
| After a fresh stream | 0 |

The live difference is the flags row: the phone gives each regional-indicator flag fewer columns
than the server, so the bar placed at column 60 has 6 more spaces before it on the phone:

```
phone:  flags: 🇯🇵 🇪🇸 🇺🇸 |                                          |
server: flags: 🇯🇵 🇪🇸 🇺🇸 |                                    |
```

After a fresh stream the text matches, likely because the redraw writes the padding as spaces, so
the phone's bar moves instead. Plain-text comparison can't see a column shift that the text
doesn't carry: emoji ZWJ sequences, skin tones, VS16, combining marks, CJK, the wide character
wrapping at the right edge, tabs, line drawing, DEC graphics, the scroll region, insert and delete
line and character, and REP all match as text.

### Styles of 9 probe cells, against the server's HTML

| Probe | Server | Phone, live | Phone, fresh stream |
|---|---|---|---|
| `CSI 4:3 m` curly underline | curly | none | none |
| `CSI 4:2 m` double underline | double | none | none |
| `CSI 58:2::255:0:0 m` + `CSI 4 m` | single, color #ff0000 | single, no color | none |
| `CSI 4:4 m` dotted underline | dotted | none | none |
| OSC 8 hyperlink | https://example.com/bench | none | none |
| truecolor foreground, background | #ff7800, #000080 | same | same |
| 256-color 196 | palette 196 | same | same |
| bold, italic, strikethrough | all three | same | same |

5 of 9 differ live, and 5 after a fresh stream; the red underline loses its underline too after a
fresh stream.

### Modes, from `com.superlogical.terminal.modes`

A program sets 1000, 1002, 1006, 2004, 1, keypad mode (`ESC =`) and Kitty keyboard flags 1, then
runs `cat`. Same result live and after a fresh stream:

| Mode | Server | Phone |
|---|---|---|
| 1 cursor keys | on | on |
| 66 keypad keys | on | on |
| 1000 mouse normal | on | **off** (Termux turns 1000 off when 1002 is set; the server lists both) |
| 1002 mouse button | on | on |
| 1003 mouse any | off | not tracked |
| 1004 focus events | off | off |
| 1006 SGR mouse | on | on |
| 2004 bracketed paste | on | on |
| 7, 25, 1049, 2026 | match | match |
| Kitty keyboard flags | 1 | not tracked |

## 3. Engine on the emulator

### Parse throughput

4 MB corpora fed in 16 KB chunks into a 100×40 terminal with 4,000 rows of history; 2 warm-up
passes, then median of 7. MB is 10^6 bytes.

| Corpus | Median MB/s | Min | Max |
|---|---:|---:|---:|
| Plain ASCII lines | 85.7 | 81.7 | 88.0 |
| SGR-heavy (256-color, truecolor) | 86.7 | 75.6 | 89.4 |
| Unicode (wide, emoji, combining) | **2.17** | 2.16 | 2.18 |
| Full-screen redraw in synchronized updates | 78.5 | 77.5 | 79.8 |

A second run on a fresh boot gave 83.0, 80.3, 2.17 and 78.4. Unicode output is about 37 times slower
than the rest; why wasn't looked into (TerminalRow's handling of characters wider or longer than one
`char` is a guess).

### Render

A 60×40 screen drawn with `TerminalRenderer` into a 1080×2400 software bitmap, text 30 px (cells
18×40 px) in the app's terminal font; 100 warm-up frames, then 500.

| Screen | Median ms | p95 ms | Max ms |
|---|---:|---:|---:|
| Styled, like Claude Code's | 0.65 | 0.70 | 0.82 |
| Plain text | 0.54 | 0.60 | 0.65 |

### Memory held by history (150 columns, 40-row screen)

The Java heap and native heap after filling, less before, once garbage collection settled.

| History rows | Lines | Java heap | Native heap | Per row |
|---:|---|---:|---:|---:|
| 4,000 | plain | 6.36 MB | 0 | 1,573 B |
| 4,000 | a color every 10 characters | 5.70 MB | 0 | 1,410 B |
| 14,000 | plain | 21.9 MB | 0 | 1,560 B |
| 14,000 | a color every 10 characters | 19.6 MB | 0 | 1,398 B |

All of Termux's memory is Java heap: about 1.5 KB a row at 150 columns, whatever the styling.

## 4. APK size

`./gradlew :app:assembleRelease`, R8 on, resources shrunk:

| | Bytes |
|---|---:|
| `app-release.apk` | 9,442,869 |
| Download size (apkanalyzer) | 5,877,279 |
| `classes.dex` | 3,362,052 |
| `com.termux` code in the dex | 58,639 |
| of which `com.termux.terminal` | 37,374 |
| of which `com.termux.view` (incl. text selection, 4,287) | 21,265 |

The terminal module adds about 59 KB of dex, 0.6 % of the APK. The universal APK carries every ABI
of its one native library today (arm64-v8a, armeabi-v7a, x86, x86_64), so a native engine would be
packed four times over unless the APK is split or filtered by ABI.
