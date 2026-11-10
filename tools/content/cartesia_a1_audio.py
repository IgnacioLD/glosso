#!/usr/bin/env python3
"""Render the English reference audio with Cartesia.

Standalone: it does not depend on the server TTS layer. It redeems the API key
for a short-lived access token, synthesises one clip per line with a small voice
palette, writes the catalogue's OGG Vorbis 24 kHz mono files and keeps a
fingerprint cache so re-running never regenerates a clip it already made.

The app consumes per-voice clips, one file per turn / paragraph / sentence:

  drills     <id>.<voicekey>[.slow].ogg          (whole sentence)
  words      <id>.<voicekey>.ogg                 (lemma)
  stories    <id>.p<i>.<voicekey>.ogg            (per paragraph)
  dialogues  <id>.t<i>.<voicekey>.ogg            (per turn, one voice per speaker)

`voicekey` is `<accent>-<gender>` (`en-GB-female`, `en-US-male`); `--accent` picks
the accent and its voice palette (default `en-GB`). Dialogues are
NOT concatenated into one file: the app plays them turn by turn
(`DialogueModel.turns[i].audio`), so a concatenated file would break playback.
What makes a dialogue multi-voice is that each turn carries its own speaker's
voice; see `assign_dialogue_voices`.

Voices, en-GB (British English):
  female  Cora   c46cf1f6-49a1-4d67-9a57-ff859a4046d3
          Julia  273f9ef7-9fc2-4def-88bb-ab108c6249ca
  male    Clive  b24f41fd-00a3-4cd8-992a-a0c9f13f3ef1
          Quentin 5568a7df-e5ab-4442-9fae-2e9ba1b15ad8

Voices, en-US (General American; the owner's selection, the same ids as the
engine's `AccentCatalog`):
  female  American female 1  db6b0ed5-d5d3-463d-ae85-518a07d3c2b4
          American female 2  9626c31c-bec5-4cca-baa8-f8ba9e84c8bc
  male    American male 2    47c38ca4-5f35-497b-b1a3-415245fb35e1
          American male 3    30894953-bcce-41fe-892c-15ce19c843ff

Narration (drills/words/stories/articles) gets exactly one female and one male
voice per item, rotated over the whole palette from the item id (`voice_pair`),
so all four voices are heard across the catalogue and a re-run keeps the same
audio. Dialogues cast each speaker with their own voice: two women get Cora and
Julia, two men Clive and Quentin, never the same voice twice for one gender.

What the app actually consumes (audited in androidApp, see docs/TTS-CARTESIA-EN.md):

  drills     audio["en-GB-<gender>"] and audio["en-GB-<gender>:slow"]  -> both voices + slow
  words      audio["en-GB-<gender>"]                                  -> both voices
  stories    paragraphAudio[i]["en-GB-<gender>"]                      -> both voices
  articles   paragraphAudio[i]["en-GB-<gender>"]                      -> both voices
  dialogues  turns[i].audio["en-GB-<gender>"]                         -> per speaker
  exercises/lessons: no audio field                                   -> nothing

Slow is rendered only for drills (`--slow`): the drill Slow button appears only
when a `:slow` key exists (`GlossoApp.kt`); stories/articles/dialogues slow by
playback rate and need no `:slow` file. Only `en-GB` ships (the pack comments
`en-US` out).

Output format: the pipeline's OGG Vorbis 24 kHz mono. Cartesia does not emit
OGG, so the PCM (`pcm_s16le` raw: no RIFF header, so no placeholder-length trap)
is written straight to OGG. Duration is always derived from the byte count, never
from a header. Run with an interpreter that has soundfile/numpy (the glosso-pro
tools venv: tools/content/.venv/bin/python).

Usage:
  tools/content/.venv/bin/python tools/content/cartesia_a1_audio.py \
      --content-dir /path/to/glosso-data/content/en --levels A1 --dry-run
  ... --levels A1,A2,B1,B2 --voices female,male --slow   # full English
  ... --accent en-US --kinds drills,words,stories,dialogues,articles \
      --levels A1,A2,B1,B2 --voices female,male --slow   # full American

The API key is read from the CARTESIA_API_KEY environment variable when set
(`tools/keys/glosso-secret run CARTESIA_API_KEY -- ...`), else from --keys-file.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import dataclasses
import datetime
import hashlib
import json
import os
import ssl
import sys
import threading
import time
import urllib.error
import urllib.request

SAMPLE_RATE = 24000
PCM_MIME = "audio/pcm"
CACHE_NAME = ".cartesia-cache.json"
PROVENANCE_NAME = ".cartesia-provenance.json"

PROVIDER = "cartesia"
DEFAULT_MODEL = "sonic-3.6"
DEFAULT_API_VERSION = "2026-08-14"
DEFAULT_BASE_URL = "https://api.cartesia.ai"
SLOW_SPEED = 0.8
CHECKPOINT_EVERY = 100

# The full palette per accent. Order matters: it is the deterministic assignment
# order. The en-GB lists are frozen: their order feeds the published clips.
PALETTES = {
    "en-GB": {
        "FEMALE": [
            ("cora", "c46cf1f6-49a1-4d67-9a57-ff859a4046d3"),
            ("julia", "273f9ef7-9fc2-4def-88bb-ab108c6249ca"),
        ],
        "MALE": [
            ("clive", "b24f41fd-00a3-4cd8-992a-a0c9f13f3ef1"),
            ("quentin", "5568a7df-e5ab-4442-9fae-2e9ba1b15ad8"),
        ],
    },
    "en-US": {
        "FEMALE": [
            ("american-female-1", "db6b0ed5-d5d3-463d-ae85-518a07d3c2b4"),
            ("american-female-2", "9626c31c-bec5-4cca-baa8-f8ba9e84c8bc"),
        ],
        "MALE": [
            ("american-male-2", "47c38ca4-5f35-497b-b1a3-415245fb35e1"),
            ("american-male-3", "30894953-bcce-41fe-892c-15ce19c843ff"),
        ],
    },
    "ru-RU": {
        "FEMALE": [
            ("tatiana", "064b17af-d36b-4bfb-b003-be07dba1b649"),
            ("irina", "642014de-c0e3-4133-adc0-36b5309c23e6"),
        ],
        "MALE": [
            ("sergei", "1e4176b1-3db9-44d6-a601-4fe68b041942"),
            ("dmitri", "888b7df4-e165-4852-bfec-0ab2b96aaa46"),
        ],
    },
}
DEFAULT_ACCENT = "en-GB"


# --------------------------------------------------------------------------- voices


def infer_gender(name: str) -> str:
    """Best effort when the content does not declare a speaker's gender."""
    low = (name or "").lower()
    female_hints = ("mother", "mum", "mom", "sister", "woman", "girl", "lady",
                    "wife", "daughter", "grandmother", "grandma", "aunt")
    return "FEMALE" if any(h in low for h in female_hints) else "MALE"


def cast_genders(item: dict) -> "list[tuple[str, str]]":
    """Speaker -> gender, in cast order, appending turns-only speakers in order."""
    order: list[str] = []
    gender: dict[str, str] = {}
    for member in item.get("cast", []) or []:
        name = member.get("name")
        if not name or name in gender:
            continue
        declared = (member.get("gender") or "").upper()
        gender[name] = declared if declared in ("FEMALE", "MALE") else infer_gender(name)
        order.append(name)
    for turn in item.get("turns", []) or []:
        name = turn.get("speaker")
        if name and name not in gender:
            gender[name] = infer_gender(name)
            order.append(name)
    return [(name, gender[name]) for name in order]


def assign_dialogue_voices(item: dict, genders: set[str],
                           accent: str = DEFAULT_ACCENT) -> dict[str, tuple[str, str]]:
    """Deterministic speaker -> (voice name, voice id).

    Each distinct speaker gets a distinct voice within their gender: the first
    woman is the first female voice of the accent's palette and the second the
    next one (Cora then Julia in en-GB), and likewise for men. More speakers
    than voices cycle (and are reported). The cast order
    is authored and stable, so the same item always maps the same way.

    `genders` is the set of genders this pass renders; a speaker of a gender not
    in it is read by the available pool (a reduced-budget pass keeps the
    speaker-distinct rule, only within one gender).
    """
    voices: dict[str, tuple[str, str]] = {}
    used = {"FEMALE": 0, "MALE": 0}
    for name, gender in cast_genders(item):
        effective = gender if gender in genders else next(iter(sorted(genders)))
        pool = PALETTES[accent][effective]
        voices[name] = pool[used[effective] % len(pool)]
        used[effective] += 1
    return voices


# --------------------------------------------------------------------------- plan


@dataclasses.dataclass(frozen=True)
class Clip:
    name: str
    text: str
    voice_name: str
    voice_id: str
    gender: str
    speed: float
    kind: str
    item_id: str
    level: str
    chars: int
    # The item's authored narrator gender ("female"/"male"), or "" when it names none.
    narrator: str = ""


KIND_FILES = {
    "drills": ("drills", "drills"),
    "words": ("words", "words"),
    "stories": ("stories", "stories"),
    "dialogues": ("dialogues", "dialogues"),
    "articles": ("articles", "articles"),
}


# Russian content is authored with the stress written as a combining acute
# (U+0301) for the learner. It is a reading aid, not spelling: the voice gets the
# plain word, unless --stress-marks asks to send it as authored.
STRESS_MARK = "\u0301"
KEEP_STRESS_MARKS = False


def _clip(name, text, voice, speed, kind, item_id, level, gender, narrator="") -> Clip:
    if not KEEP_STRESS_MARKS:
        text = text.replace(STRESS_MARK, "")
    return Clip(name=name, text=text, voice_name=voice[0], voice_id=voice[1],
                gender=gender, speed=speed, kind=kind, item_id=item_id,
                level=level, chars=len(text), narrator=narrator)


def voice_pair(item_id: str, accent: str = DEFAULT_ACCENT) -> tuple[tuple[str, str], tuple[str, str]]:
    """The (female, male) narrator pair for an item, rotated across the palette.

    Deterministic from the item id: the low bit picks the female voice, the next
    bit the male one (Cora/Julia and Clive/Quentin in en-GB), so the voices
    spread over the catalogue while each item
    still uses exactly one female and one male voice. Stable across runs, and the
    voice feeds the cache fingerprint, so a re-run keeps the same audio.
    """
    digest = int(hashlib.sha1(item_id.encode("utf-8")).hexdigest(), 16)
    female, male = PALETTES[accent]["FEMALE"], PALETTES[accent]["MALE"]
    return female[digest % len(female)], male[(digest >> 1) % len(male)]


def narrator_voices_for(item_id: str, genders: set[str],
                        accent: str = DEFAULT_ACCENT) -> list[tuple[str, str, str, str]]:
    """(gender, voice name, voice id) for the narrator genders, rotated per item."""
    female, male = voice_pair(item_id, accent)
    chosen = {"FEMALE": female, "MALE": male}
    out = []
    for gender in ("FEMALE", "MALE"):
        if gender in genders:
            name, vid = chosen[gender]
            out.append((gender, name, vid))
    return out


def load_wording(content_dir: str, accent: str) -> dict[str, str]:
    """The accent's own wording, when the content ships one.

    `<content-dir>/accents/<accent>.json` maps a clip key (drill or word id,
    `<dialogueId>#t<turn>`, `<storyOrArticleId>#p<paragraph>`) to the text that
    accent says instead of the authored one ("lorry" is "truck" in en-US), so an
    American voice never reads British wording. Items without an entry are read
    as authored.
    """
    path = os.path.join(content_dir, "accents", f"{accent}.json")
    if not os.path.isfile(path):
        return {}
    with open(path, encoding="utf-8") as fh:
        return json.load(fh).get("text", {})


def build_plan(content_dir: str, kinds: list[str], levels: set[str],
               narrator_genders: set[str], dialogue_genders: set[str],
               slow: bool, accent: str = DEFAULT_ACCENT) -> list[Clip]:
    clips: list[Clip] = []
    wording = load_wording(content_dir, accent)
    for kind in kinds:
        folder, key = KIND_FILES[kind]
        directory = os.path.join(content_dir, folder)
        if not os.path.isdir(directory):
            continue
        for filename in sorted(os.listdir(directory)):
            if not filename.endswith(".json"):
                continue
            with open(os.path.join(directory, filename), encoding="utf-8") as fh:
                data = json.load(fh)
            for item in data.get(key, []):
                if item.get("level") not in levels:
                    continue
                narrator = (item.get("narrator") or "").lower()
                if kind == "drills":
                    for gender, name, vid in narrator_voices_for(item["id"], narrator_genders, accent):
                        clip_name = f"{item['id']}.{accent}-{gender.lower()}"
                        text = wording.get(item["id"], item["text"])
                        clips.append(_clip(clip_name, text, (name, vid), 1.0, kind,
                                           item["id"], item["level"], gender.lower(), narrator))
                        if slow:
                            clips.append(_clip(clip_name + ".slow", text, (name, vid), SLOW_SPEED,
                                               kind, item["id"], item["level"], gender.lower(), narrator))
                elif kind == "words":
                    for gender, name, vid in narrator_voices_for(item["id"], narrator_genders, accent):
                        clips.append(_clip(f"{item['id']}.{accent}-{gender.lower()}",
                                           wording.get(item["id"], item["lemma"]), (name, vid), 1.0, kind, item["id"],
                                           item["level"], gender.lower(), narrator))
                elif kind in ("stories", "articles"):
                    for i, paragraph in enumerate(item.get("paragraphs", [])):
                        for gender, name, vid in narrator_voices_for(item["id"], narrator_genders, accent):
                            clips.append(_clip(f"{item['id']}.p{i}.{accent}-{gender.lower()}",
                                               wording.get(f"{item['id']}#p{i}", paragraph), (name, vid), 1.0,
                                               kind, item["id"],
                                               item["level"], gender.lower(), narrator))
                elif kind == "dialogues":
                    voices = assign_dialogue_voices(item, dialogue_genders, accent)
                    palette = PALETTES[accent]
                    fallback = palette["MALE"][0] if "MALE" in dialogue_genders else palette["FEMALE"][0]
                    for i, turn in enumerate(item.get("turns", [])):
                        voice = voices.get(turn.get("speaker"), fallback)
                        gender = "female" if voice in palette["FEMALE"] else "male"
                        clips.append(_clip(f"{item['id']}.t{i}.{accent}-{gender}",
                                           wording.get(f"{item['id']}#t{i}", turn["text"]), voice, 1.0, kind,
                                           item["id"],
                                           item["level"], gender))
    return clips


NARRATED_KINDS = ("drills", "words", "stories", "articles")


def one_narrator(plan: list[Clip], is_cached) -> list[Clip]:
    """Keeps one narrator gender per narrated item; dialogues keep a voice per speaker.

    One person says a drill, a word or a story, so it gets one voice: the gender
    the item names (`"narrator"`, required where the text shows the speaker's
    gender, like Russian "я была"), else the gender whose clips are already
    rendered and current (no credits spent), else one picked from the item id so
    the catalogue mixes both voices.
    """
    by_item: dict[tuple[str, str], list[Clip]] = {}
    for clip in plan:
        if clip.kind in NARRATED_KINDS:
            by_item.setdefault((clip.kind, clip.item_id), []).append(clip)
    keep: set[str] = set()
    for (_, item_id), clips in by_item.items():
        genders = sorted({c.gender for c in clips})
        chosen = clips[0].narrator if clips[0].narrator in genders else ""
        if not chosen:
            ready = [g for g in genders if all(is_cached(c) for c in clips if c.gender == g)]
            if ready:
                chosen = ready[0] if len(ready) == 1 else _hashed_gender(item_id, ready)
            else:
                chosen = _hashed_gender(item_id, genders)
        keep.update(c.name for c in clips if c.gender == chosen)
    return [c for c in plan if c.kind not in NARRATED_KINDS or c.name in keep]


def _hashed_gender(item_id: str, genders: list[str]) -> str:
    digest = int(hashlib.sha1(item_id.encode("utf-8")).hexdigest(), 16)
    return genders[(digest >> 2) % len(genders)]


def retire_unplanned(plan: list[Clip], audio_dir: str, retired_dir: str, dry_run: bool) -> int:
    """Moves the clips of the planned items that the plan no longer reads out of the audio dir.

    A text that changed, a dropped narrator voice or a slow variant the app now
    makes by rate would otherwise still be wired into the catalogue next to the
    current clips. Files are moved, never deleted.
    """
    planned = {c.name for c in plan}
    prefixes = tuple(sorted({c.item_id + "." for c in plan}))
    moved = 0
    for filename in sorted(os.listdir(audio_dir)):
        if not filename.endswith(".ogg") or not filename.startswith(prefixes):
            continue
        name = filename[: -len(".ogg")]
        if name in planned:
            continue
        moved += 1
        if not dry_run:
            os.makedirs(retired_dir, exist_ok=True)
            os.replace(os.path.join(audio_dir, filename), os.path.join(retired_dir, filename))
    return moved


def fingerprint(clip: Clip, model: str) -> str:
    parts = [clip.text, clip.voice_id, str(clip.speed), PROVIDER, model, "raw:pcm_s16le:24000"]
    return hashlib.sha1("|".join(parts).encode("utf-8")).hexdigest()


# --------------------------------------------------------------------------- cartesia


def _ssl_context() -> ssl.SSLContext:
    """Trust the system store, or certifi when the interpreter ships no CAs."""
    try:
        import certifi
        return ssl.create_default_context(cafile=certifi.where())
    except Exception:  # noqa: BLE001 - fall back to the default context
        return ssl.create_default_context()


_SSL_CONTEXT = _ssl_context()


class CartesiaError(RuntimeError):
    def __init__(self, status: int, body: str):
        super().__init__(f"cartesia HTTP {status}: {body[:300]}")
        self.status = status


class CartesiaClient:
    def __init__(self, api_key: str, base_url: str, version: str, model: str, language: str = "en"):
        self.language = language
        self.api_key = api_key
        self.base = base_url.rstrip("/")
        self.version = version
        self.model = model
        self._lock = threading.Lock()
        self._token = None
        self._expires_at = 0.0

    def _request(self, method: str, url: str, headers: dict, body: bytes | None):
        req = urllib.request.Request(url, data=body, method=method)
        for key, value in headers.items():
            req.add_header(key, value)
        try:
            with urllib.request.urlopen(req, timeout=120, context=_SSL_CONTEXT) as resp:
                return resp.status, resp.read(), dict(resp.headers)
        except urllib.error.HTTPError as err:
            return err.code, err.read(), dict(err.headers)

    def _redeem(self) -> str:
        payload = json.dumps({"grants": {"tts": True}, "expires_in": 120}).encode()
        status, data, _ = self._request(
            "POST", f"{self.base}/access-token",
            {
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
                "Cartesia-Version": self.version,
            },
            payload,
        )
        if status != 200:
            raise CartesiaError(status, data.decode("utf-8", "replace"))
        return json.loads(data)["token"]

    def token(self) -> str:
        with self._lock:
            now = time.time()
            if self._token is None or now >= self._expires_at - 20:
                self._token = self._redeem()
                self._expires_at = now + 120
            return self._token

    def synthesize(self, text: str, voice_id: str, speed: float) -> bytes:
        body = json.dumps({
            "model_id": self.model,
            "transcript": text,
            "voice": {"mode": "id", "id": voice_id},
            "language": self.language,
            "output_format": {"container": "raw", "encoding": "pcm_s16le", "sample_rate": SAMPLE_RATE},
            "generation_config": {"speed": speed},
        }).encode()
        last = None
        for attempt in range(5):
            token = self.token()
            status, data, _ = self._request(
                "POST", f"{self.base}/tts/bytes",
                {
                    "Authorization": f"Bearer {token}",
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


# Voices do not come out at the same level: one Russian voice (Dmitri) is about
# 28 dB quieter than the other three, which in a lesson sounds like a broken
# clip. The accents listed here are peak-normalised to TARGET_PEAK_DB when they
# are written. en-GB and en-US are not listed on purpose: their clips are
# published as rendered, and normalising only the new ones would make one voice
# jump in level between clips.
NORMALIZED_ACCENTS = {"ru-RU"}
TARGET_PEAK_DB = -2.0


def pcm_to_ogg(pcm: bytes, path: str, normalize: bool = False) -> "tuple[float, float]":
    """Writes the clip and returns its duration and the gain applied, in dB."""
    import numpy as np
    import soundfile as sf

    audio = np.frombuffer(pcm, dtype="<i2").astype("float32") / 32768.0
    if len(audio) == 0:
        raise RuntimeError("empty audio")
    gain_db = 0.0
    peak = float(np.abs(audio).max())
    if normalize and peak > 0:
        gain = (10 ** (TARGET_PEAK_DB / 20)) / peak
        audio = audio * gain
        gain_db = float(20 * np.log10(gain))
    sf.write(path, audio, SAMPLE_RATE, format="OGG", subtype="VORBIS")
    return len(audio) / SAMPLE_RATE, gain_db


# --------------------------------------------------------------------------- persistence


def load_json(path: str) -> dict:
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    return {}


def save_json(path: str, data: dict) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=1, sort_keys=True)
    os.replace(tmp, path)


def _is_cached(cache: dict, clip: Clip, model: str, audio_dir: str) -> bool:
    entry = cache.get(clip.name)
    return bool(entry and entry.get("fp") == fingerprint(clip, model)
                and os.path.isfile(os.path.join(audio_dir, clip.name + ".ogg")))


# --------------------------------------------------------------------------- main


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--content-dir", required=True, help="glosso-data/content/<lang>")
    parser.add_argument("--audio-dir", help="where the .ogg live (default <content-dir>/audio)")
    parser.add_argument("--keys-file", default=os.path.expanduser("~/glosso-keys/cartesia.env"))
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--api-version", default=DEFAULT_API_VERSION)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--accent", default=DEFAULT_ACCENT, choices=sorted(PALETTES),
                        help="accent to render: its voice palette and the <accent>-<gender> voice key")
    parser.add_argument("--kinds", default="drills,words,stories,dialogues")
    parser.add_argument("--levels", default="A1")
    parser.add_argument("--voices", default="female",
                        help="narrator genders for drills/words/stories: female | male | female,male")
    parser.add_argument("--dialogue-genders", default="female,male",
                        help="genders allowed in a dialogue's per-speaker palette")
    parser.add_argument("--stress-marks", action="store_true",
                        help="send the authored stress marks (U+0301) to the voice instead of removing them")
    parser.add_argument("--slow", action="store_true", help="also render the .slow variants (off by default)")
    parser.add_argument("--narrator", choices=("both", "one"), default="both",
                        help="narrated items (drills, words, stories, articles) in every --voices gender, "
                             "or in one: the item's `narrator`, else an already rendered one, else by id")
    parser.add_argument("--retire", action="store_true",
                        help="after a clean run, move clips of the planned items the plan no longer reads "
                             "(changed text, dropped voice, slow variants) out of the audio dir")
    parser.add_argument("--retired-dir", default="", help="where --retire moves clips (default <audio-dir>-retired)")
    parser.add_argument("--sample", type=int, default=0, help="render only the first N clips")
    parser.add_argument("--filter", default="", help="only clips whose name contains this substring")
    parser.add_argument("--only-voice", default="", help="only clips read by this voice name (e.g. dmitri)")
    parser.add_argument("--dry-run", action="store_true", help="count characters and stop")
    parser.add_argument("--plan-json", help="write the full clip plan here")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--force", action="store_true", help="ignore the cache")
    parser.add_argument("--max-chars", type=int, default=0, help="abort if the plan exceeds this many characters")
    args = parser.parse_args(argv)
    global KEEP_STRESS_MARKS
    KEEP_STRESS_MARKS = args.stress_marks

    audio_dir = args.audio_dir or os.path.join(args.content_dir, "audio")
    cache_path = os.path.join(audio_dir, CACHE_NAME)
    kinds = [k.strip() for k in args.kinds.split(",") if k.strip()]
    levels = {l.strip() for l in args.levels.split(",") if l.strip()}
    narrator_genders = {g.strip().upper() for g in args.voices.split(",") if g.strip()}
    dialogue_genders = {g.strip().upper() for g in args.dialogue_genders.split(",") if g.strip()}

    plan = build_plan(args.content_dir, kinds, levels, narrator_genders, dialogue_genders, args.slow,
                      args.accent)
    if args.filter:
        plan = [c for c in plan if args.filter in c.name]
    if args.only_voice:
        plan = [c for c in plan if c.voice_name == args.only_voice]
    if args.sample:
        plan = plan[: args.sample]

    cache = load_json(cache_path)
    if args.narrator == "one":
        plan = one_narrator(plan, lambda c: _is_cached(cache, c, args.model, audio_dir))

    by_kind: dict[str, dict[str, int]] = {}
    for clip in plan:
        stats = by_kind.setdefault(clip.kind, {"clips": 0, "chars": 0})
        stats["clips"] += 1
        stats["chars"] += clip.chars
    total_chars = sum(c.chars for c in plan)

    print(f"plan: {len(plan)} clips, {total_chars} characters (levels={sorted(levels)})")
    for kind in sorted(by_kind):
        s = by_kind[kind]
        print(f"  {kind:10s} {s['clips']:5d} clips  {s['chars']:7d} chars")

    voice_mix: dict[str, int] = {}
    for clip in plan:
        voice_mix[clip.voice_name] = voice_mix.get(clip.voice_name, 0) + 1
    print("  voices: " + ", ".join(f"{k} {v}" for k, v in sorted(voice_mix.items())))

    if args.plan_json:
        with open(args.plan_json, "w", encoding="utf-8") as fh:
            json.dump([dataclasses.asdict(c) for c in plan], fh, indent=1)

    if args.dry_run:
        changed = [c for c in plan
                   if args.force or not _is_cached(cache, c, args.model, audio_dir)]
        changed_chars = sum(c.chars for c in changed)
        print(f"  would generate {len(changed)} clips ({changed_chars} chars), "
              f"reuse {len(plan) - len(changed)}")
        if args.retire:
            moved = retire_unplanned(plan, audio_dir, args.retired_dir or audio_dir + "-retired", dry_run=True)
            print(f"  would retire {moved} clips the plan no longer reads")
        return 0
    if not plan:
        print("nothing to do")
        return 0
    if args.max_chars and total_chars > args.max_chars:
        print(f"refusing: {total_chars} characters exceed --max-chars {args.max_chars}")
        return 2

    api_key = read_api_key(args.keys_file)
    os.makedirs(audio_dir, exist_ok=True)
    provenance_path = os.path.join(audio_dir, PROVENANCE_NAME)
    provenance = load_json(provenance_path)

    client = CartesiaClient(api_key, args.base_url, args.api_version, args.model,
                            language=args.accent.split("-")[0])
    lock = threading.Lock()
    stats = {"generated": 0, "reused": 0, "chars_generated": 0, "failed": 0, "duration": 0.0, "errors": []}
    started = time.time()

    def render(clip: Clip) -> None:
        out_path = os.path.join(audio_dir, clip.name + ".ogg")
        fp = fingerprint(clip, args.model)
        if not args.force and _is_cached(cache, clip, args.model, audio_dir):
            with lock:
                stats["reused"] += 1
            return
        try:
            pcm = client.synthesize(clip.text, clip.voice_id, clip.speed)
            duration, gain_db = pcm_to_ogg(pcm, out_path, normalize=args.accent in NORMALIZED_ACCENTS)
        except Exception as exc:  # noqa: BLE001 - report per clip and keep going
            with lock:
                stats["failed"] += 1
                stats["errors"].append(f"{clip.name}: {exc}")
            return
        with lock:
            stats["generated"] += 1
            stats["chars_generated"] += clip.chars
            stats["duration"] += duration
            cache[clip.name] = {"fp": fp, "voice_id": clip.voice_id, "chars": clip.chars,
                                "voice_name": clip.voice_name}
            provenance[clip.name] = {
                "provider": PROVIDER, "model": args.model, "voice": clip.voice_name,
                "voice_id": clip.voice_id, "gender": clip.gender, "speed": clip.speed,
                "level": clip.level, "kind": clip.kind, "item": clip.item_id,
                "characters": clip.chars, "duration_s": round(duration, 3), "gain_db": round(gain_db, 1),
                "date": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            }
            # Checkpoint: a long run that dies must not lose (and pay twice for)
            # the clips it already rendered.
            if stats["generated"] % CHECKPOINT_EVERY == 0:
                save_json(cache_path, cache)
                save_json(provenance_path, provenance)
                print(f"  ... {stats['generated']} generated, {stats['failed']} failed", flush=True)

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        for _ in pool.map(render, plan):
            pass

    save_json(cache_path, cache)
    save_json(provenance_path, provenance)
    if args.retire and not stats["failed"]:
        moved = retire_unplanned(plan, audio_dir, args.retired_dir or audio_dir + "-retired", dry_run=False)
        print(f"retired {moved} clips the plan no longer reads")

    elapsed = time.time() - started
    print(
        f"\ndone in {elapsed:.1f}s: {stats['generated']} generated, {stats['reused']} reused, "
        f"{stats['failed']} failed; {stats['chars_generated']} characters, "
        f"{stats['duration']:.1f}s of audio"
    )
    if stats["errors"]:
        print("errors:")
        for line in stats["errors"][:20]:
            print("  " + line)
    return 1 if stats["failed"] else 0


def read_api_key(path: str) -> str:
    from_env = os.environ.get("CARTESIA_API_KEY", "").strip()
    if from_env:
        return from_env
    if not os.path.isfile(path):
        raise SystemExit(f"keys file not found: {path}")
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line.startswith("CARTESIA_API_KEY="):
                value = line.split("=", 1)[1].strip().strip('"').strip("'")
                if value:
                    return value
    raise SystemExit(f"CARTESIA_API_KEY not found in {path}")


if __name__ == "__main__":
    sys.exit(main())
