#!/usr/bin/env bash
#
# iOS-vs-server score parity gate (docs/IOS-ENGINE.md).
#
# Runs the same clips through the JVM recognizer (the server's code path) and
# through the iOS recognizer on a simulator, then compares transcripts, token
# confidences, alignments, raw logits and the final score and feedback the
# server would return. It exits non-zero if any final score, verdict or
# transcript differs.
#
# Clips: the engine-bench sets (tools/reference-selfscore/golden-ids.txt and
# tools/engine-bench/native-ids.txt, built from glosso-data like run.sh) plus the
# four committed score-parity fixtures, whose server results are in
# androidApp/src/androidTest/assets/score-parity/golden.json.
#
#   tools/score-parity/run-ios-parity.sh [--simulator <name-or-udid>]
#
# Inputs (env, all optional):
#   GLOSSO_ASSETS      model + vocab + cmudict   (default: <repo>/data/assets; tools/fetch-assets.sh)
#   GLOSSO_DATA        glosso-data checkout      (default: auto-detected sibling)
#   GLOSSO_PARITY_WORK scratch dir               (default: <repo>/build/ios-parity)

set -euo pipefail

fail() { echo "ERROR: $*" >&2; exit 1; }

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ASSETS="${GLOSSO_ASSETS:-$REPO/data/assets}"
WORK="${GLOSSO_PARITY_WORK:-$REPO/build/ios-parity}"
SIMULATOR=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --simulator) SIMULATOR="$2"; shift 2 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

if [ -n "${GLOSSO_DATA:-}" ]; then
    DATA="$GLOSSO_DATA"
else
    DATA=""
    for cand in "$REPO/../glosso-data" "$REPO/../../glosso-data" "$HOME/github/IgnacioLD/glosso-data"; do
        if [ -f "$cand/content/en/content.db" ]; then DATA="$cand"; break; fi
    done
    [ -n "$DATA" ] || fail "glosso-data not found; set GLOSSO_DATA to the checkout with content/en/content.db"
fi

MODEL="$ASSETS/wav2vec2_phoneme_int8_dyn_pertensor.onnx"
VOCAB="$ASSETS/phoneme_vocab.json"
CMUDICT="$ASSETS/cmudict.dict"
for f in "$MODEL" "$VOCAB" "$CMUDICT"; do
    [ -f "$f" ] || fail "missing $f (run tools/fetch-assets.sh)"
done

if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21 ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21
fi

mkdir -p "$WORK"
MAKE=(python3 "$REPO/tools/reference-selfscore/make_samples.py"
    --db "$DATA/content/en/content.db" --audio-root "$DATA/content/en/audio")
"${MAKE[@]}" --ids-file "$REPO/tools/reference-selfscore/golden-ids.txt" --out "$WORK/golden"
"${MAKE[@]}" --ids-file "$REPO/tools/engine-bench/native-ids.txt" --out "$WORK/native"

FIXTURES="$REPO/androidApp/src/androidTest/assets/score-parity"
python3 - "$FIXTURES" "$WORK/fixtures.json" <<'EOF'
import json, sys
root, out = sys.argv[1], sys.argv[2]
golden = json.load(open(f"{root}/golden.json"))
req = golden["request"]
clips = [
    {"id": "score-parity." + c["fixture"].removesuffix(".wav"), "text": req["text"],
     "ipa": req["expectedIpa"], "wav": f"{root}/fixtures/{c['fixture']}"}
    for c in golden["cases"]
]
json.dump(clips, open(out, "w"), ensure_ascii=False, indent=2)
EOF

MANIFESTS="$WORK/fixtures.json,$WORK/golden/manifest.json,$WORK/native/manifest.json"
rm -rf "$WORK/jvm.jsonl"* "$WORK/ios.jsonl"*

echo "== JVM recognizer (server path) =="
GLOSSO_PARITY_MANIFESTS="$MANIFESTS" GLOSSO_PARITY_OUT="$WORK/jvm.jsonl" \
GLOSSO_MODEL="$MODEL" GLOSSO_VOCAB="$VOCAB" GLOSSO_CMUDICT="$CMUDICT" \
    "$REPO/gradlew" -p "$REPO" :engine:jvmTest \
    --tests 'com.glosso.engine.phonetics.RecognizerParityJvmTest' --rerun --console=plain -q

echo "== iOS recognizer (simulator) =="
# simctl forwards SIMCTL_CHILD_<NAME> to the test process as <NAME>.
SIM_ARGS=()
[ -n "$SIMULATOR" ] && SIM_ARGS=("-Pglosso.ios.simulator=$SIMULATOR")
SIMCTL_CHILD_GLOSSO_PARITY_MANIFESTS="$MANIFESTS" SIMCTL_CHILD_GLOSSO_PARITY_OUT="$WORK/ios.jsonl" \
SIMCTL_CHILD_GLOSSO_MODEL="$MODEL" SIMCTL_CHILD_GLOSSO_VOCAB="$VOCAB" SIMCTL_CHILD_GLOSSO_CMUDICT="$CMUDICT" \
    "$REPO/gradlew" -p "$REPO" :engine:iosSimulatorArm64Test \
    --tests 'com.glosso.engine.phonetics.IosRecognizerTest' --rerun --console=plain -q ${SIM_ARGS[@]+"${SIM_ARGS[@]}"}

[ -f "$WORK/ios.jsonl" ] || fail "the iOS run wrote no records (is Xcode installed?)"

echo "== compare =="
python3 "$REPO/tools/score-parity/compare-ios.py" \
    --jvm "$WORK/jvm.jsonl" --ios "$WORK/ios.jsonl" --golden "$FIXTURES/golden.json"
