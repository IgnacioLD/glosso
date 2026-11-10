#!/usr/bin/env bash
#
# Downloads the runtime assets Glosso Pro serves from /assets:
#   - our selective int8 wav2vec2 phoneme model: the same artifact the server
#     loads, so the score is the same in the cloud and on the device. It is not
#     a Hugging Face artifact; it is produced by benchmark/finetune/quantize_selective.py
#     and published next to the other assets.
#   - its tokenizer vocabulary
#   - CMUdict (BSD-2-Clause), backing English G2P
#
# Usage: tools/fetch-assets.sh [output-dir]   (default: data/assets)
#
# Assets are large and are NOT committed to git; they live on the VPS. Override
# the origin with GLOSSO_ASSET_BASE (default https://api.glossostudio.com/assets).

set -euo pipefail

OUT="${1:-data/assets}"
ASSET_BASE="${GLOSSO_ASSET_BASE:-https://api.glossostudio.com/assets}"

MODEL_URL="$ASSET_BASE/wav2vec2_phoneme_int8_dyn_pertensor.onnx"
VOCAB_URL="$ASSET_BASE/phoneme_vocab.json"
CMUDICT_URL="$ASSET_BASE/cmudict.dict"

mkdir -p "$OUT"

echo "Model   -> $OUT/wav2vec2_phoneme_int8_dyn_pertensor.onnx"
curl -fL --progress-bar -o "$OUT/wav2vec2_phoneme_int8_dyn_pertensor.onnx" "$MODEL_URL"

echo "Vocab   -> $OUT/phoneme_vocab.json"
curl -fL --progress-bar -o "$OUT/phoneme_vocab.json" "$VOCAB_URL"

echo "CMUdict -> $OUT/cmudict.dict"
curl -fL --progress-bar -o "$OUT/cmudict.dict" "$CMUDICT_URL"

# --- Verify ---------------------------------------------------------------
model_bytes=$(wc -c < "$OUT/wav2vec2_phoneme_int8_dyn_pertensor.onnx")
vocab_bytes=$(wc -c < "$OUT/phoneme_vocab.json")
cmudict_bytes=$(wc -c < "$OUT/cmudict.dict")

min_model=$((300 * 1024 * 1024))
if (( model_bytes < min_model )); then
    echo "ERROR: model too small ($model_bytes bytes)" >&2
    exit 1
fi
if (( vocab_bytes < 1024 )); then
    echo "ERROR: vocab too small ($vocab_bytes bytes)" >&2
    exit 1
fi
if (( cmudict_bytes < 1024 * 1024 )); then
    echo "ERROR: cmudict too small ($cmudict_bytes bytes)" >&2
    exit 1
fi

echo
echo "OK:"
echo "  model    $model_bytes bytes"
echo "  vocab    $vocab_bytes bytes"
echo "  cmudict  $cmudict_bytes bytes"
