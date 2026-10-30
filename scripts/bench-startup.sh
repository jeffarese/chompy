#!/usr/bin/env bash
# Startup and first-use speed under each ART compilation state an install can leave: cold start
# (`am start -W`), then the first Home → terminal navigation and the first scroll of its history, from
# the frames gfxinfo records and the main thread's CPU time. Results in docs/perf/startup.md.
#
# Runs on an emulator already booted (ANDROID_SERIAL), against the demo server of docs/demo on
# 127.0.0.1:47321, which the APK must name as its only host (-Prex.defaultHosts=Studio=http://10.0.2.2:47321).
# It stages one session there with 3,000 lines of history (scripts/stage-startup-session.sh).
#
#   scripts/bench-startup.sh app-release.apk app-release.dm  # verify, speed-profile and speed
#   BENCH_MODES=verify BENCH_RUNS=3 scripts/bench-startup.sh app-release.apk app-release.dm
#
# A mode is the `cmd package compile -m` filter forced before each run: `verify` is what an update
# leaves until the phone's overnight dexopt, `speed-profile` compiles what the profile installed with
# the APK names (the .dm beside it, as AGP writes in outputs/apk/<variant>/baselineProfiles/0), and
# `speed` compiles everything. Without a .dm, speed-profile has no profile to go by.
set -euo pipefail
cd "$(dirname "$0")/.."

APK=${1:?usage: scripts/bench-startup.sh <apk> [<dm>]}
DM=${2:-}
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to a booted emulator}"
SDK=${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}
ADB="$SDK/platform-tools/adb"
PKG=com.jeffarese.chompy
RUNS=${BENCH_RUNS:-8}
MODES=${BENCH_MODES:-verify speed-profile speed}
OUT=app/build/bench
mkdir -p "$OUT"

# Only an emulator: this reinstalls the app and clears its data. Root, to clear the app's own profile
# between runs.
[[ $("$ADB" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo "$ANDROID_SERIAL isn't an emulator" >&2; exit 1; }
"$ADB" root >/dev/null
"$ADB" wait-for-device

scripts/stage-startup-session.sh

status() { "$ADB" shell pm art dump "$PKG" | grep -o '\[status=[^]]*\] \[reason=[^]]*\]' | head -1; }

# A clean install, with the profile as base.dm beside the APK: adb pairs them by name.
"$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
stage=$(mktemp -d)
cp "$APK" "$stage/base.apk"
if [[ -n $DM ]]; then
    cp "$DM" "$stage/base.dm"
    "$ADB" install-multiple -t "$stage/base.apk" "$stage/base.dm" >/dev/null
else
    "$ADB" install -t "$stage/base.apk" >/dev/null
fi
rm -rf "$stage"
echo "installed: $(status)"

# Where the session's tab is on Home, read once from the screen.
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
"$ADB" shell am start -W -n "$PKG/com.jeffarese.rexmobile.MainActivity" >/dev/null
sleep 4
"$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null
read -r TAB_X TAB_Y < <("$ADB" exec-out cat /sdcard/ui.xml | python3 -I -c '
import re, sys
for node in re.findall(r"<node [^>]*>", sys.stdin.read()):
    if "text=\"bench-history\"" in node:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", node).group(1)))
        print((x1 + x2) // 2, (y1 + y2) // 2); break
')
[[ -n ${TAB_Y:-} ]] || { echo "no bench-history tab on Home" >&2; exit 1; }
"$ADB" shell am force-stop "$PKG"

# The main thread's share of each frame recorded since the last reset: from the frame's vsync until
# its drawing is handed to the render thread. This is what compiled code shortens; the emulator's
# GPU, which draws slowly whatever ART does, comes after.
frames() {
    "$ADB" shell dumpsys gfxinfo "$PKG" framestats | python3 -I -c '
import sys
lines = sys.stdin.read().splitlines()
start = lines.index("---PROFILEDATA---")
cols = lines[start + 1].rstrip(",").split(",")
ui = []
for line in lines[start + 2:]:
    if line.startswith("---"): break
    f = dict(zip(cols, map(int, line.rstrip(",").split(","))))
    ui.append((f["SyncQueued"] - f["IntendedVsync"]) / 1e6)
slow = sum(1 for t in ui if t > 16.7)
print(f"{len(ui)} {slow} {sum(ui):.0f} {max(ui, default=0):.0f}")'
}

# CPU time so far, in ms, of the main thread and of ART's JIT compiler thread. The emulator shares the
# Mac with whatever else runs on it, which stretches wall-clock times from one run to the next; the
# time a thread spends on the CPU moves far less, and it is what interpreted code costs.
cpu() {
    "$ADB" shell "p=\$(pidof $PKG); main=\$(cut -d' ' -f1 /proc/\$p/task/\$p/schedstat); jit=0
for t in /proc/\$p/task/*; do [ \"\$(cat \$t/comm)\" = 'Jit thread pool' ] && jit=\$(cut -d' ' -f1 \$t/schedstat); done
echo \$((main / 1000000)) \$((jit / 1000000))"
}

results="$OUT/startup-$(date +%Y%m%d-%H%M%S).tsv"
printf 'mode\trun\tstart_ms\tstart_cpu_ms\tnav_slow\tnav_worst_ms\tnav_cpu_ms\tscroll_slow\tscroll_worst_ms\tscroll_cpu_ms\tjit_cpu_ms\n' >"$results"

# The modes take turns, run by run, so a Mac that gets busier halfway weighs on each of them alike.
for run in $(seq "$RUNS"); do
    for mode in $MODES; do
        # Only the profile installed with the APK: none from earlier runs.
        "$ADB" shell rm -f "/data/misc/profiles/cur/0/$PKG/primary.prof"
        "$ADB" shell cmd package compile -f -m "$mode" "$PKG" >/dev/null
        [[ $run == 1 ]] && echo "$mode: $(status)"
        "$ADB" shell am force-stop "$PKG"
        sleep 1
        start=$("$ADB" shell am start -W -n "$PKG/com.jeffarese.rexmobile.MainActivity" | sed -n 's/^TotalTime: //p' | tr -d '\r')
        read -r c0 _ < <(cpu)
        # Home connects to the host and lists its tabs.
        sleep 3
        "$ADB" shell dumpsys gfxinfo "$PKG" reset >/dev/null
        read -r c1 _ < <(cpu)
        "$ADB" shell input tap "$TAB_X" "$TAB_Y"
        sleep 3
        read -r c2 _ < <(cpu)
        read -r _ ns _ nw < <(frames)
        "$ADB" shell dumpsys gfxinfo "$PKG" reset >/dev/null
        for _ in 1 2 3; do "$ADB" shell input swipe 540 700 540 1900 250; done
        sleep 1
        read -r c3 jit < <(cpu)
        read -r _ ss _ sw < <(frames)
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$mode" "$run" "$start" "$c0" "$ns" "$nw" $((c2 - c1)) "$ss" "$sw" $((c3 - c2)) "$jit" | tee -a "$results"
    done
done
"$ADB" shell am force-stop "$PKG"

# Medians per mode.
python3 -I - "$results" <<'PY'
import csv, statistics, sys
rows = list(csv.DictReader(open(sys.argv[1]), delimiter="\t"))
keys = list(rows[0])[2:]
print("median per mode (" + sys.argv[1] + ")")
print("mode".ljust(14) + "".join(k.rjust(15) for k in keys))
for mode in dict.fromkeys(r["mode"] for r in rows):
    rs = [r for r in rows if r["mode"] == mode]
    print(mode.ljust(14) + "".join(f"{statistics.median(float(r[k]) for r in rs):15.0f}" for k in keys))
PY

