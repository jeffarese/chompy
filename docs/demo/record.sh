#!/bin/zsh
# Records the README demo on the emulator: agents start and the board sorts them; Claude's question is answered from
# its card, its permission menu from another, the note it leaves once done is read and put away, and its effort is
# raised from the Claude strip.
set -e
D=${0:A:h}
OUT=${D:h:h}/build/demo
ADB=${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb
nap() { /bin/sleep $1 }

# Taps the centre of the first node whose text or accessibility label is $1 (or starts with it, for a $1 ending in
# "…") and whose top is above $2 pixels.
tap() {
  $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null
  local pos=($($ADB exec-out cat /sdcard/ui.xml | python3 -I -c '
import html, re, sys
want, below = sys.argv[1], int(sys.argv[2])
def attr(node, name):
    m = re.search(name + r"=\"([^\"]*)\"", node)
    return html.unescape(m.group(1)) if m else ""
for node in re.findall(r"<node [^>]*>", sys.stdin.read()):
    names = attr(node, " text"), attr(node, "content-desc")
    hit = any(n.startswith(want[:-1]) if want.endswith("…") else n == want for n in names if n)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", attr(node, "bounds")))
    if hit and y1 < below:
        print((x1 + x2) // 2, (y1 + y2) // 2); break
' "$1" "${2:-2400}"))
  [[ -n $pos ]] || { echo "nothing reads \"$1\"" >&2; exit 1 }
  $ADB shell input tap $pos[1] $pos[2]
}

$D/stage.sh >/dev/null
# A fresh start, so no setting from an earlier take shows, such as the Claude strip left open. The build's preset host
# skips the welcome.
$ADB shell pm clear com.jeffarese.rexmobile >/dev/null
$ADB shell pm grant com.jeffarese.rexmobile android.permission.POST_NOTIFICATIONS
$ADB shell am start -n com.jeffarese.rexmobile/.MainActivity >/dev/null
nap 4
$ADB shell rm -f /sdcard/demo.mp4
$ADB shell screenrecord --bit-rate 16000000 /sdcard/demo.mp4 &
nap 2.5

touch $OUT/start           # every agent starts reporting
nap 9                      # they work for a while, then ask, finish and fail

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
nap 1
tap "Show Claude's controls"                    # the Claude strip, over the key bar
nap 1.2
tap "Effort:…"                                  # it works at high
nap 1.2
tap "max"                                       # turned up from the strip's menu
nap 2.5
$ADB shell input keyevent 4                     # back to the board, where it's Done
nap 3

$ADB shell kill -2 $($ADB shell pidof screenrecord | tr -d '\r')
nap 2
$ADB pull /sdcard/demo.mp4 $OUT/demo-raw.mp4 >/dev/null
echo recorded $OUT/demo-raw.mp4
