#!/usr/bin/env python3
"""Render one voice's clips for the multi-accent bench.

Auto-consistency harness: it synthesises the fixed phrase set
(`tools/engine-bench/native-ids.txt`) with a single Cartesia voice and writes a
`manifest.json` the engine's `MultiAccentNativeTest` reads. The engine then
scores every clip against the accent system of that voice, so the metric says
whether the phonology of an accent matches how its own voice actually speaks.

It is NOT the native metric of docs/MULTI-ACCENT.md: these are TTS clips, not
recordings of human natives, so the number is auto-consistency, not `own98`.

The voice's reference audio lives in the server registry; here we re-synth the
phrases with the same Cartesia voice id, which is what makes the measurement
repeatable without shipping the owner's recordings.

    tools/keys/glosso-secret run CARTESIA_API_KEY -- \
      python3 tools/multi-accent-bench/synth_voice.py \
        --db /path/content.db --ids-file tools/engine-bench/native-ids.txt \
        --voice-id <uuid> --voice-key '<accent>-<gender>' --out /tmp/v

Output is 16 kHz mono PCM WAV, the recognizer's input. Re-running reuses the
cache in the output dir, so a bench pass never pays Cartesia twice.
"""

import argparse
import concurrent.futures
import datetime
import hashlib
import json
import os
import sqlite3
import sys
import threading
import time
import wave

# The Cartesia client already used for the catalogue's reference audio; the key
# is redeemed once for a short-lived token, never sent to the TTS endpoint.
# Homebrew Python ships no CA store and its certifi can be broken, so point the
# default context at the system bundle before the client builds it.
if not os.environ.get("SSL_CERT_FILE") and os.path.isfile("/etc/ssl/cert.pem"):
    os.environ["SSL_CERT_FILE"] = "/etc/ssl/cert.pem"
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "content")))
from cartesia_a1_audio import CartesiaClient, CartesiaError, read_api_key  # noqa: E402


class CartesiaIdVoiceClient(CartesiaClient):
    """Cartesia client that sends the production TTS payload.

    `CartesiaTtsProvider` (the server) sends `voice: {"id": <uuid>}`. The shared
    client in tools/content sends the newer `{"mode": "id", ...}`. In 2026-09 the
    two shapes render the same voice differently: `mode: id` is slower and the
    phoneme model mis-hears male voices with it, while the `id` shape reproduces
    the clips already published in content.db. The bench has to measure what the
    product synthesises, so it sends the `id` shape.
    """

    def synthesize(self, text, voice_id, speed):
        body = json.dumps({
            "model_id": self.model,
            "transcript": text,
            "voice": {"id": voice_id},
            "language": "en",
            "output_format": {"container": "raw", "encoding": "pcm_s16le", "sample_rate": SAMPLE_RATE},
            "generation_config": {"speed": speed},
        }).encode()
        last = None
        for attempt in range(5):
            status, data, _ = self._request(
                "POST", f"{self.base}/tts/bytes",
                {
                    "Authorization": f"Bearer {self.token()}",
                    "Content-Type": "application/json",
                    "Cartesia-Version": self.version,
                },
                body,
            )
            if status == 200:
                return data
            if status in (401, 403):
                with self._lock:
                    self._token = None
            last = CartesiaError(status, data.decode("utf-8", "replace"))
            if status in (401, 403, 429) or 500 <= status < 600:
                time.sleep(min(2 ** attempt, 15))
                continue
            raise last
        raise last

SAMPLE_RATE = 16000
PROVIDER = "cartesia"
DEFAULT_MODEL = "sonic-3.6"
DEFAULT_API_VERSION = "2026-08-14"
DEFAULT_BASE_URL = "https://api.cartesia.ai"
CACHE_NAME = ".synth-cache.json"
PROVENANCE_NAME = ".synth-provenance.json"


def read_ids(path):
    ids = []
    with open(path) as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#"):
                ids.append(line)
    return ids


def catalogue(db_path, wanted):
    """text + canonical ipa per id, from WORD/DRILL rows and DIALOGUE turns."""
    wanted = set(wanted)
    entries = {}
    conn = sqlite3.connect(db_path)
    for cid, kind, text, data in conn.execute(
        "SELECT id, kind, text, data FROM content WHERE kind IN ('WORD','DRILL')"
    ):
        if cid in wanted:
            entries[cid] = {"id": cid, "kind": kind, "text": text, "ipa": (json.loads(data).get("ipa") or "").strip()}
    for cid, text, data in conn.execute("SELECT id, text, data FROM content WHERE kind = 'DIALOGUE'"):
        for n, turn in enumerate(json.loads(data).get("turns") or []):
            tid = f"{cid}.t{n}"
            if tid in wanted:
                entries[tid] = {
                    "id": tid,
                    "kind": "DIALOGUE_TURN",
                    "text": turn.get("text", ""),
                    "ipa": (turn.get("ipa") or "").strip(),
                }
    conn.close()
    return entries


def fingerprint(entry, voice_id, model, speed):
    parts = [entry["text"], entry["ipa"], voice_id, str(speed), PROVIDER, model, f"pcm_s16le:{SAMPLE_RATE}", "voice:id"]
    return hashlib.sha1("|".join(parts).encode("utf-8")).hexdigest()


def write_wav(path, pcm):
    if len(pcm) == 0:
        raise RuntimeError("empty audio")
    with wave.open(path, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(SAMPLE_RATE)
        out.writeframes(pcm)
    return len(pcm) / 2 / SAMPLE_RATE


def load_json(path):
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    return {}


def save_json(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=1, sort_keys=True)
    os.replace(tmp, path)


def resolve_api_key(keys_file):
    key = os.environ.get("CARTESIA_API_KEY", "").strip()
    if key:
        return key
    return read_api_key(keys_file)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--db", required=True, help="published content.db")
    parser.add_argument("--ids-file", required=True, help="catalogue ids to render, one per line")
    parser.add_argument("--voice-id", required=True, help="Cartesia voice UUID")
    parser.add_argument("--voice-key", required=True, help="voice label for the manifest (the set key)")
    parser.add_argument("--out", required=True, help="output dir for WAVs + manifest")
    parser.add_argument("--keys-file", default=os.path.expanduser("~/glosso-keys/cartesia.env"))
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--api-version", default=DEFAULT_API_VERSION)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--speed", type=float, default=1.0)
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--allow-missing", action="store_true", help="skip ids absent from content.db")
    parser.add_argument("--force", action="store_true", help="ignore the synth cache")
    args = parser.parse_args(argv)

    os.makedirs(args.out, exist_ok=True)
    ids = read_ids(args.ids_file)
    entries = catalogue(args.db, ids)
    ordered = []
    for cid in ids:
        entry = entries.get(cid)
        if entry is None or not entry["text"] or not entry["ipa"]:
            if not args.allow_missing:
                raise SystemExit(f"id missing text/ipa in content.db: {cid}")
            continue
        ordered.append(entry)
    if not ordered:
        raise SystemExit(f"no renderable id (0 clips) for voice {args.voice_key}; refusing to write an empty manifest")

    cache_path = os.path.join(args.out, CACHE_NAME)
    provenance_path = os.path.join(args.out, PROVENANCE_NAME)
    cache = load_json(cache_path)
    provenance = load_json(provenance_path)
    client = CartesiaIdVoiceClient(resolve_api_key(args.keys_file), args.base_url, args.api_version, args.model)
    lock = threading.Lock()
    stats = {"generated": 0, "reused": 0, "failed": 0, "chars": 0, "seconds": 0.0, "errors": []}
    started = time.time()

    def render(entry):
        wav_path = os.path.join(args.out, entry["id"] + ".wav")
        fp = fingerprint(entry, args.voice_id, args.model, args.speed)
        stored = cache.get(entry["id"])
        if not args.force and stored and stored.get("fp") == fp and os.path.isfile(wav_path):
            with lock:
                stats["reused"] += 1
            return
        try:
            pcm = client.synthesize(entry["text"], args.voice_id, args.speed)
            seconds = write_wav(wav_path, pcm)
        except Exception as exc:  # noqa: BLE001 - report per clip and keep going
            with lock:
                stats["failed"] += 1
                stats["errors"].append(f"{entry['id']}: {exc}")
            return
        with lock:
            stats["generated"] += 1
            stats["chars"] += len(entry["text"])
            stats["seconds"] += seconds
            cache[entry["id"]] = {"fp": fp, "voice_id": args.voice_id, "chars": len(entry["text"])}
            provenance[entry["id"]] = {
                "provider": PROVIDER, "model": args.model, "voice_id": args.voice_id,
                "voice_key": args.voice_key, "speed": args.speed, "characters": len(entry["text"]),
                "duration_s": round(seconds, 3),
                "date": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            }

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        for _ in pool.map(render, ordered):
            pass

    save_json(cache_path, cache)
    save_json(provenance_path, provenance)

    if stats["errors"]:
        for line in stats["errors"][:10]:
            print("  " + line, file=sys.stderr)

    manifest = []
    for entry in ordered:
        wav_path = os.path.join(args.out, entry["id"] + ".wav")
        if not os.path.isfile(wav_path):
            continue
        manifest.append({
            "id": entry["id"], "kind": entry["kind"], "voice": args.voice_key,
            "text": entry["text"], "ipa": entry["ipa"], "wav": wav_path,
        })
    if not manifest:
        raise SystemExit(f"every clip failed for voice {args.voice_key} ({stats['failed']} errors); no manifest")
    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)

    elapsed = time.time() - started
    print(
        f"[synth] {args.voice_key}: {len(manifest)} clips ({stats['reused']} cached) in {elapsed:.1f}s; "
        f"{stats['failed']} failed -> {os.path.join(args.out, 'manifest.json')}"
    )
    return 1 if stats["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
