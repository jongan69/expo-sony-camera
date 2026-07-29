#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SOURCE="$ROOT/third_party/UVCAndroid"
OUTPUT="$SOURCE/libuvccamera/build/outputs/aar/libuvccamera-release.aar"
DESTINATION="$ROOT/android/libs/UVCAndroid-sony-bulk-patched.aar"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

cd "$SOURCE"
./gradlew --no-daemon :libuvccamera:assembleRelease
test -f "$OUTPUT"
cp "$OUTPUT" "$DESTINATION"

cd "$ROOT/android/libs"
shasum -a 256   UVCAndroid-sony-bulk-patched.aar   UVCAndroid-sony-bulk-patched-arm64-certified.aar > SHA256SUMS

"$ROOT/scripts/verify-uvc-artifacts.sh"

# Keep the vendored tree source-only after the AAR and checksums are installed.
for generated in "$SOURCE/.gradle" "$SOURCE/libuvccamera/build"; do
  if [ -d "$generated" ]; then
    find "$generated" -depth -delete
  fi
done
