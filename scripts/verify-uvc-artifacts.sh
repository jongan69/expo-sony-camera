#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ACTIVE="$ROOT/android/libs/UVCAndroid-sony-bulk-patched.aar"
CERTIFIED="$ROOT/android/libs/UVCAndroid-sony-bulk-patched-arm64-certified.aar"
SOURCE="$ROOT/third_party/UVCAndroid"
EXPECTED_COMMIT=fcdae5f9a194e2111d57019176b53470a120415b

test -f "$ACTIVE"
test -f "$CERTIFIED"
test -f "$SOURCE/LICENSE"
test -f "$SOURCE/UPSTREAM_COMMIT"
test -f "$SOURCE/libuvccamera/src/main/jni/libuvc/src/stream.c"
test "$(tr -d '\r\n' < "$SOURCE/UPSTREAM_COMMIT")" = "$EXPECTED_COMMIT"

active_entries=$(unzip -Z1 "$ACTIVE")
certified_entries=$(unzip -Z1 "$CERTIFIED")

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  grep -q "^jni/$abi/libuvc\.so$" <<< "$active_entries"
done

grep -q '^jni/arm64-v8a/libuvc\.so$' <<< "$certified_entries"
(cd "$ROOT/android/libs" && shasum -a 256 -c SHA256SUMS)
