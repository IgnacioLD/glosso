"""Build the accent reference of a content bank: `<content>/accent-reference.json`.

For every scorable sentence of the bank (drill text, word lemma, dialogue turn)
and for every accent wording of one (`<content>/accents/<accent>.json`), the
sentence phonemized by espeak-ng in the convention the acoustic model
transcribes in. The model was fine-tuned on espeak labels and writes any English
accent down in espeak's en-GB convention, so that reading is the reference a
native of any accent matches (docs/MULTI-ACCENT.md). The dictionary reading in
`ipa` stays the canonical one.

The output is one generated file, keyed by the sentence itself:

  { "version": 1, "language": "en",
    "references": { "en-GB": { "The load on the truck is light.": "ðə lˈəʊd ...", ... } } }

The authored JSON is not touched. `./gradlew :server:publishContent` merges the
file (and the accent wordings) into the published items as `accentIpa`,
`wording` and `accentParagraphs`, and fails if a sentence has no reference, so a
stale file cannot ship.

Licence boundary (docs/LICENSING.md 4.b): espeak-ng is GPL-3.0 and a build-time
tool only. This script reuses `phonemize.py`, the sanctioned entry point; only
the IPA it emits ships.

Usage (from tools/content), after editing content or an accent file:
  uv run --python 3.11 --with espeakng-loader --with phonemizer-fork \\
      python accent_reference.py --language en --content ../../../glosso-data/content/en
"""

import argparse
import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import phonemize

# Accent key -> espeak locale of the reference published for a language: the
# convention the acoustic model writes that language in.
MODEL_REFERENCE = {
    "en": {"en-GB": "en-gb"},
}

OUTPUT = "accent-reference.json"


def scorable_sentences(content):
    """Every sentence the app can score, in a stable order."""
    seen = {}

    def add(text):
        text = (text or "").strip()
        if text:
            seen.setdefault(text, None)

    for folder in ("drills", "words", "dialogues"):
        key = phonemize.LIST_KEY[folder]
        for path in sorted(glob.glob(os.path.join(content, folder, "*.json"))):
            with open(path, encoding="utf-8") as fh:
                bank = json.load(fh)
            for item in bank.get(key, []):
                if folder == "drills":
                    add(item.get("text"))
                elif folder == "words":
                    add(item.get("lemma"))
                else:
                    for turn in item.get("turns", []):
                        add(turn.get("text"))
    # An accent's own wording of a sentence is scored too. Paragraph keys
    # ("#p") are readings, which are not scored.
    for path in sorted(glob.glob(os.path.join(content, "accents", "*.json"))):
        with open(path, encoding="utf-8") as fh:
            for key, text in json.load(fh).get("text", {}).items():
                if "#p" not in key:
                    add(text)
    return list(seen)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--language", required=True, help="target language code (content/<lang>)")
    ap.add_argument("--content", required=True, help="content dir (holds the kind folders)")
    args = ap.parse_args()

    locales = MODEL_REFERENCE.get(args.language)
    if not locales:
        raise SystemExit(f"no model reference convention declared for '{args.language}'")
    backend = phonemize.load_phonemize()
    content = os.path.abspath(args.content)
    sentences = scorable_sentences(content)

    references = {}
    for accent, locale in locales.items():
        table = {}
        for text in sentences:
            ipa, _ = phonemize.phone_sentence(text, backend, locale)
            if ipa:
                table[text] = ipa
        references[accent] = dict(sorted(table.items()))

    out = os.path.join(content, OUTPUT)
    with open(out, "w", encoding="utf-8") as fh:
        json.dump({"version": 1, "language": args.language, "references": references},
                  fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    sizes = ", ".join(f"{accent} {len(table)}" for accent, table in references.items())
    print(f"{len(sentences)} sentences -> {out} ({sizes})")


if __name__ == "__main__":
    main()
