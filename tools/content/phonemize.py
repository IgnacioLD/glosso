"""Fill reference IPA with espeak-ng and build the per-language lexicon.

espeak-ng is the phonemizer the acoustic model was fine-tuned on, so its IPA is
the closest match to the model's phone inventory. It runs here as an external
authoring tool (not linked into the product), and only the generated data (IPA
strings + a word lexicon) ships.

Licence boundary (docs/LICENSING.md 4.b): espeak-ng is GPL-3.0 and is a
**build-time tool only**. It is never linked, packaged or shelled out from the
app, the server or their container; this script is the sole entry point and runs
via `uv run --with espeakng-loader --with phonemizer-fork`. The IPA it emits is
data derived from the input text, not a derivative work of espeak-ng, so the
generated data may be redistributed. Future languages should move to a
permissive build-time G2P (Epitran/Gruut, MIT); see docs/LICENSING.md 4.b/6.

Output:
  * `ipa` on every scorable item (drill text, word lemma, dialogue turn, story).
  * `<content>/<language>.lexicon` (`word ipa` per line) that the server loads as
    runtime G2P and `publishContent` uses to validate drills.

Words are phonemized one at a time and joined with spaces, so the reference keeps
one IPA token per word - the scorer's word alignment depends on it. This trades
away cross-word liaison; author short drills where that does not matter.

Usage:
  uv run --with espeakng-loader --with phonemizer-fork python phonemize.py \\
      --language fr --content ../../../glosso-data/content/fr
"""

import argparse
import glob
import json
import os

LIST_KEY = {
    "drills": "drills",
    "words": "words",
    "stories": "stories",
    "articles": "articles",
    "dialogues": "dialogues",
    "exercises": "exercises",
    "lessons": "lessons",
}

# Kinds that carry a scorable `text`/`ipa` pair.
SCORABLE = ("drills", "words", "stories", "articles", "dialogues")

# espeak-ng spellings that are one sound written in several characters, rewritten
# so the published reference parses into the phones the scorer compares. Russian:
# Ч is always soft (`tʃʲ` -> `tʃ`), `ɪ^` is the softness of a final РЬ and `u"`
# is У after a soft consonant. The engine applies the same folds to what the
# model hears (RU_SYMBOL_FOLDS in PhoneticComparator.kt); keep the two in step.
SYMBOL_FOLDS = {
    "ru": (("tʃʲ", "tʃ"), ("ɪ^", "ʲ"), ('u"', "u")),
}


# Russian content writes the stress as a combining acute (U+0301), a reading aid
# for the learner. espeak-ng does not read it: it breaks the word there and moves
# the stress ("го́род" comes out as "ɡʌrˈot"). The mark is removed before
# phonemizing and from the lexicon keys, and `stress_mismatch` reports the words
# where espeak-ng stresses another syllable than the author wrote.
STRESS_MARK = "\u0301"

# One-consonant prepositions have no vowel; phonemized alone espeak-ng spells the
# letter's name ("в" -> "vˈɛ"). They get the consonant itself, context-free like
# every lexicon entry (voicing assimilation to the next word is not written).
# Languages whose sentences are phonemized whole. Read alone, every word comes
# out stressed and unassimilated; in a sentence the unstressed words reduce
# ("не" is `nʲɪ`, "а" is `a`) and a preposition takes the voicing of the next
# word ("в центре" is `f tsˈɛntrʲi`), which is how a native says it and what the
# acoustic model, trained on espeak-ng sentences, writes. espeak-ng keeps one
# chunk per word for these; when it does not, the item falls back to word by word.
SENTENCE_LEVEL = {"ru"}

CLITICS = {
    "ru": {"в": "v", "к": "k", "с": "s", "б": "b", "ж": "ʒ"},
}

VOWELS = {
    "ru": ("аеёиоуыэюя", "aɑʌeɛiɪoɵuy"),
}


def stress_mismatch(marked, ipa, language):
    """True when `marked` writes the stress on another vowel than `ipa` stresses."""
    letters, phones = VOWELS.get(language, ("", ""))
    if STRESS_MARK not in marked or "ˈ" not in ipa or not letters:
        return False
    authored = sum(1 for ch in marked[:marked.index(STRESS_MARK)] if ch in letters) - 1
    before = ipa[:ipa.index("ˈ")]
    heard = sum(1 for ch in before if ch in phones)
    return authored != heard


def canonical_ipa(ipa, language):
    """The reference spelling of one espeak-ng IPA chunk for `language`."""
    for old, new in SYMBOL_FOLDS.get(language, ()):
        ipa = ipa.replace(old, new)
    return ipa


def load_phonemize():
    try:
        import espeakng_loader
        from phonemizer.backend.espeak.wrapper import EspeakWrapper
        from phonemizer import phonemize
    except ImportError as exc:  # pragma: no cover - authoring-time tool
        raise SystemExit(
            "phonemizer/espeakng_loader missing. Run with:\n"
            "  uv run --with espeakng-loader --with phonemizer-fork python phonemize.py ..."
        ) from exc
    EspeakWrapper.set_library(espeakng_loader.get_library_path())
    EspeakWrapper.set_data_path(espeakng_loader.get_data_path())
    return phonemize


def marked_word(raw):
    """The word as authored (lowercase, no surrounding punctuation), stress mark kept."""
    word = raw.lower().replace("\u2019", "'")
    keep = "'-" + STRESS_MARK
    start, end = 0, len(word)
    while start < end and not (word[start].isalpha() or word[start] in keep):
        start += 1
    while end > start and not (word[end - 1].isalpha() or word[end - 1] in keep):
        end -= 1
    word = word[start:end]
    # A token with no letter or digit (a spaced dash) is punctuation, not a word.
    return word if any(ch.isalnum() for ch in word) else ""


def normalize_word(raw):
    return marked_word(raw).replace(STRESS_MARK, "").strip(STRESS_MARK)


def load_overrides(content, language):
    """`<content>/<language>.overrides`: `word ipa` lines for the words espeak-ng gets wrong.

    The word is written as authored, stress mark included, so a homograph is
    corrected only in the reading the author marked ("дела́" and not "де́ла").
    """
    path = os.path.join(content, f"{language}.overrides")
    overrides = {}
    if os.path.isfile(path):
        for line in open(path, encoding="utf-8"):
            parts = line.split()
            if len(parts) == 2 and not line.startswith("#"):
                overrides[parts[0].lower()] = parts[1]
    return overrides


def phone_sentence(text, phonemize, locale, language=None, overrides=None):
    """One IPA chunk per word (or the word itself when espeak returns nothing)."""
    marked = [marked_word(w) for w in text.split()]
    marked = [m for m in marked if normalize_word(m)]
    words = [normalize_word(m) for m in marked]
    if not words:
        return None, {}
    clitics = CLITICS.get(language, {})
    overrides = overrides or {}
    phonemes = phonemize(
        words,
        language=locale,
        backend="espeak",
        with_stress=True,
        preserve_punctuation=False,
        # A loanword makes espeak-ng switch voice and wrap the word in flags
        # ("(en)bˈeɪbi(de)"); the flags are not phones and must not reach the IPA.
        # English callers keep the phonemizer default so their output is unchanged.
        language_switch="remove-flags" if language not in (None, "en") else "keep-flags",
    )
    phonemes = [clitics.get(w) or overrides.get(m) or canonical_ipa(p.strip(), language)
                for w, m, p in zip(words, marked, phonemes)]
    lexicon = {word: ipa for word, ipa in zip(reversed(words), reversed(phonemes)) if ipa}
    if language in SENTENCE_LEVEL and len(words) > 1:
        whole = phonemize(
            [" ".join(words)], language=locale, backend="espeak", with_stress=True,
            preserve_punctuation=False, language_switch="remove-flags",
        )[0].split()
        if len(whole) == len(words):
            chunks = [overrides.get(m) or canonical_ipa(p, language) for m, p in zip(marked, whole)]
            return " ".join(chunks), lexicon
    lexicon, chunks = {}, []
    for word, ipa in zip(words, phonemes):
        if ipa:
            lexicon.setdefault(word, ipa)
        chunks.append(ipa or word)
    return " ".join(chunks), lexicon


def _dump(value, level):
    """JSON like the authored banks: 2-space indent, short scalar lists inline."""
    pad = "  " * level
    if isinstance(value, dict):
        if not value:
            return "{}"
        body = ",\n".join(f'{pad}  {json.dumps(k, ensure_ascii=False)}: {_dump(v, level + 1)}' for k, v in value.items())
        return "{\n" + body + "\n" + pad + "}"
    if isinstance(value, list):
        if not value:
            return "[]"
        if all(not isinstance(x, (dict, list)) for x in value):
            return "[" + ", ".join(json.dumps(x, ensure_ascii=False) for x in value) + "]"
        body = ",\n".join(f"{pad}  {_dump(x, level + 1)}" for x in value)
        return "[\n" + body + "\n" + pad + "]"
    return json.dumps(value, ensure_ascii=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--language", required=True, help="target language code (content/<lang>)")
    ap.add_argument("--content", required=True, help="content dir (holds the kind folders)")
    ap.add_argument("--locale", default=None, help="espeak locale override, e.g. fr-fr")
    ap.add_argument("--force", action="store_true", help="overwrite IPA that is already present")
    args = ap.parse_args()

    locale = args.locale or {
        "en": "en-gb",
        "fr": "fr-fr",
        "de": "de",
        "es": "es",
        "ru": "ru",
        "it": "it",
        "pt": "pt",
    }.get(args.language, args.language)

    phonemize = load_phonemize()
    content = os.path.abspath(args.content)
    lexicon = {}
    filled = 0
    mismatches = {}
    overrides = load_overrides(content, args.language)
    phone = lambda text: phone_sentence(text, phonemize, locale, args.language, overrides)

    def check_stress(text, ipa=None):
        marked_words = [m for m in (marked_word(raw) for raw in text.split()) if m]
        # The reading in the sentence when there is one per word, else the word alone.
        chunks = (ipa or "").split()
        in_sentence = chunks if len(chunks) == len(marked_words) else [None] * len(marked_words)
        for marked, chunk in zip(marked_words, in_sentence):
            reading = chunk or lexicon.get(normalize_word(marked))
            if marked in overrides or not reading:
                continue
            if stress_mismatch(marked, reading, args.language):
                mismatches[marked] = reading

    def fill(item, text):
        nonlocal filled
        ipa, words = phone(text)
        # Always harvest the lexicon, even on a re-run where the item already has
        # IPA, so the published lexicon stays complete.
        lexicon.update(words)
        check_stress(text, ipa)
        if not args.force and (item.get("ipa") or "").strip():
            return
        if ipa:
            item["ipa"] = ipa
            filled += 1

    for folder in SCORABLE:
        key = LIST_KEY[folder]
        for path in sorted(glob.glob(os.path.join(content, folder, "*.json"))):
            bank = json.load(open(path, encoding="utf-8"))
            dirty = False
            for item in bank.get(key, []):
                if folder == "drills":
                    fill(item, item.get("text", ""))
                    # Focus words may not appear in the sentence; add them so the
                    # publisher's minimal-pair check can phonemize them.
                    for word in item.get("focus", []):
                        w = normalize_word(word)
                        if w and w not in lexicon:
                            _, words = phone(word)
                            lexicon.update(words)
                        check_stress(word)
                elif folder == "words":
                    fill(item, item.get("lemma", ""))
                elif folder == "dialogues":
                    for turn in item.get("turns", []):
                        fill(turn, turn.get("text", ""))
                elif folder in ("stories", "articles"):
                    para_ipa = []
                    for paragraph in item.get("paragraphs", []):
                        ipa, words = phone(paragraph)
                        lexicon.update(words)
                        check_stress(paragraph, ipa)
                        if ipa:
                            para_ipa.append(ipa)
                    if para_ipa and (args.force or not (item.get("ipa") or "").strip()):
                        item["ipa"] = " ".join(para_ipa)
                        filled += 1
                dirty = True
            if dirty:
                with open(path, "w", encoding="utf-8") as f:
                    f.write(_dump(bank, 0) + "\n")

    lexicon_path = os.path.join(content, f"{args.language}.lexicon")
    with open(lexicon_path, "w", encoding="utf-8") as f:
        for word in sorted(lexicon):
            f.write(f"{word} {lexicon[word]}\n")

    print(f"{filled} items filled with IPA, {len(lexicon)} lexicon entries -> {lexicon_path}")
    for marked, ipa in sorted(mismatches.items()):
        print(f"STRESS {marked}: espeak-ng says {ipa}")
    if mismatches:
        print(f"{len(mismatches)} words stressed differently from the authored mark; review them")


if __name__ == "__main__":
    main()
