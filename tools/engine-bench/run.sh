#!/usr/bin/env bash
#
# The engine benchmark: one command that runs real audio -> ONNX -> score over a
# fixed, versioned set of reference clips and reports the metric the owner
# mandates - how many native reference clips reach 98 - plus the separation AUC
# as a guard, and FAILS when the engine gets worse.
#
# See docs/ENGINE-BENCHMARK.md and tools/engine-bench/README.md.
#
#   tools/engine-bench/run.sh
#
# It reuses the oracle sample builder already in the repo
# (tools/reference-selfscore/make_samples.py); it does not measure with a second
# script. Inputs (all overridable by env):
#   GLOSSO_ASSETS      dir with model/vocab/cmudict    (default: <repo>/data/assets)
#   GLOSSO_DATA        glosso-data checkout            (default: auto-detected sibling)
#   GLOSSO_BENCH_WORK  scratch dir for WAVs+manifests  (default: <repo>/build/engine-bench)
#
# Assets are large and are NOT in git. Fetch them with tools/fetch-assets.sh or
# copy an existing data/assets (anything with wav2vec2_phoneme_fp16.onnx).

set -euo pipefail

fail() { echo "ERROR: $*" >&2; exit 1; }

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$REPO/tools/engine-bench"
SELFSCORE="$REPO/tools/reference-selfscore"
ASSETS="${GLOSSO_ASSETS:-$REPO/data/assets}"
WORK="${GLOSSO_BENCH_WORK:-$REPO/build/engine-bench}"

if [ -n "${GLOSSO_DATA:-}" ]; then
    DATA="$GLOSSO_DATA"
else
    DATA=""
    for cand in "$REPO/../glosso-data" "$REPO/../../glosso-data" "$HOME/github/IgnacioLD/glosso-data"; do
        if [ -f "$cand/content/en/content.db" ]; then DATA="$cand"; break; fi
    done
    [ -n "$DATA" ] || fail "glosso-data not found; set GLOSSO_DATA to the checkout with content/en/content.db"
fi

MODEL="${GLOSSO_MODEL:-$ASSETS/wav2vec2_phoneme_fp16.onnx}"
VOCAB="${GLOSSO_VOCAB:-$ASSETS/phoneme_vocab.json}"
CMUDICT="${GLOSSO_CMUDICT:-$ASSETS/cmudict.dict}"
DB="${GLOSSO_CONTENT_DB:-$DATA/content/en/content.db}"
AUDIO_ROOT="${GLOSSO_AUDIO_ROOT:-$DATA/content/en/audio}"

[ -f "$MODEL" ] || fail "model not found at $MODEL (run tools/fetch-assets.sh, or set GLOSSO_MODEL)"
[ -f "$VOCAB" ] || fail "vocab not found at $VOCAB (set GLOSSO_VOCAB)"
[ -f "$CMUDICT" ] || fail "cmudict not found at $CMUDICT (set GLOSSO_CMUDICT)"
[ -f "$DB" ] || fail "content.db not found at $DB (set GLOSSO_DATA or GLOSSO_CONTENT_DB)"
[ -d "$AUDIO_ROOT" ] || fail "audio dir not found at $AUDIO_ROOT (set GLOSSO_DATA or GLOSSO_AUDIO_ROOT)"

if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21 ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21
fi

echo "== engine benchmark =="
echo "assets    $MODEL"
echo "content   $DB"
echo "golden    $SELFSCORE/golden-ids.txt"
echo "natives   $HERE/native-ids.txt"
echo

MAKE=(python3 "$SELFSCORE/make_samples.py" --db "$DB" --audio-root "$AUDIO_ROOT")
"${MAKE[@]}" --ids-file "$SELFSCORE/golden-ids.txt" --out "$WORK/golden"
"${MAKE[@]}" --ids-file "$HERE/native-ids.txt" --out "$WORK/native"

LOG="$WORK/gradle.log"
export GLOSSO_REFERENCE_MANIFEST="$WORK/golden/manifest.json"
export GLOSSO_NATIVE_MANIFEST="$WORK/native/manifest.json"
export GLOSSO_MODEL="$MODEL" GLOSSO_VOCAB="$VOCAB" GLOSSO_CMUDICT="$CMUDICT"
export GLOSSO_REQUIRE_REFERENCE_SAMPLE=1

set +e
"$REPO/gradlew" :engine:jvmTest \
    --tests 'com.glosso.engine.phonetics.ReferenceSelfScoreTest' \
    --tests 'com.glosso.engine.phonetics.ReferenceVariantAucProbeTest' \
    --tests 'com.glosso.engine.phonetics.EnGbReferenceAucProbeTest' \
    --rerun-tasks --info --console=plain >"$LOG" 2>&1
status=$?
set -e

echo
grep -E 'REFSCORE_SUMMARY|NATIVE98\||AUC_PROBE_CHECK|GB_AUC_PROBE_CHECK|AUC_PROBE_SET' "$LOG" || true
echo
if [ "$status" -eq 0 ]; then
    echo "BENCHMARK PASS"
else
    echo "BENCHMARK FAIL (gradle exit $status) - full log: $LOG"
    grep -E 'FAILED|reaching 98|regressed' "$LOG" | head -40 || true
fi
exit "$status"
