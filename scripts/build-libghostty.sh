#!/bin/bash
# Build the terminal's native library: libghostty-vt at the Ghostty commit the Rex server uses
# (terminal/native/GHOSTTY_COMMIT), linked into the JNI bridge (terminal/src/main/cpp/rexterm.c).
#
# It writes the phone's library, terminal/src/main/jniLibs/arm64-v8a/librexterm.so, and this Mac's,
# terminal/native/host/librexterm.dylib, which the unit tests load. Both are checked in, so building
# the app needs neither Zig nor the NDK; run this again after changing rexterm.c or the commit.
#
# Needs: Zig (through mise, at the version that commit asks for), the Android NDK, and a JDK.
#
#     scripts/build-libghostty.sh
#
# The Rex server pins its Ghostty commit in go.mitchellh.com/libghostty's CMakeLists.txt. Snapshot
# format 1 still changes between Ghostty commits, so keep this commit the same as the server's. After
# moving it, run scripts/snapshot-fixture/make.sh for the unit tests' snapshot, and check
# terminal/native/ENGINE-LICENSES.txt against what libghostty-vt then builds in.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
COMMIT=$(tr -d '[:space:]' < "$ROOT/terminal/native/GHOSTTY_COMMIT")
WORK=${WORK:-$ROOT/build/libghostty}
SDK=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")
NDK=${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}
JDK=${JAVA_HOME:-$(sed -n 's/^org.gradle.java.home=//p' "$ROOT/gradle.properties")}
API=26

mkdir -p "$WORK"
if [ ! -d "$WORK/ghostty/.git" ]; then
  git clone --filter=blob:none --no-checkout -q https://github.com/ghostty-org/ghostty.git "$WORK/ghostty"
fi
git -C "$WORK/ghostty" fetch -q origin "$COMMIT" 2>/dev/null || true
git -C "$WORK/ghostty" checkout -q "$COMMIT"
ZIG_VERSION=$(sed -n 's/.*minimum_zig_version = "\(.*\)".*/\1/p' "$WORK/ghostty/build.zig.zon")

zig_build() {
  (cd "$WORK/ghostty" && ANDROID_NDK_HOME="$NDK" mise exec "zig@$ZIG_VERSION" -- zig build \
    -Demit-lib-vt=true -Demit-xcframework=false -Doptimize=ReleaseFast "$@")
}

echo "libghostty-vt $COMMIT for the phone"
zig_build -Dtarget="aarch64-linux-android.$API" --prefix "$WORK/out-android-arm64"
echo "libghostty-vt $COMMIT for this Mac"
zig_build --prefix "$WORK/out-host"

BIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
OUT_ANDROID="$ROOT/terminal/src/main/jniLibs/arm64-v8a"
OUT_HOST="$ROOT/terminal/native/host"
mkdir -p "$OUT_ANDROID" "$OUT_HOST"

echo "librexterm.so"
"$BIN/aarch64-linux-android$API-clang" -shared -fPIC -O2 -std=c11 \
  -I "$WORK/out-android-arm64/include" "$ROOT/terminal/src/main/cpp/rexterm.c" \
  "$WORK/out-android-arm64/lib/libghostty-vt.a" \
  -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,-soname,librexterm.so \
  -o "$OUT_ANDROID/librexterm.so"
"$BIN/llvm-strip" --strip-unneeded "$OUT_ANDROID/librexterm.so"

echo "librexterm.dylib"
clang -dynamiclib -O2 -std=c11 \
  -I "$WORK/out-host/include" -I "$JDK/include" -I "$JDK/include/darwin" \
  "$ROOT/terminal/src/main/cpp/rexterm.c" "$WORK/out-host/lib/libghostty-vt.a" \
  -Wl,-dead_strip -install_name @rpath/librexterm.dylib -o "$OUT_HOST/librexterm.dylib"
strip -x "$OUT_HOST/librexterm.dylib"

ls -l "$OUT_ANDROID/librexterm.so" "$OUT_HOST/librexterm.dylib"
