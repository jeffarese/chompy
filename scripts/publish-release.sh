#!/usr/bin/env bash
# Publish the release APK just built to Chompy's GitHub releases, where the app's updater finds it.
#
#   ./gradlew :app:assembleRelease -q
#   scripts/publish-release.sh notes.md      # the release's notes, as Markdown bullets
#
# The version comes from the build's output-metadata.json and becomes the tag (v0.3.2). The APK must be
# signed with Chompy's release key: one signed with another key couldn't update the installed app, from
# GitHub or from Google Play. The same version goes to Play as a bundle: see docs/play/README.md.
set -euo pipefail

cd "$(dirname "$0")/.."
repo="${CHOMPY_RELEASES_REPO:-jeffarese/chompy}"
notes="${1:?usage: scripts/publish-release.sh NOTES_FILE}"
meta=app/build/outputs/apk/release/output-metadata.json
built=app/build/outputs/apk/release/app-release.apk
# The release key's certificate, as the README gives it for checking a download.
key=6403cd230907af161f1822b71bda993164a5c2cb847c08c184f036c3892a594a

[[ -f "$meta" ]] || { echo "No release build: run ./gradlew :app:assembleRelease first" >&2; exit 1; }
[[ -f "$notes" ]] || { echo "No notes file at $notes" >&2; exit 1; }

sdk=$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)
apksigner=$(ls -d "${sdk:-${ANDROID_SDK_ROOT:-}}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
[[ -x "$apksigner" ]] || { echo "No apksigner in the Android SDK's build-tools" >&2; exit 1; }
signer=$("$apksigner" verify --print-certs "$built" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[[ "$signer" == "$key" ]] || { echo "The APK isn't signed with Chompy's release key (it has $signer)" >&2; exit 1; }

version=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$meta")
# Such as 0.4.0-hosts, a build for the benchmarks that names the demo server: build it again without -P.
[[ "$version" != *-* ]] || { echo "This build is $version, not one to publish: run ./gradlew :app:assembleRelease again" >&2; exit 1; }
mkdir -p dist
apk="dist/chompy-$version.apk"
cp "$built" "$apk"

gh release create "v$version" "$apk" --repo "$repo" --title "Chompy $version" --notes-file "$notes"
echo "Published Chompy $version to $repo"
