#!/usr/bin/env bash
# Stages the session that scripts/bench-startup.sh and scripts/generate-baseline-profile.sh open: a
# window named bench-history with 3,000 colored lines of history, enough to scroll. Made once, and only
# ever on the demo server of docs/demo on 127.0.0.1:47321.
set -euo pipefail
R=(rex -S 127.0.0.1:47321 --autostart=false)

"${R[@]}" ls | grep -q '^zz-startup ' && exit 0
"${R[@]}" new zz-startup --window bench-history --shell none -- /usr/bin/python3 -I -c '
import random, sys, time
random.seed(7)
words = "build test cargo claude rex ghostty terminal stream snapshot scroll render frame".split()
for i in range(3000):
    sys.stdout.write(f"\x1b[{31 + i % 7}m{i:5d}\x1b[0m " + " ".join(random.choice(words) for _ in range(9)) + "\n")
sys.stdout.flush()
time.sleep(10**7)' >/dev/null
