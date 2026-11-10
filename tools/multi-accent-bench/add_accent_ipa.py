"""Add whole-sentence accent references to a bench manifest.

For every clip of a `make_samples.py` manifest, writes
`accentIpa: {"en-GB": ..., "en-US": ...}`: the sentence phonemized by espeak-ng
with that accent's voice, one IPA chunk per word. `MultiAccentNativeTest` passes
them to `MultiAccentScorer` as `accentReferences`, the same thing `/v1/score`
does with `ScoreRequest.accentIpa`.

espeak-ng is GPL and a build-time tool only (docs/LICENSING.md 4.b): this script
reuses `tools/content/phonemize.py`, the sanctioned entry point, and only the
IPA it emits is kept.

Usage (from the repo root):
  uv run --python 3.11 --with espeakng-loader --with phonemizer-fork \
      python tools/multi-accent-bench/add_accent_ipa.py <manifest.json>...
"""

import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "content"))

import phonemize  # noqa: E402

LOCALES = {"en-GB": "en-gb", "en-US": "en-us"}


def main(paths):
    backend = phonemize.load_phonemize()
    for path in paths:
        with open(path, encoding="utf-8") as fh:
            manifest = json.load(fh)
        for sample in manifest:
            sample["accentIpa"] = {
                accent: phonemize.phone_sentence(sample["text"], backend, locale)[0]
                for accent, locale in LOCALES.items()
            }
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(manifest, fh, ensure_ascii=False, indent=1)
        print(f"{path}: {len(manifest)} clips")


if __name__ == "__main__":
    main(sys.argv[1:])
