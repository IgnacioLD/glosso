"""Regenerate the English reference IPA as British (en-GB).

The published `content.db` carries the primary CMUdict reading, which is American
and rhotic: "near" ships as `nˈɪɹ`, so a correct British reading (`nˈiə`, no /ɹ/)
used to be charged as a missing sound. This tool rebuilds the reference for every
scorable item with espeak-ng's `en-gb` voice, the same phonemizer the acoustic
model was fine-tuned on, and writes:

  * `data/en_gb_ipa.tsv` - one row per scorable item (drill text, word lemma,
    dialogue turn, story/article) with the old and the new IPA, for review and
    as the data the engine regression test reads.
  * optionally, a **copy** of `content.db` with the `ipa` fields rewritten
    (`--apply`), so the owner can build a test asset without touching the
    tracked catalogue.

Licence boundary (docs/LICENSING.md 4.b): espeak-ng is GPL-3.0 and is a
build-time tool only. It is never linked, packaged or shipped; the IPA it emits
is data derived from the input text and only that text ships. The phonemizer
itself lives in `tools/content/phonemize.py` (the sanctioned entry point); this
tool reuses it and adds no new espeak reference.

Words are phonemized one at a time and joined with spaces, exactly like
`phonemize.py`, so the reference keeps one IPA token per source word and the
scorer's word alignment stays intact.

Usage (from tools/content, run with the same environment as phonemize.py - see
that module's docstring for the exact command):
  python en_gb_reference.py --db ../../../glosso-data/content/en/content.db
"""

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import phonemize

SCORABLE = {
    "DRILL": "text",
    "WORD": "lemma",
    "STORY": "paragraphs",
    "ARTICLE": "paragraphs",
    "DIALOGUE": "turns",
}

VOWELS = "aeiouɪɛæʌɔʊəɜɐɑɒɨᵻˈˌ"
CODA_RHO = re.compile(r"[ɹɝɚ](?![%s])" % VOWELS)
CODA_RHO_FINAL = re.compile(r"[ɹɝɚ]$")


def has_coda_rhotic(chunk):
    """A rhotic vowel, or a /ɹ/ not followed by a vowel, is a coda."""
    if "ɝ" in chunk or "ɚ" in chunk:
        return True
    return bool(CODA_RHO.search(chunk) or CODA_RHO_FINAL.search(chunk))


def item_targets(kind, data):
    """(key, text, old_ipa, setter) for every scorable string in an item."""
    if kind == "DRILL":
        return [(data["id"], data.get("text", ""), data.get("ipa", ""), "ipa")]
    if kind == "WORD":
        return [(data["id"], data.get("lemma", ""), data.get("ipa", ""), "ipa")]
    if kind == "DIALOGUE":
        out = []
        for i, turn in enumerate(data.get("turns", [])):
            out.append((f'{data["id"]}#t{i}', turn.get("text", ""), turn.get("ipa", ""), ("turn", i)))
        return out
    if kind in ("STORY", "ARTICLE"):
        paragraphs = data.get("paragraphs", [])
        return [(data["id"], " ".join(paragraphs), data.get("ipa", ""), "ipa")]
    return []


def fill_items(db_path, phonemize_backend, locale):
    conn = sqlite3.connect(db_path)
    rows = conn.execute(
        "select id, kind, data from content where kind in (%s) order by id"
        % ",".join("?" * len(SCORABLE)),
        tuple(SCORABLE),
    ).fetchall()
    conn.close()

    records = []
    for _id, kind, blob in rows:
        data = json.loads(blob)
        for key, text, old_ipa, target in item_targets(kind, data):
            if not text.strip():
                continue
            new_ipa = None
            if kind in ("STORY", "ARTICLE"):
                # One chunk per paragraph, joined, exactly like phonemize.py.
                chunks = []
                for paragraph in data.get("paragraphs", []):
                    ipa, _ = phonemize.phone_sentence(paragraph, phonemize_backend, locale)
                    if ipa:
                        chunks.append(ipa)
                if chunks:
                    new_ipa = " ".join(chunks)
            else:
                new_ipa, _ = phonemize.phone_sentence(text, phonemize_backend, locale)
            if not new_ipa:
                continue
            records.append(
                {
                    "key": key,
                    "kind": kind,
                    "text": " ".join(text.split()),
                    "old_ipa": old_ipa,
                    "new_ipa": new_ipa,
                    "target": target,
                }
            )
    return records


def write_tsv(path, records):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("key\tkind\told_ipa\tnew_ipa\n")
        for record in records:
            fh.write(
                "\t".join(
                    (record["key"], record["kind"], record["old_ipa"], record["new_ipa"])
                )
                + "\n"
            )
    return path


def apply_to_db(src, dst, records):
    """Copy src to dst and rewrite each item's ipa. Never writes to src."""
    if os.path.abspath(src) == os.path.abspath(dst):
        raise SystemExit("refusing to overwrite the source content.db in place")
    with open(src, "rb") as fh:
        digest_before = hashlib.sha256(fh.read()).hexdigest()
    with open(dst, "wb") as fh:
        fh.write(open(src, "rb").read())

    by_id = {}
    for record in records:
        by_id.setdefault(record["key"].split("#")[0], []).append(record)

    conn = sqlite3.connect(dst)
    changed = 0
    for item_id, blobs in conn.execute(
        "select id, data from content where id in (%s)" % ",".join("?" * len(by_id)),
        tuple(by_id),
    ).fetchall():
        data = json.loads(blobs)
        for record in by_id[item_id]:
            if record["target"] == "ipa":
                if data.get("ipa") != record["new_ipa"]:
                    data["ipa"] = record["new_ipa"]
                    changed += 1
            else:
                _, index = record["target"]
                data["turns"][index]["ipa"] = record["new_ipa"]
                changed += 1
        conn.execute(
            "update content set data=? where id=?",
            (json.dumps(data, ensure_ascii=False), item_id),
        )
    conn.commit()
    conn.close()

    with open(dst, "rb") as fh:
        digest_after = hashlib.sha256(fh.read()).hexdigest()
    print(f"\napplied {changed} ipa fields -> {dst}")
    print(f"  source sha256 {digest_before}")
    print(f"  output sha256 {digest_after}")


def stats(records):
    with_old = [r for r in records if r["old_ipa"].strip()]
    changed = [r for r in with_old if r["old_ipa"].strip() != r["new_ipa"].strip()]

    changed_words = set()
    coda_words = set()
    drill_word_coda_words = set()
    coda_tokens = 0
    coda_removed = 0
    drill_word_coda_tokens = 0
    drill_word_coda_removed = 0
    drill_word_items = set()
    for record in with_old:
        words = [phonemize.normalize_word(w) for w in record["text"].split()]
        words = [w for w in words if w]
        old_chunks = record["old_ipa"].split()
        new_chunks = record["new_ipa"].split()
        is_drill_word = record["kind"] in ("DRILL", "WORD")
        record_has_coda = False
        for word, old_chunk, new_chunk in zip(words, old_chunks, new_chunks):
            if old_chunk != new_chunk:
                changed_words.add(word)
            if has_coda_rhotic(old_chunk):
                coda_words.add(word)
                coda_tokens += 1
                record_has_coda = True
                if not has_coda_rhotic(new_chunk):
                    coda_removed += 1
                if is_drill_word:
                    drill_word_coda_words.add(word)
                    drill_word_coda_tokens += 1
                    if not has_coda_rhotic(new_chunk):
                        drill_word_coda_removed += 1
        if is_drill_word and record_has_coda:
            drill_word_items.add(record["key"])

    return {
        "records": len(records),
        "records_with_reference": len(with_old),
        "records_changed": len(changed),
        "distinct_words_changed": len(changed_words),
        "coda_rhotic_distinct_words": len(coda_words),
        "coda_rhotic_tokens": coda_tokens,
        "coda_rhotic_tokens_removed": coda_removed,
        "drill_word_coda_distinct_words": len(drill_word_coda_words),
        "drill_word_coda_tokens": drill_word_coda_tokens,
        "drill_word_coda_items": len(drill_word_items),
        "drill_word_coda_tokens_removed": drill_word_coda_removed,
        "sample_coda_words": sorted(coda_words)[:40],
    }


def probe_reference(probe_path, cmudict_path, locale, phonemize_backend, frequent=None):
    """canon token -> en-GB token, for the 810-clip AUC probe.

    The probe's `canon` is the production (CMUdict) reference. Each token is
    mapped back to a word through CMUdict's primary reading, phonemized en-GB,
    and replaced; tokens that are already plain words (hyphenated, contractions)
    are phonemized directly. No parameters are fitted.
    """
    import cmudict_reverse

    primary_to_word = cmudict_reverse.primary_ipa_to_word(cmudict_path, frequent)
    probe = json.load(open(probe_path, encoding="utf-8"))
    tokens = set()
    for row in probe["rows"]:
        if row.get("role") not in ("native", "nonnative"):
            continue
        tokens.update(row.get("canon", "").split())

    mapping = {}
    direct = 0
    for token in sorted(tokens):
        word = primary_to_word.get(token)
        if word is None:
            stripped = token.strip("'\u2019")
            if stripped.isascii() and re.search(r"[a-z]", stripped, re.I):
                word = stripped
                direct += 1
        if not word:
            continue
        ipa, _ = phonemize.phone_sentence(word, phonemize_backend, locale)
        if ipa:
            mapping[token] = ipa
    print(f"probe tokens {len(tokens)} mapped {len(mapping)} (direct {direct})")
    return mapping


def write_probe_tsv(path, mapping):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("us_ipa\ten_gb_ipa\n")
        for token in sorted(mapping):
            fh.write(f"{token}\t{mapping[token]}\n")
    return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True, help="content.db to read")
    ap.add_argument("--locale", default="en-gb")
    ap.add_argument("--out-tsv", default=os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "data", "en_gb_ipa.tsv"))
    ap.add_argument("--apply", default=None,
                    help="write a copy of content.db with the new IPA (never the source)")
    ap.add_argument("--probe", default=None, help="arbiter probe_tts_ref.json for the AUC remap")
    ap.add_argument("--cmudict", default=None, help="cmudict.dict (needed with --probe)")
    ap.add_argument("--frequent-words", default=None,
                    help="newline word list used to disambiguate CMUdict homophones")
    ap.add_argument("--probe-out", default=os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "data", "en_gb_probe.tsv"))
    args = ap.parse_args()

    backend = phonemize.load_phonemize()
    print(f"locale={args.locale} db={args.db}")
    records = fill_items(args.db, backend, args.locale)
    write_tsv(args.out_tsv, records)

    report = stats(records)
    print(f"\nrecords                          {report['records']}")
    print(f"records with a reference         {report['records_with_reference']}")
    print(f"records changed                  {report['records_changed']}")
    print(f"distinct words changed           {report['distinct_words_changed']}")
    print("coda rhotic (all scoreable kinds)")
    print(f"  distinct words                 {report['coda_rhotic_distinct_words']}")
    print(f"  tokens                         {report['coda_rhotic_tokens']}")
    print(f"  tokens whose coda r is removed {report['coda_rhotic_tokens_removed']}")
    print("coda rhotic, DRILL+WORD (owner's metric)")
    print(f"  distinct words                 {report['drill_word_coda_distinct_words']}")
    print(f"  tokens                         {report['drill_word_coda_tokens']}")
    print(f"  items                          {report['drill_word_coda_items']}")
    print(f"  tokens whose coda r is removed {report['drill_word_coda_tokens_removed']}")
    print(f"-> {args.out_tsv}")

    if args.apply:
        apply_to_db(args.db, args.apply, records)

    if args.probe:
        if not args.cmudict:
            raise SystemExit("--probe requires --cmudict")
        frequent = None
        if args.frequent_words:
            frequent = {w.strip().lower() for w in open(args.frequent_words, encoding="utf-8")}
        mapping = probe_reference(args.probe, args.cmudict, args.locale, backend, frequent)
        write_probe_tsv(args.probe_out, mapping)
        print(f"probe map -> {args.probe_out}")


if __name__ == "__main__":
    main()
