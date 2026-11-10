#!/usr/bin/env bash
#
# Downloads the official ONNX Runtime iOS build (the `onnxruntime-c` CocoaPods
# archive Microsoft publishes for every release) and lays it out for the
# Kotlin/Native cinterop of :engine's iOS recognizer:
#
#   <out>/include/                         C API headers
#   <out>/ios-arm64/libonnxruntime.a       device (iosArm64)
#   <out>/ios-simulator-arm64/libonnxruntime.a   simulator (iosSimulatorArm64), arm64 slice
#   <out>/LICENSE                          MIT, Microsoft Corporation
#
# The version matches the Android artifact (onnxruntime-android in
# gradle/libs.versions.toml) and the archive is pinned by SHA-256, so every
# machine links the same bytes. The binary is never committed (data/ is
# gitignored); :engine runs this script before the cinterop when the output is
# missing, so a plain Gradle build on a Mac needs no manual step.
#
# Usage: tools/fetch-onnxruntime-ios.sh [output-dir]
#        (default: data/onnxruntime-ios/<version>)
#
# Bumping the version: change ORT_VERSION and ORT_SHA256 together (the hash of
# https://download.onnxruntime.ai/pod-archive-onnxruntime-c-<version>.zip), and
# run the iOS parity test (docs/IOS-ENGINE.md) before merging.

set -euo pipefail

ORT_VERSION="1.30.0"
ORT_SHA256="e6f1670c14406fd9f082bb400ab197a9b0a9646058ca6366e440642e2b54a2ea"
ORT_URL="https://download.onnxruntime.ai/pod-archive-onnxruntime-c-${ORT_VERSION}.zip"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT/data/onnxruntime-ios/$ORT_VERSION}"
STAMP="$OUT/.complete"

if [[ -f "$STAMP" && "$(cat "$STAMP")" == "$ORT_SHA256" ]]; then
    echo "ONNX Runtime iOS $ORT_VERSION already at $OUT"
    exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "ONNX Runtime iOS $ORT_VERSION <- $ORT_URL"
curl -fL --progress-bar -o "$WORK/ort.zip" "$ORT_URL"

actual="$(shasum -a 256 "$WORK/ort.zip" | awk '{print $1}')"
if [[ "$actual" != "$ORT_SHA256" ]]; then
    echo "ERROR: SHA-256 mismatch for $ORT_URL" >&2
    echo "  expected $ORT_SHA256" >&2
    echo "  actual   $actual" >&2
    exit 1
fi

unzip -q "$WORK/ort.zip" -d "$WORK/x"
XCF="$WORK/x/onnxruntime.xcframework"

rm -rf "$OUT"
mkdir -p "$OUT/include" "$OUT/ios-arm64" "$OUT/ios-simulator-arm64"
cp "$WORK"/x/Headers/*.h "$OUT/include/"
cp "$WORK/x/LICENSE" "$OUT/LICENSE"
# The frameworks are static archives. The device one is arm64 only; the simulator
# one is arm64 + x86_64, and only the arm64 slice is linked (iosSimulatorArm64).
cp "$XCF/ios-arm64/onnxruntime.framework/onnxruntime" "$OUT/ios-arm64/libonnxruntime.a"
lipo "$XCF/ios-arm64_x86_64-simulator/onnxruntime.framework/onnxruntime" \
    -thin arm64 -output "$OUT/ios-simulator-arm64/libonnxruntime.a"
/usr/libexec/PlistBuddy -c 'Print :MinimumOSVersion' \
    "$XCF/ios-arm64/onnxruntime.framework/Info.plist" > "$OUT/MINIMUM_IOS_VERSION"

echo "$ORT_SHA256" > "$STAMP"
echo "OK: $OUT (minimum iOS $(cat "$OUT/MINIMUM_IOS_VERSION"))"
