#!/usr/bin/env bash
# Generate the app's baseline profile (baselineprofile/.../BaselineProfileGenerator.kt) into
# app/src/main/generated/baselineProfiles, to commit. Regenerate it when startup, Home or the terminal
# screen change a lot: code it doesn't name still runs, only interpreted until the phone's overnight
# dexopt.
#
# Runs on an emulator already booted (ANDROID_SERIAL; API 33 or later, or rooted), against the demo
# server of docs/demo on 127.0.0.1:47321. It stages the same session there as scripts/bench-startup.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${ANDROID_SERIAL:?set ANDROID_SERIAL to a booted emulator}"
SDK=${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}
ADB="$SDK/platform-tools/adb"
R=(rex -S 127.0.0.1:47321 --autostart=false)

# Only an emulator: the generator reinstalls the app.
[[ $("$ADB" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo "$ANDROID_SERIAL isn't an emulator" >&2; exit 1; }

scripts/stage-startup-session.sh

# The app sees the demo server as its only host, so Home opens on its tabs without a welcome.
./gradlew :app:generateBaselineProfile -Prex.defaultHosts=Studio=http://10.0.2.2:47321
wc -l app/src/main/generated/baselineProfiles/baseline-prof.txt
