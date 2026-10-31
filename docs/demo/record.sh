#!/bin/zsh
# Records the README demo on the emulator: four Claudes on two Macs, one thing at a time. One asks a question and is
# answered from its card on Home; another finishes; a third asks to run its tests, is allowed from its terminal, and
# leaves a note that is read and put away; its effort is raised from the Claude strip; and back on the board the
# finished one is cleared with a swipe. Needs both demo servers and a build with both hosts (README.md).
#
# With SHOTS=<dir> it records no video and takes a screenshot at each moment instead (board.png, permission.png, ...),
# for the README's stills and the Play listing.
set -e
D=${0:A:h}
OUT=${D:h:h}/build/demo
ADB=${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb
APP=com.jeffarese.chompy
nap() { /bin/sleep $1 }
# A screenshot named $1, in SHOTS mode only, after waiting $2 seconds there for banners and cards to settle.
shot() { [[ -z $SHOTS ]] || { nap ${2:-0}; $ADB exec-out screencap -p > $SHOTS/$1.png } }

# The centre of the first node whose text or accessibility label is $1 (or starts with it, for a $1 ending in "…")
# and whose top is above $2 pixels.
find_node() {
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
  echo $pos
}
tap() { local p=($(find_node "$@")); $ADB shell input tap $p[1] $p[2] }
# Drags the node reading $1 to the right, as a finger clearing a card does.
swipe_away() { local p=($(find_node "$@")); $ADB shell input swipe $p[1] $p[2] $(( p[1] + 700 )) $p[2] 250 }

$D/stage.sh >/dev/null
# A fresh start, so no setting from an earlier take shows, such as the Claude strip left open. The build's preset host
# skips the welcome.
$ADB shell am force-stop com.jeffarese.rexmobile   # an older build on the emulator would alert for these agents too
$ADB shell pm clear $APP >/dev/null
$ADB shell pm grant $APP android.permission.POST_NOTIFICATIONS
$ADB shell am start -n $APP/com.jeffarese.rexmobile.MainActivity >/dev/null
nap 4
touch $OUT/start           # the four Claudes start working
nap 2.5                    # and their cards settle before the take starts
if [[ -z $SHOTS ]]; then
  $ADB shell rm -f /sdcard/demo.mp4
  $ADB shell screenrecord --bit-rate 16000000 /sdcard/demo.mp4 &
  nap 1
else
  mkdir -p $SHOTS
fi

nap 3                      # the one adding retries asks which queue to use
shot board 1.5

# Each tap first reads the screen for its button, which takes about a second, so the naps before them are short.
tap "webhooks" 1500                             # answered from its card on Home
nap 2.5                                         # and it's back to work
shot answered
touch $OUT/docs                                 # the guide is written
nap 3.5
touch $OUT/permit                               # the reconnect fix wants to run the tests
nap 2.5
tap "Fix reconnect after a network change" 1500 # its terminal, with the permission menu as a card
nap 1.2
shot permission
tap "Yes"
nap 3                                           # they pass, and Claude leaves a note
shot note 1.5
tap "Learn more"
nap 1.2
shot note-open
tap "Understood"
nap 1
tap "Show Claude's controls"                    # the Claude strip, over the key bar
nap 1.2
tap "Effort:…"
nap 1.2
shot effort
tap "max"                                       # turned up from the strip's menu
nap 2.5
$ADB shell input keyevent 4                     # back to the board
nap 2.5
shot done
swipe_away "Write the onboarding guide"         # the finished guide, seen and cleared
nap 3

if [[ -z $SHOTS ]]; then
  $ADB shell kill -2 $($ADB shell pidof screenrecord | tr -d '\r')
  nap 2
  $ADB pull /sdcard/demo.mp4 $OUT/demo-raw.mp4 >/dev/null
  echo recorded $OUT/demo-raw.mp4
else
  echo screenshots in $SHOTS
fi
