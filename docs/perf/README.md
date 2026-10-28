# Terminal engine benchmarks

A harness to measure the terminal engine before and after a change of engine: Termux until 0.2.18,
libghostty-vt from 0.3.0. The baseline for Termux is in [baseline-termux.md](baseline-termux.md),
and libghostty-vt's results, against it, in [libghostty.md](libghostty.md).

Everything engine-specific goes through one file per test source set, so a new engine only
changes those two files:

- `app/src/test/java/com/jeffarese/rexmobile/bench/BenchEngine.kt` (live benchmarks, Robolectric)
- `app/src/androidTest/java/com/jeffarese/rexmobile/bench/BenchEngine.kt` (engine benchmarks, on a device)

The rest only uses `RemoteTerminal`, `TerminalSession.feed` and `ScreenReader`, which stay.

Every benchmark prints its results and writes them as JSON to `app/build/bench/`.

## Live benchmarks

They need a Rex server. Each makes throwaway sessions named `zz-bench-…` and destroys them when
done; without `REX_LIVE_HOST` they are skipped. Use this Mac's server: the numbers depend on the
path to it.

### Load of a fresh stream

`LiveLoadBench`: for scrollbacks of 0 (a fresh shell), 2,000 and 14,000 numbered 150-column lines
of random base64, on a 160×48 block, it opens a fresh stream over links of unlimited speed,
512 KB/s and 48 KB/s, three times each, and records the time to the first right screen, to input
open (`syncing` false), and to loaded (`!catchingUp && !syncing`, and the history all there:
`BenchEngine.historyLoading`), the bytes received on the data
and control connections (TLS included), `RemoteTerminal.lastLoad`, and the history the phone ends
up with. A cell whose first run takes over a minute runs once.

```sh
REX_LIVE_HOST=<your-host>.ts.net ./gradlew :app:testDebugUnitTest --tests '*LiveLoadBench*' --rerun
```

It takes about 4 minutes; results in `app/build/bench/live-load-<engine>.json`.

- `BENCH_SIZES=0,2000`, `BENCH_LINKS=0,48` (KB/s, 0 unlimited) and `BENCH_RUNS=1` narrow the matrix.
- The link's speed is shared by the stream and the control connection, as on a phone.
  `BENCH_THROTTLE=data` throttles the stream alone, as `LiveEarlyScreenTest` does
  (results in `live-load-<engine>-data-only.json`).

### Fidelity

`LiveFidelityBench`: on an 80×30 block it prints a fixed corpus of hard cases (emoji sequences,
flags, wide characters at the right edge, combining marks, tabs, line drawing, DEC graphics, a
scroll region, insert and delete, REP, a hyperlink, underline styles, colors), then compares
the phone's rows with the server's `format` text row by row, and the style of a few cells with the
server's `format` HTML. Each row ends in a bar at column 60, so a width difference shows as a
different number of spaces. Then a program turns on mouse reporting, bracketed paste, application
cursor keys and keypad, and Kitty keyboard flags, and the modes the phone's emulator believes are
on are compared with `com.superlogical.terminal.modes`. Each comparison runs on the live stream and
again after a fresh stream.

```sh
REX_LIVE_HOST=<your-host>.ts.net ./gradlew :app:testDebugUnitTest --tests '*LiveFidelityBench*' --rerun
```

It takes about 15 seconds and reports mismatches rather than failing; results in
`app/build/bench/live-fidelity-<engine>.json`.

## Engine benchmarks (on a device)

`EngineBench` (instrumented): parse throughput for four synthetic corpora of 4 MB fed in 16 KB
chunks (plain ASCII, SGR-heavy, Unicode, and a full-screen redraw in synchronized updates), the
time to draw a styled 60×40 screen into a 1080×2400 software bitmap, and the memory 4,000 and
14,000 rows of 150-column history hold: Java heap, native heap, the process's PSS (which counts
memory an engine maps itself, as libghostty-vt does), the engine's own count where it keeps one,
and the same after compressing the history where the engine can. The corpora are generated from
fixed seeds.

It runs against the `benchmark` build type, which is debug without `debuggable`, so ART compiles
the engine as in a release. The script boots the `rexmobile-test` AVD headless (arm64, API 35),
runs the benchmarks, copies the JSON results to `app/build/bench/`, and stops
that emulator:

```sh
scripts/bench-engine.sh                   # all three: about 3 minutes with the boot, 25 s of tests
scripts/bench-engine.sh parseThroughput   # or renderTime, historyMemory
ANDROID_SERIAL=<device> scripts/bench-engine.sh   # a device already connected; nothing is stopped
```

The benchmark build is signed with the release key when `keystore.properties` has one, so it
installs over a release build on the emulator.

The emulator runs as fast as the Mac lets it, and a busy Mac can halve its speed from one boot to
the next. To compare two engines, run both on one boot, in turn, a few rounds each:
`ANDROID_SERIAL=emulator-5580` with each checkout's `scripts/bench-engine.sh`, uninstalling the app
between them when the version code goes down. The live benchmarks above run on the Mac's JVM
(Robolectric), so their times include no phone CPU; these are the phone-side costs.

## APK size

```sh
./gradlew :app:assembleRelease
stat -f %z app/build/outputs/apk/release/app-release.apk
```

The terminal module's share of the dex, from the R8 mapping:

```sh
/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/bin/apkanalyzer dex packages --defined-only \
  --proguard-mappings app/build/outputs/mapping/release/mapping.txt \
  app/build/outputs/apk/release/app-release.apk | grep -E '^P .*com\.termux(\.terminal|\.view)?$'
```
