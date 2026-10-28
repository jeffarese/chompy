#!/usr/bin/env bash
# Run the instrumented engine benchmarks (app/src/androidTest/.../bench/EngineBench.kt) and copy
# their JSON results to app/build/bench.
#
# Uses the device in ANDROID_SERIAL if set. Otherwise it boots the rexmobile-test AVD headless on
# port 5580, and stops exactly that emulator when done.
#
#   scripts/bench-engine.sh                  # every benchmark
#   scripts/bench-engine.sh parseThroughput  # one of them
set -euo pipefail
cd "$(dirname "$0")/.."

SDK=${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVD=${BENCH_AVD:-rexmobile-test}
PKG=com.jeffarese.rexmobile
CLASS=$PKG.bench.EngineBench${1:+#$1}
OUT=app/build/bench
mkdir -p "$OUT"

emulator_pid=""
cleanup() {
    if [[ -n $emulator_pid ]]; then
        echo "stopping emulator (pid $emulator_pid)"
        "$ADB" -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || true
        for _ in $(seq 30); do kill -0 "$emulator_pid" 2>/dev/null || break; sleep 1; done
        kill "$emulator_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT

if [[ -z ${ANDROID_SERIAL:-} ]]; then
    export ANDROID_SERIAL=emulator-5580
    # A cold boot each time, so no state carries over from an earlier run.
    "$EMULATOR" -avd "$AVD" -port 5580 -no-window -no-audio -no-boot-anim -no-snapshot >"$OUT/emulator.log" 2>&1 &
    emulator_pid=$!
    echo "emulator pid $emulator_pid ($ANDROID_SERIAL)"
    "$ADB" -s "$ANDROID_SERIAL" wait-for-device
    until [[ $("$ADB" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do sleep 2; done
    # Settle after boot, when the system is still busy optimizing.
    sleep 30
fi

./gradlew -q :app:assembleBenchmark :app:assembleBenchmarkAndroidTest
"$ADB" install -r -t app/build/outputs/apk/benchmark/app-benchmark.apk >/dev/null
"$ADB" install -r -t app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk >/dev/null

"$ADB" shell rm -rf "/sdcard/Android/data/$PKG/files/bench"
"$ADB" shell am instrument -w -e class "$CLASS" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/engine-instrument.log"
grep -q "^OK (" "$OUT/engine-instrument.log"
"$ADB" pull "/sdcard/Android/data/$PKG/files/bench/." "$OUT/" >/dev/null
"$ADB" shell getprop ro.product.model | tr -d '\r' | sed 's/^/device: /'
"$ADB" shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r' | sed 's/^/app heap limit: /'
ls -1 "$OUT"/engine-*.json
