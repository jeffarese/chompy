#!/bin/zsh
# Records Notify when away for Google Play's foreground service declaration: it is turned on in Settings, the app is
# left, and when Claude asks a question its notification answers it. Run after record.sh, with the app open on
# Settings › Alerts and Notify when away off. The raw video lands in build/demo/away-raw.mp4, and the notification's
# screenshot, for the Play listing, in build/demo/notification.png.
set -e
D=${0:A:h}
OUT=${D:h:h}/build/demo
ADB=${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb
nap() { /bin/sleep $1 }
tap() {
  $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null
  local pos=($($ADB exec-out cat /sdcard/ui.xml | python3 -I -c '
import html, re, sys
want = sys.argv[1]
for node in re.findall(r"<node [^>]*>", sys.stdin.read()):
    names = [html.unescape(m) for m in re.findall(r"(?: text|content-desc)=\"([^\"]*)\"", node)]
    if want in names:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", node).group(1)))
        print((x1 + x2) // 2, (y1 + y2) // 2); break
' "$1"))
  [[ -n $pos ]] || { echo "nothing reads \"$1\"" >&2; exit 1 }
  $ADB shell input tap $pos[1] $pos[2]
}

$D/stage.sh >/dev/null
# Battery limits back on, so Settings asks for them to be lifted again.
$ADB shell dumpsys deviceidle whitelist -com.jeffarese.chompy >/dev/null 2>&1 || true
$ADB shell rm -f /sdcard/away.mp4
$ADB shell screenrecord --bit-rate 16000000 /sdcard/away.mp4 &
nap 2.5
tap "Notify when away"
nap 1.5
$ADB shell input swipe 540 1400 540 900 400            # up to the row it adds
nap 1.5
tap "Let Chompy run in the background"          # Android asks to let it run unrestricted
nap 1.5
tap "Allow"
nap 2
$ADB shell input keyevent 3                     # away, to the home screen
nap 2
touch $OUT/start                                # the agents start, and in a few seconds Claude asks
nap 10
$ADB shell cmd statusbar expand-notifications
nap 2
$ADB exec-out screencap -p > $OUT/notification.png
tap "webhooks"                                  # answered from the notification, which goes once Claude is back at work
nap 6
$ADB shell cmd statusbar collapse
nap 1.5
$ADB shell kill -2 $($ADB shell pidof screenrecord | tr -d '\r')
nap 2
$ADB pull /sdcard/away.mp4 $OUT/away-raw.mp4 >/dev/null
echo recorded $OUT/away-raw.mp4
