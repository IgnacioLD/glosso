#!/usr/bin/env bash
#
# Cloud-vs-on-device score parity gate.
#
# Stages the scoring model + vocab on the device (push-copy; no network needed
# during the test), runs the committed ScoreParityTest against the golden, and
# exits non-zero on any drift.
#
# Usage:
#   tools/score-parity/run-parity-test.sh [--serial emulator-5570] [--clear]
#
#   --serial  adb serial to run on (default emulator-5570).
#   --clear   wipe app data first, forcing the test to re-provision the pack.
#
# The test loads the ~342 MB selective int8 model into memory, so the device
# needs roughly 1 GB of free RAM. The 2.5 GB emulator images here are too small
# and get OOM-killed while loading it; boot the emulator with -memory 4096.
#
# Requires data/assets/{wav2vec2_phoneme_int8_dyn_pertensor.onnx,phoneme_vocab.json}; run
# tools/fetch-assets.sh if they are missing. See
# androidApp/src/androidTest/assets/score-parity/README.md.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERIAL="emulator-5570"
CLEAR=0
STAGING="/data/local/tmp/glosso-parity"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --clear) CLEAR=1; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

MODEL="$ROOT/data/assets/wav2vec2_phoneme_int8_dyn_pertensor.onnx"
VOCAB="$ROOT/data/assets/phoneme_vocab.json"
if [[ ! -f "$MODEL" || ! -f "$VOCAB" ]]; then
    echo "Missing model assets under data/assets. Run: tools/fetch-assets.sh" >&2
    exit 1
fi

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"

# Stage the pack where the test can copy it (world-readable under /data/local/tmp,
# which the app uid may read). Skip files already staged at the right size.
"$ADB" -s "$SERIAL" shell mkdir -p "$STAGING"
for src in "$MODEL" "$VOCAB"; do
    name="$(basename "$src")"
    remote_size="$("$ADB" -s "$SERIAL" shell "stat -c %s '$STAGING/$name' 2>/dev/null" 2>/dev/null | tr -d '\r' || true)"
    local_size="$(wc -c < "$src" | tr -d ' ')"
    if [[ "$remote_size" != "$local_size" ]]; then
        echo "Staging $name ($local_size bytes) on $SERIAL"
        "$ADB" -s "$SERIAL" push "$src" "$STAGING/$name" >/dev/null
    else
        echo "Already staged: $name"
    fi
done
"$ADB" -s "$SERIAL" shell chmod 755 "$STAGING"
"$ADB" -s "$SERIAL" shell chmod 644 "$STAGING/wav2vec2_phoneme_int8_dyn_pertensor.onnx" "$STAGING/phoneme_vocab.json"

if [[ "$CLEAR" == 1 ]]; then
    echo "Clearing app data on $SERIAL"
    "$ADB" -s "$SERIAL" shell pm clear me.shirobyte42.glosso >/dev/null 2>&1 || true
fi

echo "Running ScoreParityTest on $SERIAL"
ANDROID_SERIAL="$SERIAL" "$ROOT/gradlew" -p "$ROOT" :androidApp:connectedPlaystoreDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.glosso.app.ScoreParityTest
