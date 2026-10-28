#!/bin/zsh
# Sets the demo server's stage: one session per scripted agent, all waiting for the start file.
# Only ever talks to the demo server on 127.0.0.1:47321.
set -e
D=${0:A:h}
OUT=${D:h:h}/build/demo
mkdir -p $OUT
R=(rex -S 127.0.0.1:47321 --autostart=false)
rm -f $OUT/start
# Clear any earlier run.
for id in $($R ls --json 2>/dev/null | python3 -I -c 'import json,sys; [print(s["session_id"]) for s in json.load(sys.stdin)["sessions"]]' 2>/dev/null); do
  $R kill $id >/dev/null
done
mk() { $R new $1 --window $2 --shell none -- /usr/bin/python3 -I $D/agent.py $3 $OUT/start >/dev/null; }
mk rex-android "fix reconnect" claude
mk billing "webhook retries" claude-asks
mk deploy-tool "cobra" codex
mk infra "staging" terraform
mk vt-parser "tests" cargo
mk site "release notes" gemini
$R ls
