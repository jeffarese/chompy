#!/bin/zsh
# Sets the demo's stage: four Claudes, two on each demo server (Studio on 127.0.0.1:47321, MacBook on 47322), all
# waiting for the start file. Only ever talks to those two servers.
set -e
D=${0:A:h}
OUT=${D:h:h}/build/demo
mkdir -p $OUT
rm -f $OUT/start $OUT/permit $OUT/docs
# Clear any earlier run.
for port in 47321 47322; do
  R=(rex -S 127.0.0.1:$port --autostart=false)
  for id in $($R ls --json 2>/dev/null | python3 -I -c 'import json,sys; [print(s["session_id"]) for s in json.load(sys.stdin)["sessions"]]' 2>/dev/null); do
    $R kill $id >/dev/null
  done
done
mk() { rex -S 127.0.0.1:$1 --autostart=false new $2 --window $3 --shell none -- /usr/bin/python3 -I $D/agent.py $4 $OUT/start >/dev/null; }
mk 47321 billing "retries" story-asks
mk 47321 chompy "reconnect" story-permit
mk 47322 api "redis" story-work
mk 47322 handbook "onboarding" story-docs
