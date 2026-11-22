#!/usr/bin/env bash
#
# Per-language phone-recognizer bench (see README.md). Every step is cached under
# build/language-bench, so a re-run only does what is missing.
#
#   tools/keys/glosso-secret run CARTESIA_API_KEY -- tools/language-bench/run.sh
#
# Without CARTESIA_API_KEY the TTS clips are skipped (FLEURS only).
#   GLOSSO_LANGBENCH_MODELS   models to run (default: all in bench.py MODELS)
#   GLOSSO_LANGBENCH_PERF=0   skip the speed/memory runs (they want an idle machine)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="$HERE/.venv/bin/python"
if [ ! -x "$PY" ]; then
  uv venv --python 3.12 "$HERE/.venv"
  uv pip install --python "$PY" -r "$HERE/requirements.txt"
fi
command -v "${ESPEAK_NG:-espeak-ng}" >/dev/null || { echo "ERROR: espeak-ng not found (brew install espeak-ng)" >&2; exit 1; }

MODELS="${GLOSSO_LANGBENCH_MODELS:-w2v2-lv60 w2v2-en-int8 zipa-cr-small zipa-cr-large zipa-t-small zipa-cr-ns-small whisper-ipa-base}"
bench() { "$PY" "$HERE/bench.py" "$@"; }

bench data
if [ -n "${CARTESIA_API_KEY:-}" ]; then bench tts; else echo "no CARTESIA_API_KEY: skipping the TTS clips"; fi
for model in $MODELS; do
  # whisper is autoregressive and ~25x slower than ZIPA: 30 FLEURS clips per language are enough to rule it out.
  case "$model" in whisper-*) bench transcribe "$model" --max-fleurs 30 ;; *) bench transcribe "$model" ;; esac
done

if [ "${GLOSSO_LANGBENCH_PERF:-1}" != 0 ]; then
  # w2v2-en-int8 and zipa-cr-ns-small share the graph of w2v2-lv60 / zipa-cr-small.
  for model in $MODELS; do
    case "$model" in w2v2-en-int8|zipa-cr-ns-small) continue ;; esac
    for threads in 1 0; do bench perf "$model" --threads "$threads" | tail -1; done
  done > "$HERE/results/perf.jsonl"
fi

bench score
bench engine
