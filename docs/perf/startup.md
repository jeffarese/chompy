# Startup after an update

How fast the app starts and first opens a terminal right after an update, and what installing its
baseline profile with the update changes. Measured on 30 October 2026 with
[`scripts/bench-startup.sh`](../../scripts/bench-startup.sh).

## Why an update was slow

Installing an APK throws away what ART compiled for the version before. Until the phone's overnight
background dexopt, the new version's own code (Home, the terminal screen, `RemoteTerminal`, the Compose
it uses) runs in the interpreter and the JIT. The updater ships several versions a day, so the app was
nearly always in that state.

0.3.14's APK carried only the profile its libraries ship (Compose and the rest), and it never reached
ART on Android 15: profileinstaller 1.3, which Compose brings, answers "unsupported ART version" there
(`ProfileInstallReceiver` result 3 on the emulator). Even where it works, a profile it installs is
only compiled at that same overnight dexopt.

A profile installed *with* the APK, as a dex metadata file (`base.dm` beside `base.apk` in the install
session), is compiled while installing. That is what Play does, and now what the updater does.

## What changed

- **An app baseline profile.** [`BaselineProfileGenerator`](../../baselineprofile/src/main/java/com/jeffarese/rexmobile/baselineprofile/BaselineProfileGenerator.kt)
  covers the cold start, Home with a host's tabs, opening a terminal and scrolling its history,
  against the demo server of [docs/demo](../demo). Its output is checked in at
  `app/src/main/generated/baselineProfiles/baseline-prof.txt` (about 22,000 rules, 2,600 of them the
  app's own), and goes into every build's `assets/dexopt`. Regenerate it with
  [`scripts/generate-baseline-profile.sh`](../../scripts/generate-baseline-profile.sh).
- **The updater installs it.** `Updater.dexMetadata` reads `assets/dexopt/baseline.prof` and
  `baseline.profm` from the downloaded APK itself, so the profile always matches that APK's code, has
  profileinstaller's writer turn it into the format the phone's ART reads, and the install session
  gets it as `base.dm`. The profile can't stop an update: if it can't be made or written, the APK
  installs alone, and if Android turns down an install that carried one, it is tried once more
  without it (not when the user said no).
- **profileinstaller 1.4.1**, which installs the profile on Android 15 too, for apps installed some
  other way.

## Environment

| | |
|---|---|
| Emulator | AVD `rexmobile-test`: google_apis arm64-v8a, API 35, 4 vCPUs, 2 GB, 1080×2400, headless, SwiftShader GPU |
| Build | `release` (R8, as shipped), signed with the debug key, the demo server as its only host |
| Server | `rex server run` on 127.0.0.1:47321, a session with 3,000 colored lines of history |
| Mac | Apple M5 Max, 18 cores, shared with other builds and emulators (load average 8 to 20) |

The release build rather than the `benchmark` type: `benchmark` isn't minified, so its dex is 45 MB
against the release's 3.4 MB, and would measure an app that doesn't ship.

## Method

Each round forces a compiler filter with `cmd package compile -f -m <filter>`, clears the app's own
JIT profile, cold-starts it, waits 3 s on Home, taps the session's tab, waits 3 s, and scrolls the
history back three times. The three filters take turns round by round, 12 rounds each:

- **verify**: what an update leaves until the overnight dexopt. Nothing compiled.
- **speed-profile**: compiled from the profile installed with the APK, as the updater now does. With
  0.3.14's APK that is its libraries' profile; with the new one, the app's.
- **speed**: everything compiled, the ceiling.

The Mac was busy with other work, which stretches wall-clock times by 2 or 3 times from one run to the
next. So beside `am start -W`'s TotalTime the script reads each thread's CPU time from
`/proc/<pid>/task/*/schedstat`: the main thread's, which is what interpreted code costs the UI, and
the JIT compiler thread's. "Slow frames" are frames whose main-thread part (vsync to handing the frame
to the render thread, from `gfxinfo framestats`) took over 16.7 ms; the emulator's software GPU,
slow whatever ART does, comes after that and is left out. Two runs on different loads can't be
compared with each other, only the filters within a run.

## Results

Medians of 12 rounds, ms.

**0.3.14 (its libraries' profile only)**, the before:

| | verify | speed-profile | speed |
|---|---:|---:|---:|
| Cold start (TotalTime) | 576 | 579 | 520 |
| Cold start, main thread CPU | 120 | 114 | 110 |
| First Home → terminal, main thread CPU | 141 | 58 | 50 |
| First Home → terminal, slow frames | 11 | 10 | 9 |
| First Home → terminal, worst frame (main thread) | 70 | 53 | 68 |
| First scroll, main thread CPU | 67 | 53 | 58 |
| JIT thread CPU, whole round | 568 | 90 | 5 |

Round by round, verify against speed: cold start 1.5 times as long, the first navigation's main thread
3 times as busy (more in all 12 rounds). That is well past being worth doing.

**With the app's profile**, the after:

| | verify | speed-profile | speed |
|---|---:|---:|---:|
| Cold start (TotalTime) | 406 | 309 | 363 |
| Cold start, main thread CPU | 90 | 54 | 68 |
| First Home → terminal, main thread CPU | 76 | 38 | 34 |
| First Home → terminal, slow frames | 6 | 6 | 6 |
| First Home → terminal, worst frame (main thread) | 44 | 32 | 48 |
| First scroll, main thread CPU | 55 | 44 | 38 |
| JIT thread CPU, whole round | 197 | 18 | 4 |

What an update leaves goes from the verify column to the speed-profile one:

- **Opening the first terminal takes half the main thread's time** (76 → 38 ms), lower in all 12
  rounds, and about what compiling everything gives (34).
- **Starting takes 40% less of the main thread's time** (90 → 54 ms, lower in 10 of 12 rounds), less
  than `speed` (68), whose bigger compiled code costs more to load. The median wall-clock start drops
  from 406 to 309 ms, but on this busy Mac wall-clock time varied too much to put a number on it.
- **The JIT has a tenth of the work** (197 → 18 ms of CPU), work a phone does on its cores while the
  app starts.
- The app's profile does more than its libraries' did: against the JIT, 18 ms left of 197 rather than
  90 of 568 (0.09 of verify against 0.16).
- Frames: the emulator draws in software, so its frames are slow whatever ART does, and slow-frame
  counts don't separate the filters. The worst frame of the first navigation is lower with the
  profile in both runs.

A phone's cores are several times slower than the Mac's, so the milliseconds above grow on one; the
ratios are what carry over.

## Checked end to end

On the emulator, 0.3.14 with the new updater (installed with `adb`, so compiled `verify`) was given a
downloaded 0.3.15 as if it had found it on GitHub, and installed it from Home's card. Afterwards:

```
$ adb shell pm art dump com.jeffarese.rexmobile
    arm64: [status=speed-profile] [reason=install-dm] [primary-abi]
$ adb shell ls /data/app/*/com.jeffarese.rexmobile*/
base.apk  base.dm  lib  oat
```

artd opened `base.dm` during the install, the reference profile it left names 13,600 classes and
methods (`profman --dump-classes-and-methods`), and the installer of record is now the app itself.
`DexMetadataTest` (instrumented) checks the profile made from an APK is a zip holding `primary.prof` in
the device's format (`pro\0015\0` from Android 12). `UpdaterTest` checks that a profile that can't be
made doesn't stop the install, that an install turned down with a profile is tried once more without
it, and that one the user turned down isn't.

Not checked on a device: an install that Android turns down because of the profile. Nothing made one
fail; the retry is covered by `UpdaterTest` only.

## Run it again

Boot the emulator, start the demo server as in [docs/demo](../demo/README.md) (step 1), and build with
it as the only host:

```sh
./gradlew :app:assembleRelease -Prex.defaultHosts=Studio=http://10.0.2.2:47321
ANDROID_SERIAL=emulator-5554 scripts/bench-startup.sh \
  app/build/outputs/apk/release/app-release.apk \
  app/build/outputs/apk/release/baselineProfiles/0/app-release.dm
```

Each run takes about 15 minutes for 12 rounds (`BENCH_RUNS`), and leaves its rows in
`app/build/bench/startup-<time>.tsv`. The `.dm` beside the APK is the one AGP writes for `adb install`,
the same profile the updater makes from the APK's assets. The script needs root on the emulator, to
clear the app's own profile between rounds; google_apis images allow `adb root`.
