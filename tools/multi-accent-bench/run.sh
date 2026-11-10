#!/usr/bin/env bash
#
# Multi-accent bench. Two modes:
#
# 1. Native (default): each accent's own clips from content.db, scored with
#    multi-accent scoring against their own system (docs/MULTI-ACCENT.md). This
#    is the native metric once real native recordings exist.
#
#      tools/multi-accent-bench/run.sh                       # en-GB + en-US (shipped audio)
#      GLOSSO_ACCENT_VOICES="en-US-South-female en-IE-male" tools/multi-accent-bench/run.sh
#
# 2. Synthetic auto-consistency (GLOSSO_ACCENT_SYNTH=1): the fixed phrase set is
#    re-synthesised with each registered Cartesia voice and scored against that
#    voice's own accent system. NOT the native metric (TTS, not human): it says
#    whether an accent's phonology matches how its own voice speaks.
#
#      GLOSSO_ACCENT_SYNTH=1 tools/keys/glosso-secret run CARTESIA_API_KEY -- \
#        tools/multi-accent-bench/run.sh
#      GLOSSO_ACCENT_SYNTH=1 GLOSSO_ACCENT_VOICES="en-US-South-female@d1d9c946" \
#        tools/keys/glosso-secret run CARTESIA_API_KEY -- tools/multi-accent-bench/run.sh
#
# A voice key is "<accent>-<gender>" (native) or "<system>@<voice>" (synth). The
# synth roster is tools/multi-accent-bench/registered-voices.tsv (30 registered
# voices, snapshot 2026-09-26). Inputs as tools/engine-bench/run.sh
# (GLOSSO_ASSETS, GLOSSO_DATA, GLOSSO_MODEL, GLOSSO_VOCAB, GLOSSO_CMUDICT) plus:
#   GLOSSO_ACCENT_VOICES     voices/sets to measure (default: shipped en-GB/en-US
#                            native; every registered voice in synth mode)
#   GLOSSO_ACCENT_IDS        id list (default: tools/engine-bench/native-ids.txt)
#   GLOSSO_ACCENT_ROSTER     synth voice map (default: registered-voices.tsv)
#   GLOSSO_ACCENT_MIN_RATE   optional wall on the own-system reach-98 rate; without
#                            it the run is report-only and never prints PASS
#   GLOSSO_ACCENT_WORK       scratch dir (default: <repo>/build/multi-accent-bench)
#
# A voice with no clips fails the run: an empty set must never look like evidence.

set -euo pipefail

fail() { echo "ERROR: $*" >&2; exit 1; }

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ASSETS="${GLOSSO_ASSETS:-$REPO/data/assets}"
WORK="${GLOSSO_ACCENT_WORK:-$REPO/build/multi-accent-bench}"
IDS="${GLOSSO_ACCENT_IDS:-$REPO/tools/engine-bench/native-ids.txt}"
SYNTH="${GLOSSO_ACCENT_SYNTH:-}"
ROSTER="${GLOSSO_ACCENT_ROSTER:-$REPO/tools/multi-accent-bench/registered-voices.tsv}"

if [ -n "$SYNTH" ]; then
    [ -f "$ROSTER" ] || fail "synth roster not found at $ROSTER (set GLOSSO_ACCENT_ROSTER)"
    VOICES="${GLOSSO_ACCENT_VOICES:-$(cut -f1 "$ROSTER")}"
else
    VOICES="${GLOSSO_ACCENT_VOICES:-en-GB-female en-GB-male en-US-female en-US-male}"
fi

if [ -n "${GLOSSO_DATA:-}" ]; then
    DATA="$GLOSSO_DATA"
else
    DATA=""
    for cand in "$REPO/../glosso-data" "$REPO/../../glosso-data" "$HOME/github/IgnacioLD/glosso-data"; do
        if [ -f "$cand/content/en/content.db" ]; then DATA="$cand"; break; fi
    done
    [ -n "$DATA" ] || fail "glosso-data not found; set GLOSSO_DATA to the checkout with content/en/content.db"
fi

MODEL="${GLOSSO_MODEL:-$ASSETS/wav2vec2_phoneme_int8_dyn_pertensor.onnx}"
VOCAB="${GLOSSO_VOCAB:-$ASSETS/phoneme_vocab.json}"
CMUDICT="${GLOSSO_CMUDICT:-$ASSETS/cmudict.dict}"
DB="${GLOSSO_CONTENT_DB:-$DATA/content/en/content.db}"
AUDIO_ROOT="${GLOSSO_AUDIO_ROOT:-$DATA/content/en/audio}"

[ -f "$MODEL" ] || fail "model not found at $MODEL (run tools/fetch-assets.sh, or set GLOSSO_MODEL)"
[ -f "$VOCAB" ] || fail "vocab not found at $VOCAB (set GLOSSO_VOCAB)"
[ -f "$CMUDICT" ] || fail "cmudict not found at $CMUDICT (set GLOSSO_CMUDICT)"
[ -f "$DB" ] || fail "content.db not found at $DB (set GLOSSO_DATA or GLOSSO_CONTENT_DB)"

if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21 ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21
fi

echo "== multi-accent bench ($([ -n "$SYNTH" ] && echo synth || echo native)) =="
echo "model     $MODEL"
echo "content   $DB"
echo "ids       $IDS"
echo "voices    $(echo $VOICES | wc -w | tr -d ' ')"
echo

MANIFESTS=""
for voice in $VOICES; do
    if [ -n "$SYNTH" ]; then
        key="${voice%%=*}"
        if [ "$key" != "$voice" ]; then
            voice_id="${voice#*=}"
            label="$key"
        else
            line="$(awk -F'\t' -v k="$key" '$1==k {print; exit}' "$ROSTER")"
            [ -n "$line" ] || fail "voice '$key' is not in $ROSTER; pass key=voiceId or add it to the roster"
            label="$(printf '%s' "$line" | cut -f2)"
            voice_id="$(printf '%s' "$line" | cut -f3)"
        fi
        dir="$WORK/$(printf '%s' "$key" | tr -c 'A-Za-z0-9._@-' '_')"
        if ! python3 "$REPO/tools/multi-accent-bench/synth_voice.py" \
            --db "$DB" --ids-file "$IDS" --voice-id "$voice_id" --voice-key "$label" --out "$dir"; then
            fail "voice '$key' produced no clips; the bench must never PASS on an empty set"
        fi
        MANIFESTS="${MANIFESTS:+$MANIFESTS;}$key=$dir/manifest.json"
    else
        if ! python3 "$REPO/tools/reference-selfscore/make_samples.py" --db "$DB" --audio-root "$AUDIO_ROOT" \
            --ids-file "$IDS" --voice "$voice" --out "$WORK/$voice"; then
            fail "voice '$voice' produced no clips; the bench must never PASS on an empty set (check the voice key exists in content.db and its audio is on disk)"
        fi
        MANIFESTS="${MANIFESTS:+$MANIFESTS;}$voice=$WORK/$voice/manifest.json"
    fi
done

LOG="$WORK/gradle.log"
export GLOSSO_ACCENT_MANIFESTS="$MANIFESTS"
export GLOSSO_MODEL="$MODEL" GLOSSO_VOCAB="$VOCAB" GLOSSO_CMUDICT="$CMUDICT"
export GLOSSO_REQUIRE_REFERENCE_SAMPLE=1

set +e
"$REPO/gradlew" :engine:jvmTest --tests 'com.glosso.engine.accent.MultiAccentNativeTest' \
    --rerun-tasks --info --console=plain >"$LOG" 2>&1
status=$?
set -e

echo
grep -E '^ *ACCENT98\|' "$LOG" | sed 's/^ *//' || true
grep -E '^ *ACCENT98_ACCENT\|' "$LOG" | sed 's/^ *//' || true
echo
echo "per clip: grep ACCENT_NATIVE $LOG"
if [ "$status" -ne 0 ]; then
    echo "MULTI-ACCENT BENCH FAIL (gradle exit $status) - $LOG"
elif [ -n "${GLOSSO_ACCENT_MIN_RATE:-}" ]; then
    echo "MULTI-ACCENT BENCH PASS (wall $GLOSSO_ACCENT_MIN_RATE)"
else
    echo "MULTI-ACCENT BENCH OK (report-only: no GLOSSO_ACCENT_MIN_RATE, not evidence to turn the flag on)"
fi
exit "$status"
