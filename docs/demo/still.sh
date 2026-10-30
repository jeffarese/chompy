#!/bin/zsh
# The board for the feature graphic: an agent in each state, standing still, with nothing to answer on their cards.
# Saves docs/play/shots/feature_board.png, which store-art.py puts in the feature graphic's phone. Set up the server,
# the app and the emulator as for record.sh (README.md), at 1080x1920. Only ever talks to the demo server on
# 127.0.0.1:47321.
set -e
D=${0:A:h}
OUT=${D:h:h}/build/demo
ADB=${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}/platform-tools/adb
APP=com.jeffarese.chompy
R=(rex -S 127.0.0.1:47321 --autostart=false)
mkdir -p $OUT
rm -f $OUT/start
for id in $($R ls --json 2>/dev/null | python3 -I -c 'import json,sys; [print(s["session_id"]) for s in json.load(sys.stdin)["sessions"]]' 2>/dev/null); do
  $R kill $id >/dev/null
done
still() { $R new $1 --window $2 --shell none -- /usr/bin/python3 -I $D/agent.py still $OUT/start $3 $4 $5 $6 /Users/dev/src/$1 >/dev/null; }
still billing "retries" claude blocked "Add retries to the webhook sender" "Which queue should the retries use?"
still vt-parser "tests" cargo error "Test the parser" "2 of 148 tests failed"
still infra "staging" terraform done "Plan staging" "Plan: 3 to add, 1 to change, 0 to destroy"
still chompy "reconnect" claude working "Fix reconnect after a network change" "Editing NetworkWatch.kt"
still deploy-tool "cobra" codex working "Port the CLI flags to cobra" "Mapping 23 flags"
still site "notes" gemini working "Draft the release notes" "Polishing the wording"

$ADB shell pm clear $APP >/dev/null
$ADB shell pm grant $APP android.permission.POST_NOTIFICATIONS
$ADB shell am start -n $APP/com.jeffarese.rexmobile.MainActivity >/dev/null
/bin/sleep 4
touch $OUT/start
# Every banner gone, and the cards settled in their places.
/bin/sleep 10
$ADB exec-out screencap -p > ${D:h}/play/shots/feature_board.png
echo "Saved docs/play/shots/feature_board.png"
