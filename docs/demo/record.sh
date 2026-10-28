#!/bin/zsh
# Records the README demo on the emulator: agents start and the board sorts them; Claude's question is answered from
# its card, its permission menu from another, and the note it leaves once done is read and put away.
set -e
D=${0:A:h}
OUT=${D:h:h}/build/demo
ADB=${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb
nap() { /bin/sleep $1 }

# Taps the centre of the first node whose text is $1 and whose top is above $2 pixels.
tap() {
  $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null
  local pos=($($ADB exec-out cat /sdcard/ui.xml | python3 -I -c '
import re, sys
want, below = sys.argv[1], int(sys.argv[2])
for m in re.finditer(r"text=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", sys.stdin.read()):
    t, x1, y1, x2, y2 = m.group(1), *map(int, m.groups()[1:])
    if t == want and y1 < below:
        print((x1 + x2) // 2, (y1 + y2) // 2); break
' "$1" "${2:-2400}"))
  [[ -n $pos ]] || { echo "nothing reads \"$1\"" >&2; exit 1 }
  $ADB shell input tap $pos[1] $pos[2]
}

$D/stage.sh >/dev/null
$ADB shell am start -n com.jeffarese.rexmobile/.MainActivity >/dev/null
nap 4
$ADB shell rm -f /sdcard/demo.mp4
$ADB shell screenrecord --bit-rate 16000000 /sdcard/demo.mp4 &
nap 2.5

touch $OUT/start           # every agent starts reporting
nap 7                      # they work, then ask, finish and fail

# Each tap first reads the screen for its button, which takes about a second, so the naps before them are short.
tap "Add retries to the webhook sender" 1500   # Claude's question
nap 0.8
tap "webhooks"                                  # answered from its card
nap 1.5
$ADB shell input keyevent 4                     # back to the board
nap 1

tap "Fix reconnect after a network change" 1500 # Claude wants to run the tests
nap 0.8
tap "Yes"
nap 3                                           # they pass, and Claude leaves a note
tap "Learn more"
nap 1.2
tap "Understood"
nap 0.8
$ADB shell input keyevent 4                     # back to the board, where it's Done
nap 3

$ADB shell kill -2 $($ADB shell pidof screenrecord | tr -d '\r')
nap 2
$ADB pull /sdcard/demo.mp4 $OUT/demo-raw.mp4 >/dev/null
echo recorded $OUT/demo-raw.mp4
