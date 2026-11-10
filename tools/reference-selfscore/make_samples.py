#!/usr/bin/env python3
"""Builds the reference-self-score oracle sample.

The oracle checks the one thing the product must never get wrong: the engine's
own reference audio, scored against the expected IPA of its own text, has to come
out at ~100. If the reference clip fails against the reference IPA, nothing the
app says (praise or correction) is trustworthy.

This script picks a deterministic, varied sample from a published `content.db`
(words, drill sentences and dialogue turns), converts each clip to the WAV
16 kHz mono the recognizer expects, and writes `manifest.json` next to the WAVs.
The clips stay out of git; the engine test reads the manifest when it is pointed
at it and skips otherwise:

    python tools/reference-selfscore/make_samples.py \
        --db /path/to/content.db --audio-root /path/to/content/en/audio \
        --out /tmp/glosso-refscore

    GLOSSO_REFERENCE_MANIFEST=/tmp/glosso-refscore/manifest.json \
        ./gradlew :engine:jvmTest --tests '*ReferenceSelfScoreTest*'
"""

import argparse
import json
import os
import random
import sqlite3
import subprocess
import sys

SAMPLE_SEED = 20260923

# Voice preference: the course is en-GB, so the British voices are what the
# learner hears in "Normal / Lenta". Fall back to whichever voice the item has.
VOICE_PREFERENCE = ("en-GB-female", "en-GB-male", "en-US-female", "en-US-male")


def pick_audio(audio, only_voice=None):
    if only_voice:
        if only_voice in audio:
            return only_voice, audio[only_voice]
        # A voice rendered on disk but not listed in the map (the en-US clips while
        # the accent is not offered) follows the same file naming: swap the key.
        for key, rel in audio.items():
            if ":" not in key and key in os.path.basename(rel):
                return only_voice, rel.replace(key, only_voice)
        return None, None
    for voice in VOICE_PREFERENCE:
        if voice in audio:
            return voice, audio[voice]
    if audio:
        voice = next(iter(audio))
        return voice, audio[voice]
    return None, None


def convert(ogg, wav):
    subprocess.run(
        ["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", ogg, wav],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def collect(db_path, audio_root, only_voice=None):
    conn = sqlite3.connect(db_path)
    entries = []

    for cid, kind, text, data in conn.execute(
        "SELECT id, kind, text, data FROM content WHERE kind IN ('WORD','DRILL')"
    ):
        item = json.loads(data)
        ipa = (item.get("ipa") or "").strip()
        audio = item.get("audio") or {}
        if not ipa or not audio:
            continue
        voice, rel = pick_audio(audio, only_voice)
        if rel is None:
            continue
        entries.append({
            "id": cid,
            "kind": kind,
            "text": text,
            "ipa": ipa,
            "voice": voice,
            "audio": os.path.join(audio_root, os.path.basename(rel)),
        })

    for cid, text, data in conn.execute(
        "SELECT id, text, data FROM content WHERE kind = 'DIALOGUE'"
    ):
        item = json.loads(data)
        for n, turn in enumerate(item.get("turns") or []):
            ipa = (turn.get("ipa") or "").strip()
            audio = turn.get("audio") or {}
            if not ipa or not audio:
                continue
            voice, rel = pick_audio(audio, only_voice)
            if rel is None:
                continue
            entries.append({
                "id": f"{cid}.t{n}",
                "kind": "DIALOGUE_TURN",
                "text": turn.get("text", ""),
                "ipa": ipa,
                "voice": voice,
                "audio": os.path.join(audio_root, os.path.basename(rel)),
            })

    conn.close()
    return entries


def sample(entries, per_kind):
    rng = random.Random(SAMPLE_SEED)
    chosen = []
    for kind in ("WORD", "DRILL", "DIALOGUE_TURN"):
        pool = [e for e in entries if e["kind"] == kind]
        pool.sort(key=lambda e: e["id"])
        rng.shuffle(pool)
        chosen.extend(pool[: per_kind.get(kind, 0)])
    chosen.sort(key=lambda e: (e["kind"], e["id"]))
    return chosen


def read_ids(path):
    ids = []
    with open(path) as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#"):
                ids.append(line)
    return ids


def select_by_ids(entries, ids):
    by_id = {e["id"]: e for e in entries}
    # A dialogue-turn id is "<dialogue>.t<n>"; a bare dialogue id selects all turns.
    chosen = []
    for wanted in ids:
        if wanted in by_id:
            chosen.append(by_id[wanted])
            continue
        turns = [e for e in entries if e["kind"] == "DIALOGUE_TURN" and e["id"].startswith(wanted + ".t")]
        if turns:
            chosen.extend(turns)
        else:
            print(f"  id not found in the catalogue: {wanted}", file=sys.stderr)
    return chosen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True, help="published content.db")
    parser.add_argument("--audio-root", required=True, help="directory with the .ogg clips")
    parser.add_argument("--out", required=True, help="output directory for WAVs + manifest")
    parser.add_argument("--words", type=int, default=15)
    parser.add_argument("--drills", type=int, default=20)
    parser.add_argument("--turns", type=int, default=10)
    parser.add_argument("--ids-file", default=None,
                        help="select exactly these catalogue ids (one per line, # comments) "
                             "instead of the deterministic random sample")
    parser.add_argument("--voice", default=None,
                        help="use only this voice key (e.g. en-US-female) and skip items "
                             "without it; builds one accent's native set (docs/MULTI-ACCENT.md)")
    parser.add_argument("--allow-missing", action="store_true",
                        help="keep entries whose clip is absent instead of failing")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    entries = collect(args.db, args.audio_root, args.voice)
    if args.ids_file:
        chosen = select_by_ids(entries, read_ids(args.ids_file))
    else:
        chosen = sample(entries, {"WORD": args.words, "DRILL": args.drills,
                                  "DIALOGUE_TURN": args.turns})

    manifest = []
    missing = 0
    for e in chosen:
        ogg = e["audio"]
        if not os.path.isfile(ogg):
            missing += 1
            print(f"  missing clip: {ogg}", file=sys.stderr)
            if args.allow_missing:
                continue
            continue
        wav = os.path.join(args.out, e["id"] + ".wav")
        convert(ogg, wav)
        manifest.append({
            "id": e["id"],
            "kind": e["kind"],
            "voice": e["voice"],
            "text": e["text"],
            "ipa": e["ipa"],
            "wav": wav,
        })

    if not manifest:
        print(
            "ERROR: no clips built (0 clips); refusing to write an empty manifest. "
            f"voice={args.voice or 'default'}, {missing} clip(s) missing. "
            "Check the voice key exists in content.db and its audio is on disk.",
            file=sys.stderr,
        )
        sys.exit(1)

    manifest_path = os.path.join(args.out, "manifest.json")
    with open(manifest_path, "w") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)

    print(f"wrote {len(manifest)} clips ({missing} missing) -> {manifest_path}")


if __name__ == "__main__":
    main()
