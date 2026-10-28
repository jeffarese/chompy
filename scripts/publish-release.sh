#!/usr/bin/env bash
# Publish the release APK just built to the releases repository, where the app's updater finds it.
#
#   ./gradlew :app:assembleRelease -q
#   scripts/publish-release.sh notes.md      # the release's notes, as Markdown bullets
#
# The version comes from the build's output-metadata.json and becomes the tag (v0.3.2). The APK must be
# signed with the release key: one signed with the debug key couldn't update the installed app.
set -euo pipefail

cd "$(dirname "$0")/.."
repo="${REX_RELEASES_REPO:-jeffarese/rex-android-releases}"
notes="${1:?usage: scripts/publish-release.sh NOTES_FILE}"
meta=app/build/outputs/apk/release/output-metadata.json

[[ -f keystore.properties ]] || { echo "No keystore.properties: the APK would carry the debug key" >&2; exit 1; }
[[ -f "$meta" ]] || { echo "No release build: run ./gradlew :app:assembleRelease first" >&2; exit 1; }
[[ -f "$notes" ]] || { echo "No notes file at $notes" >&2; exit 1; }

version=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$meta")
mkdir -p dist
apk="dist/rex-mobile-$version.apk"
cp app/build/outputs/apk/release/app-release.apk "$apk"

gh release create "v$version" "$apk" --repo "$repo" --title "Rex $version" --notes-file "$notes"
echo "Published Rex $version to $repo"
