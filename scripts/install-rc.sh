#!/bin/bash
# Build the debug APK as the next release candidate and install it on the
# connected phone. Non-main branches never touch version.properties: the RC
# is the next patch after main's version with an -rc.N suffix
# (main 1.3.1 -> 1.3.2-rc.1, 1.3.2-rc.2, ...), passed to Gradle as overrides.
#
#   scripts/install-rc.sh            # next rc.N for the current base
#   scripts/install-rc.sh --dry-run  # print the version, build nothing
set -euo pipefail

cd "$(dirname "$0")/.."

REMOTE=${RC_REMOTE:-origin}
MAIN=${RC_MAIN:-master}
DRY_RUN=false
[ "${1:-}" = "--dry-run" ] && DRY_RUN=true

git fetch -q "$REMOTE" "$MAIN" || echo "warning: fetch failed, using cached $REMOTE/$MAIN" >&2
props=$(git show "$REMOTE/$MAIN:version.properties")
prop() { echo "$props" | sed -n "s/^$1=//p" | tr -d '\r'; }
major=$(prop VERSION_MAJOR); minor=$(prop VERSION_MINOR); patch=$(prop VERSION_PATCH)
code=$(prop VERSION_CODE)
base="$major.$minor.$((patch + 1))"

# rc counter: highest of any v<base>-rc.N tag and the last local install
state="$(git rev-parse --git-dir)/motoeye-rc-$base"
last=$(cat "$state" 2>/dev/null || echo 0)
tagged=$(git tag -l "v$base-rc.*" | sed -n 's/.*-rc\.\([0-9]*\)$/\1/p' | sort -n | tail -1)
n=$(( (${tagged:-0} > last ? ${tagged:-0} : last) + 1 ))
version="$base-rc.$n"
rc_code=$((code + 1))

echo "RC version: v$version (code $rc_code; main is $major.$minor.$patch code $code)"
$DRY_RUN && exit 0

./gradlew -q assembleDebug -PversionNameOverride="$version" -PversionCodeOverride="$rc_code"
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
echo "$n" > "$state"
adb shell dumpsys package com.a42r.motoeye.remote | grep -m1 versionName
