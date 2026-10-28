#!/bin/sh
# Write the snapshot the unit tests restore (app/src/test/resources/snapshots) with the libghostty-vt
# that scripts/build-libghostty.sh built for this Mac. It is the Ghostty a Rex server runs, so this is
# the snapshot such a server sends. Run it again after moving to a new GHOSTTY_COMMIT.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=${WORK:-$ROOT/build/libghostty}
OUT=$ROOT/app/src/test/resources/snapshots
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

clang -O1 -std=c11 -I "$WORK/out-host/include" "$HERE/encode.c" "$WORK/out-host/lib/libghostty-vt.a" -o "$TMP/encode"
mkdir -p "$OUT"
python3 "$HERE/fixture.py" "$OUT/history.vt" "$OUT/alternate.vt"
"$TMP/encode" "$OUT/history.vt" "$OUT/history.snap" 120 30 100000
"$TMP/encode" "$OUT/alternate.vt" "$OUT/alternate.snap" 120 30 100000
