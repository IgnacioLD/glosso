"""Turn the British reference IPA into learner-dictionary (textbook) notation.

The product keeps two IPA representations and they do not have to match:

  * **internal (scoring)**: the `ipa` field of `content.db`, the reference the
    engine scores with. This tool never touches it. Scoring stays exactly as it
    is; the internal reference only has to score well, not to be pretty.
  * **presentation (display)**: the same British reading written the way Oxford
    Learner's / Cambridge / Longman write it, shown when the learner turns
    phonetics on (`PhoneticsPreferences`, off by default). It is stored in each
    item's `displayIpa` field and is never sent to the scorer.

The presentation form is derived from the espeak-ng `en-gb` phonemization (the
same build-time tool as `phonemize.py`, GPL-3.0, never linked or shipped: only
the generated IPA text is distributed; see docs/LICENSING.md 4.b). espeak already
gives a British, non-rhotic reading with length marks and stress; this module
only rewrites the handful of symbols where the learner's dictionary uses a
different convention, so the learner recognises what they studied:

  ɹ -> r    dictionaries print /r/, not the IPA turned r
  ɛ -> e    DRESS: Oxford/Cambridge write /e/ (bed /bed/), not /ɛ/
  a -> æ    TRAP: /æ/ (cat /kæt/), keeping /a/ inside the aɪ/aʊ diphthongs
  ɐ -> ə    unstressed central vowel folded into the schwa the learner expects
  i -> ɪ    with a following ə: NEAR is /ɪə/ (near /nɪə/), but the long /iːə/
            of "Leo" is left alone
  n̩ -> n    syllabic n (wasn't), written as a plain /n/

Everything else espeak emits is already the dictionary convention: the vowels
/iː ɪ ɒ ɔː ʊ uː ʌ ɑː ɜː ə/, the diphthongs /eɪ aɪ ɔɪ əʊ aʊ ɪə eə ʊə/, the
stress marks ˈ ˌ and the length mark ː. The mapping is deliberately small and
familiar-first: where a dictionary simplifies the phonetic reality for the
learner, so does this (e.g. it does not add the optional word-final "(r)").

Input is the reference file `data/en_gb_ipa.tsv` that `en_gb_reference.py`
writes (one row per scorable item: `key kind old_ipa new_ipa`). Output:

  * `data/en_gb_display.tsv` - `key kind internal_ipa display_ipa`, the before
    (what the app shows today, the CMUdict primary) and after (textbook) for
    every scorable item, for review and as the data the apply step reads.
  * optionally, a **copy** of `content.db` with a `displayIpa` field added next
    to each item's `ipa` (`--apply`), never the source.

Usage (same environment as `en_gb_reference.py`; the sample also needs the
espeak backend of `phonemize.py` - see that module's docstring for the command):

  python textbook_ipa.py --reference data/en_gb_ipa.tsv
  python textbook_ipa.py --reference data/en_gb_ipa.tsv \
      --db ../../../glosso-data/content/en/content.db --apply /tmp/content.display.db
  python textbook_ipa.py --sample data/en_gb_display_sample.tsv \
      --cmudict /path/to/cmudict.dict
"""

import argparse
import json
import os
import re
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# The convention rewrites, in order. See the module docstring for the rationale.
# `a(?![ɪʊ])` keeps the /a/ of the PRICE/MOUTH diphthongs (aɪ, aʊ) intact; the
# negative lookbehind keeps the long /iːə/ from turning into /ɪːə/.
REWRITES = (
    (re.compile("n\u0329"), "n"),          # syllabic n
    (re.compile("ɹ"), "r"),
    (re.compile("(?<!\u02d0)i(?=ə)"), "ɪ"),  # NEAR: iə -> ɪə, not iːə
    (re.compile("ɛ"), "e"),
    (re.compile("a(?![ɪʊ])"), "æ"),
    (re.compile("ɐ"), "ə"),
)

# The symbols the textbook form may contain, for the golden test that the mapping
# is complete (a stray espeak-only symbol would fail loudly instead of shipping).
TEXTBOOK_ONLY = set("ɹɛɐ")


def to_textbook(ipa):
    """Rewrite one espeak en-gb IPA string into learner-dictionary notation."""
    out = ipa
    for pattern, repl in REWRITES:
        out = pattern.sub(repl, out)
    return out


def assert_textbook(ipa):
    """Raise when a rewritten string still carries an espeak-only symbol."""
    leftover = TEXTBOOK_ONLY.intersection(ipa)
    if leftover:
        raise SystemExit(f"textbook rewrite left espeak-only symbols {sorted(leftover)} in {ipa!r}")


def read_reference(path):
    """Rows from `en_gb_ipa.tsv`: (key, kind, old_ipa, new_ipa)."""
    records = []
    with open(path, encoding="utf-8") as fh:
        for i, line in enumerate(fh):
            if i == 0:
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 4:
                continue
            records.append((parts[0], parts[1], parts[2], parts[3]))
    return records


def display_records(reference):
    """(key, kind, internal_ipa, display_ipa) with the textbook rewrite applied."""
    out = []
    for key, kind, internal, new_ipa in reference:
        display = to_textbook(new_ipa)
        assert_textbook(display)
        out.append((key, kind, internal, display))
    return out


def write_tsv(path, records):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("key\tkind\tinternal_ipa\tdisplay_ipa\n")
        for key, kind, internal, display in records:
            fh.write(f"{key}\t{kind}\t{internal}\t{display}\n")
    return path


def apply_to_db(src, dst, records):
    """Copy `src` to `dst` and add the `displayIpa` field per item. Never in place."""
    if os.path.abspath(src) == os.path.abspath(dst):
        raise SystemExit("refusing to overwrite the source content.db in place")
    with open(src, "rb") as fh:
        data = fh.read()
    with open(dst, "wb") as fh:
        fh.write(data)

    by_id = {}
    for key, _kind, _internal, display in records:
        by_id.setdefault(key.split("#")[0], []).append((key, display))

    conn = sqlite3.connect(dst)
    added = 0
    for item_id, blobs in conn.execute(
        "select id, data from content where id in (%s)" % ",".join("?" * len(by_id)),
        tuple(by_id),
    ).fetchall():
        item = json.loads(blobs)
        for key, display in by_id[item_id]:
            if "#t" in key:
                index = int(key.split("#t")[1])
                turn = item.get("turns", [])[index]
                if turn.get("displayIpa") != display:
                    turn["displayIpa"] = display
                    added += 1
            else:
                if item.get("displayIpa") != display:
                    item["displayIpa"] = display
                    added += 1
        conn.execute(
            "update content set data=? where id=?",
            (json.dumps(item, ensure_ascii=False), item_id),
        )
    conn.commit()
    conn.close()
    print(f"\nadded {added} displayIpa fields -> {dst}")
    return added


# The published bank folders and the list each holds. Articles carry no `ipa`,
# so they get no display form either.
BANK_LISTS = {
    "drills": "drills",
    "words": "words",
    "dialogues": "dialogues",
    "stories": "stories",
}

# The `"ipa"` field of an item, in whatever whitespace the file uses.
IPA_FIELD = re.compile(r'"ipa"\s*:\s*"(?:[^"\\]|\\.)*"')

# A `displayIpa` field right after an `"ipa"` one, from a previous run.
DISPLAY_AFTER = re.compile(r'\s*,\s*"displayIpa"\s*:\s*"(?:[^"\\]|\\.)*"')


def _insert_display(text, displays):
    """Put a `displayIpa` field after each `"ipa"` occurrence, in order.

    A surgical text edit, not a JSON re-serialisation: the bank files are
    hand-authored (compact dialogue turns) or written by kotlinx (4-space), and
    a round-trip would reformat them. Idempotent: when a `displayIpa` already
    follows the `"ipa"` field it is updated in place, never duplicated. Returns
    (new_text, occurrences, changed).
    """
    out = []
    last = 0
    count = 0
    changed = 0
    for match in IPA_FIELD.finditer(text):
        out.append(text[last:match.end()])
        display = displays[count] if count < len(displays) else None
        existing = DISPLAY_AFTER.match(text, match.end())
        if existing is not None:
            replacement = (
                ', "displayIpa": ' + json.dumps(display, ensure_ascii=False)
                if display else existing.group(0)
            )
            if replacement != existing.group(0):
                changed += 1
            out.append(replacement)
            last = existing.end()
        elif display:
            out.append(', "displayIpa": ' + json.dumps(display, ensure_ascii=False))
            changed += 1
            last = match.end()
        else:
            last = match.end()
        count += 1
    out.append(text[last:])
    return "".join(out), count, changed


def apply_to_banks(bank_dir, records):
    """Write `displayIpa` into a bank directory (published `dist` or authored).

    `content.db` is rebuilt from these banks, so the field must live here to
    survive a publish. Idempotent: re-running updates in place, never
    duplicates. Returns, per folder, how many fields changed and how many items
    had no display form (a gap, reported, never left silent).
    """
    by_key = {key: display for key, _kind, _internal, display in records}
    report = {}
    for folder, list_key in BANK_LISTS.items():
        directory = os.path.join(bank_dir, folder)
        if not os.path.isdir(directory):
            continue
        written = 0
        gaps = 0
        for name in sorted(os.listdir(directory)):
            if not name.endswith(".json"):
                continue
            path = os.path.join(directory, name)
            text = open(path, encoding="utf-8").read()
            bank = json.loads(text)
            displays = []
            for item in bank.get(list_key, []):
                if folder == "dialogues":
                    for i in range(len(item.get("turns", []))):
                        displays.append(by_key.get(f'{item["id"]}#t{i}'))
                else:
                    displays.append(by_key.get(item.get("id")))
            gaps += sum(1 for d in displays if not d)
            new_text, count, changed = _insert_display(text, displays)
            if count != len(displays):
                raise SystemExit(
                    f"expected {len(displays)} ipa fields in {path}, found {count}"
                )
            if new_text != text:
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(new_text)
                written += changed
        report[folder] = (written, gaps)
    total = sum(w for w, _ in report.values())
    print(f"\napplied displayIpa in {bank_dir}: {total} fields")
    for folder, (written, gaps) in report.items():
        print(f"  {folder:10s} {written} changed, {gaps} without a display form")
    return report


def sample(words, cmudict_path, locale="en-gb"):
    """Before (CMUdict primary, the internal reference) vs after (textbook)."""
    import cmudict_reverse
    import phonemize

    backend = phonemize.load_phonemize()
    entries = cmudict_reverse.read_entries(cmudict_path)

    rows = []
    for word in words:
        readings = entries.get(word.lower())
        before = cmudict_reverse.word(readings[0]) if readings else ""
        raw, _ = phonemize.phone_sentence(word, backend, locale)
        after = to_textbook(raw) if raw else ""
        rows.append((word, before, after))
    return rows


def write_sample(path, rows):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("word\tbefore_cmudict\tafter_textbook\n")
        for word, before, after in rows:
            fh.write(f"{word}\t/{before}/\t/{after}/\n")
    return path


SAMPLE_WORDS = [
    # The owner's hard cases.
    "near", "thirty", "water", "apartheid", "creutzfeldt", "tao",
    # Schwa.
    "about", "banana", "teacher", "doctor",
    # Diphthongs.
    "gate", "price", "choice", "goat", "mouth", "square", "cure",
    # Vowel contrasts a learner meets from lesson one.
    "red", "man", "hot", "thought", "bird", "cart", "boot", "book", "cup",
    "happy", "computer",
    # Words whose British reading differs from the American one.
    "tomato", "vase",
]


def main():
    ap = argparse.ArgumentParser()
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--reference", default=os.path.join(here, "data", "en_gb_ipa.tsv"),
                    help="en-GB reference TSV written by en_gb_reference.py")
    ap.add_argument("--out-tsv", default=os.path.join(here, "data", "en_gb_display.tsv"))
    ap.add_argument("--apply", default=None, help="write a copy of content.db with displayIpa")
    ap.add_argument("--db", default=None, help="content.db to copy (required with --apply)")
    ap.add_argument("--banks", default=None,
                    help="published bank dir to stamp displayIpa into (the durable path)")
    ap.add_argument("--sample", default=None, help="write the before/after sample TSV here")
    ap.add_argument("--cmudict", default=None, help="cmudict.dict (required with --sample)")
    ap.add_argument("--words", default=None, help="sample words, newline-separated (default embedded)")
    args = ap.parse_args()

    if args.reference and os.path.isfile(args.reference):
        records = display_records(read_reference(args.reference))
        write_tsv(args.out_tsv, records)
        print(f"{len(records)} display rows -> {args.out_tsv}")
        if args.apply:
            if not args.db:
                raise SystemExit("--apply requires --db")
            apply_to_db(args.db, args.apply, records)
        if args.banks:
            apply_to_banks(args.banks, records)
    elif args.apply or args.sample or args.banks:
        raise SystemExit(f"missing reference file {args.reference}")

    if args.sample:
        if not args.cmudict:
            raise SystemExit("--sample requires --cmudict")
        words = SAMPLE_WORDS
        if args.words:
            words = [w.strip() for w in open(args.words, encoding="utf-8") if w.strip()]
        rows = sample(words, args.cmudict)
        write_sample(args.sample, rows)
        print(f"{len(rows)} sample rows -> {args.sample}")


if __name__ == "__main__":
    main()
