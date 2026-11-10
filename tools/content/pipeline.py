"""Glosso content pipeline: authored JSON -> IPA (from publishContent) + audio -> SQLite.

Mirrors glosso-studio: reference IPA is offline G2P (never the acoustic model);
audio is pre-generated (Kokoro by default, Chatterbox with --tts chatterbox) so
the app plays files offline. The reference audio is rendered for every voice so
the learner's accent/gender settings pick a file instead of re-downloading
content. Both backends write the exact same file names, extensions and rates:
`content/<lang>/audio/<name>.ogg`, OGG Vorbis, 24 kHz mono.

Everything is scoped to one language (`--language`, default `en`): the content
directory, the Kokoro voices and the audio keys. Voice keys are
`<accent>-<gender>` and the app builds the same key from its settings.

Kinds and their audio (all keyed by voice, ":slow" suffix = slow speed):
  drills     -> audio["<voice>"] / audio["<voice>:slow"] (whole sentence)
  words      -> audio["<voice>"]
  stories    -> paragraphAudio[i]["<voice>"]
  articles   -> paragraphAudio[i]["<voice>"]
  dialogues  -> turns[i].audio["<accent>-<gender>"] (one voice per speaker)
  exercises  -> no audio
  lessons    -> no audio

Long-form kinds (stories/articles/words) render the voices in --voices; drills
always render every narrator voice (plus the slow variant).

Usage:  python pipeline.py --language en [--tts kokoro|chatterbox] [--device cuda] \
            [--refs-dir DIR] [--model-dir DIR] [--source DIR] [--out DIR] [--audio-kinds ...]

`--no-render` builds (or rebuilds) the DB and its audio maps from the clips already
on disk, without loading the TTS backend or the GPU: handy for a partial deploy
while a render is still running. Missing clips simply get no map entry.
"""

import argparse
import bisect
import datetime
import glob
import hashlib
import json
import os
import shutil
import sqlite3
import time

import intonation
from intonation import LEGACY_PROFILE_VERSION, character_profile, line_profile

# cuBLAS needs a fixed workspace for deterministic runs; set before torch's
# first cuBLAS call (no-op when torch is never imported, e.g. data-only runs).
os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")

SLOW_SPEED = 0.8

# A full render is hours of GPU work; a reboot, OOM or Ctrl-C must not throw it
# away. Flush the audio cache to disk every this many new clips, or after this
# many seconds (whichever comes first), so a restart resumes where it stopped.
CACHE_FLUSH_CLIPS = 25
CACHE_FLUSH_SECONDS = 30.0

# Per-clip provenance and the commercial-license gate (docs/LICENSING.md,
# docs/CONTENT-ENGINE.md sec. 16). Every clip records which provider/model/voice/
# profile/moment made it, and a clip whose provider has no verified commercial
# license is never published into content.db. Chatterbox is MIT; the cloned
# references come from Kokoro (Apache-2.0); both allow commercial use.
PROVIDER_INFO = {
    "chatterbox": {"license": "MIT", "commercial_license": True},
    "kokoro": {"license": "Apache-2.0", "commercial_license": True},
}


def model_revision(model_dir):
    """Identity of the checkpoint: HF tree commit if present, else a file hash."""
    if not model_dir or not os.path.isdir(model_dir):
        return "unknown"
    for name in ("REVISION", "revision"):
        path = os.path.join(model_dir, name)
        if os.path.isfile(path):
            with open(path, encoding="utf-8") as fh:
                return fh.read().strip()
    trees = os.path.join(model_dir, ".cache", "huggingface", "trees")
    if os.path.isdir(trees):
        shas = sorted(f[:-5] for f in os.listdir(trees) if f.endswith(".json"))
        if shas:
            return "ResembleAI/chatterbox@" + shas[-1]
    h = hashlib.sha1()
    for fname in sorted(os.listdir(model_dir)):
        path = os.path.join(model_dir, fname)
        if os.path.isfile(path):
            h.update(("%s:%d" % (fname, os.path.getsize(path))).encode())
    return "local-" + h.hexdigest()[:12]

# Chatterbox checkpoints. English voices (af_*/am_*/bf_*/bm_*) use the
# English model; other languages go through the multilingual checkpoint with a
# language_id. The `f -> fr` mapping stays for the Chatterbox route, but French,
# German and Russian audio is rendered with Cartesia today (see LANGUAGES), so
# this pipeline only wires their clips; it does not render them.
CHATTERBOX_MODEL_DIR = "/opt/gen/models/chatterbox"
MTL_LANGUAGES = {"f": "fr"}

# Per-language voices. `narrators` maps the app's voice key to the Kokoro voice
# id; `accents` are the accents used to cast dialogues. Kokoro's voice id starts
# with its language pack letter (`a`/`b` English, `f` French, ...), which is what
# KPipeline expects as `lang_code`.
#
# English ships British and American voices. The clips of both are rendered with
# Cartesia (`cartesia_a1_audio.py --accent`, docs/TTS-CARTESIA-EN.md) and wired
# here with `--no-render`; the Kokoro ids below only matter to a Kokoro render.
LANGUAGES = {
    "en": {
        "narrators": {
            "en-US-female": "af_kore",
            "en-US-male": "am_puck",
            "en-GB-female": "bf_lily",
            "en-GB-male": "bm_fable",
        },
        "accents": ("en-GB", "en-US"),
        "voices": "en-GB-female,en-GB-male,en-US-female,en-US-male",
    },
    # French, German and Russian audio is not rendered here: Kokoro has no usable
    # voice for them, so their clips come from Cartesia, named
    # `<id>.<accent>-<gender>.ogg` like the English ones. The narrator keys below
    # are what `--no-render` needs to build content.db and wire those clips into
    # the audio maps; `renderer` makes a render attempt fail loudly instead of
    # handing a Cartesia voice id to Kokoro. Each id is the first of the two
    # Cartesia voices per gender in the engine's LanguagePack, which holds the
    # full palette.
    "fr": {
        "narrators": {
            "fr-FR-female": "7c58f4a4-a72c-42fa-a503-41b9408820f3",
            "fr-FR-male": "c6ccfe32-6bee-484a-a8a2-7a51bee93f99",
        },
        "accents": ("fr-FR",),
        "voices": "fr-FR-female,fr-FR-male",
        "renderer": "cartesia",
    },
    "de": {
        "narrators": {
            "de-DE-female": "4ab1ff51-476d-42bb-8019-4d315f7c0c05",
            "de-DE-male": "57a3a9e0-a91c-4c94-a2bb-e6cbab3ae649",
        },
        "accents": ("de-DE",),
        "voices": "de-DE-female,de-DE-male",
        "renderer": "cartesia",
    },
    "ru": {
        "narrators": {
            "ru-RU-female": "064b17af-d36b-4bfb-b003-be07dba1b649",
            "ru-RU-male": "1e4176b1-3db9-44d6-a601-4fe68b041942",
        },
        "accents": ("ru-RU",),
        "voices": "ru-RU-female,ru-RU-male",
        "renderer": "cartesia",
    },
}

KINDS = {
    "drills": ("DRILL", "drills"),
    "words": ("WORD", "words"),
    "stories": ("STORY", "stories"),
    "articles": ("ARTICLE", "articles"),
    "dialogues": ("DIALOGUE", "dialogues"),
    "exercises": ("EXERCISE", "exercises"),
    "lessons": ("LESSON", "lessons"),
}

# Every kind the app can play a clip for. `--audio-kinds` default is all of them:
# excluding a kind whose clips already exist on disk ships a catalogue with the
# audio paid for but not wired (stories/articles used to land with paragraphAudio
# empty). The post-build check below makes that fail instead of shipping.
AUDIO_KINDS = ("drills", "dialogues", "words", "stories", "articles")

SCHEMA = """
create table if not exists content (
    id text primary key,
    kind text not null,
    language text not null,
    level text,
    theme text,
    text text not null,
    ref text,
    data text not null
);
create table if not exists theme (id text primary key, labels text);
create index if not exists idx_content_kind on content(kind);
create index if not exists idx_content_theme on content(theme);
create index if not exists idx_content_ref on content(ref);
"""


class Tts:
    def __init__(self, out_dir, voices=None, force=False, backend="kokoro",
                 device=None, refs_dir=None, model_dir=None, dry=False, migrate=False):
        self.out_dir = out_dir
        self.audio_dir = os.path.join(out_dir, "audio")
        os.makedirs(self.audio_dir, exist_ok=True)
        self._pipes = {}
        self.voices = voices or {}
        self.force = force
        self.backend = backend
        self.device = device
        self.refs_dir = refs_dir or os.path.join(os.path.dirname(os.path.abspath(__file__)), "voices")
        self.model_dir = model_dir
        # dry: DB-only pass. `say` returns the path of an existing clip or None,
        # never imports a backend, renders or writes cache.
        self.dry = dry
        # migrate: re-tag the cache. `say` rewrites the fingerprint of an
        # existing clip to the CURRENT PROFILE_VERSION (no render, no backend)
        # and returns its path, so it also rebuilds the audio maps. It only
        # re-tags a clip it can prove was made by this exact provider/checkpoint/
        # voice/profile (see _can_migrate); anything else is skipped, never
        # silently reused.
        self.migrate = migrate
        # True when another tool renders this language's clips and keeps their
        # provenance (Cartesia: .cartesia-provenance.json). A dry pass then only
        # wires the clips; recording them here would claim this backend made them.
        self.external_provenance = False
        self.migration_skipped = 0
        self.migration_migrated = 0
        # Cache identity. profile_version is the intonation tag; hashed_profile
        # selects the v2 fingerprint that also covers the checkpoint and the
        # voice reference. Both are read once here from intonation, so the
        # migration lives behind intonation.USE_HASHED_PROFILE_VERSION and the
        # default (False) keeps the legacy fingerprint byte-identical.
        # See docs/TTS-CACHE.md.
        self.profile_version = intonation.PROFILE_VERSION
        self.hashed_profile = intonation.USE_HASHED_PROFILE_VERSION
        self._ref_revisions = {}
        # One chatterbox model per language family, conditionals per voice
        # (prepared once against the reference clip, reused across clips).
        self._chatterbox_models = {}
        self._chatterbox_conds = {}
        self._cache_path = os.path.join(self.audio_dir, ".cache.json")
        try:
            with open(self._cache_path, encoding="utf-8") as fh:
                self._cache = json.load(fh)
        except Exception:
            self._cache = {}
        self._cache_dirty = False
        self._flush_every = CACHE_FLUSH_CLIPS
        self._flush_interval = CACHE_FLUSH_SECONDS
        self._since_flush = 0
        self._last_flush = time.monotonic()
        # Provenance + license gate.
        self.licensed = PROVIDER_INFO.get(self.backend, {}).get("commercial_license", False)
        self.model_revision = model_revision(model_dir)
        self._provenance_path = os.path.join(self.audio_dir, ".provenance.json")
        try:
            with open(self._provenance_path, encoding="utf-8") as fh:
                self._provenance = json.load(fh)
        except Exception:
            self._provenance = {}
        self._provenance_dirty = False

    def _legacy_fingerprint(self, text, voice, speed):
        # Byte-identical to the pre-migration pipeline: Kokoro keeps its
        # untagged fingerprint; other backends tag the engine and the LEGACY
        # profile tag (never the hashed one), so the historical cache entries
        # still compare equal during the one-time migration.
        tag = "" if self.backend == "kokoro" else f"|{self.backend}|{LEGACY_PROFILE_VERSION}"
        return hashlib.sha1(f"{text}|{voice}|{speed}{tag}".encode("utf-8")).hexdigest()

    def _ref_revision(self, voice):
        """Identity of the reference clip cloned as `voice`, memoised per voice."""
        if voice not in self._ref_revisions:
            path = os.path.join(self.refs_dir, f"{voice}.wav")
            try:
                h = hashlib.sha1()
                with open(path, "rb") as fh:
                    for chunk in iter(lambda: fh.read(65536), b""):
                        h.update(chunk)
                rev = "sha1:" + h.hexdigest()[:12]
            except OSError:
                rev = "no-ref"
            self._ref_revisions[voice] = rev
        return self._ref_revisions[voice]

    def _hashed_fingerprint(self, text, voice, speed):
        # v2 identity: every input that changes the audio, so switching
        # --model-dir or regenerating voices/<voice>.wav invalidates the cache,
        # not only a profile edit or a --tts switch. Only used once the
        # migration is activated. See docs/TTS-CACHE.md.
        parts = (
            text, voice, str(speed), self.backend, self.profile_version,
            self.model_revision, self._ref_revision(voice),
        )
        return hashlib.sha1("|".join(parts).encode("utf-8")).hexdigest()

    def _fingerprint(self, text, voice, speed):
        # With the flag off the legacy fingerprint is byte-identical to before,
        # so the in-flight render and the existing clips stay untouched.
        if self.hashed_profile:
            return self._hashed_fingerprint(text, voice, speed)
        return self._legacy_fingerprint(text, voice, speed)

    def _can_migrate(self, name, text, voice, speed):
        """True only for a clip provably made by this provider/checkpoint/voice/profile.

        The stored fingerprint must be exactly the legacy (or already hashed)
        fingerprint for this clip, and the provenance sidecar must agree on the
        provider, the checkpoint and the voice, under a tag we know how to
        migrate. A missing or unexpected record is a skip, never a blind
        re-tag: re-tagging an unverifiable clip is the same class of bug as
        reusing a stale one.
        """
        stored = self._cache.get(name)
        if stored not in (self._legacy_fingerprint(text, voice, speed),
                          self._hashed_fingerprint(text, voice, speed)):
            return False
        prov = self._provenance.get(name)
        if not prov:
            return False
        if prov.get("provider") != self.backend:
            return False
        if prov.get("model") != self.model_revision:
            return False
        if prov.get("voice") != voice:
            return False
        ref = prov.get("ref")
        if ref is not None and ref != self._ref_revision(voice):
            return False
        if prov.get("profile") not in (self.profile_version, LEGACY_PROFILE_VERSION):
            return False
        return True

    def _cached(self, name, fp):
        # A missing cache entry is a miss: an unverifiable file (stale clip from
        # another engine, interrupted run) must be re-rendered, never reused.
        current = self._existing(name)
        if current is not None and not self.force and self._cache.get(name) == fp:
            return current
        return None

    def _existing(self, name):
        for ext in (".ogg", ".wav"):
            path = os.path.join(self.audio_dir, name + ext)
            if os.path.isfile(path):
                return path
        return None

    def _remember(self, name, fp):
        """Record a cache hit/write, flushing periodically so a cut loses little."""
        if self._cache.get(name) != fp:
            self._cache[name] = fp
            self._cache_dirty = True
        self._touch()

    def _record_provenance(self, name, voice, path):
        """Record which provider/model/voice/profile and when made a clip."""
        info = PROVIDER_INFO.get(self.backend, {})
        try:
            when = datetime.datetime.fromtimestamp(
                os.path.getmtime(path), datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        except OSError:
            when = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        record = {
            "provider": self.backend,
            "model": self.model_revision,
            "voice": voice,
            "ref": self._ref_revision(voice),
            "profile": self.profile_version,
            "license": info.get("license"),
            "commercial_license": bool(info.get("commercial_license", False)),
            "date": when,
        }
        if self._provenance.get(name) != record:
            self._provenance[name] = record
            self._provenance_dirty = True
        self._touch()

    def _touch(self):
        self._since_flush += 1
        if (self._since_flush >= self._flush_every
                or time.monotonic() - self._last_flush >= self._flush_interval):
            self.flush()

    def _write_json(self, path, obj):
        # Atomic replace: a crash mid-write must not leave a truncated file.
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(obj, fh)
        os.replace(tmp, path)

    def flush(self):
        if not (self._cache_dirty or self._provenance_dirty):
            return
        if self._cache_dirty:
            self._write_json(self._cache_path, self._cache)
            self._cache_dirty = False
        if self._provenance_dirty:
            self._write_json(self._provenance_path, self._provenance)
            self._provenance_dirty = False
        self._since_flush = 0
        self._last_flush = time.monotonic()

    def _pipe(self, voice):
        # Imported lazily so a data-only run (--audio-kinds "") needs no torch.
        from kokoro import KPipeline

        # Kokoro's language pack letter is the first character of the voice id.
        lang = voice[0]
        if lang not in self._pipes:
            self._pipes[lang] = KPipeline(lang_code=lang)
        return self._pipes[lang]

    def say(self, name, text, voice, speed=1.0, speaker=None, kind="drill", profile=None):
        # License gate: a provider without a verified commercial license never
        # produces a publishable clip.
        if not self.licensed:
            return None
        if self.dry or self.migrate:
            current = self._existing(name)
            if current is None:
                return None
            if self.migrate:
                if not self._can_migrate(name, text, voice, speed):
                    # Unverifiable clip: leave the fingerprint untouched and
                    # drop it from the maps, so the app never serves it and a
                    # later render fills it with a clip from this config.
                    self.migration_skipped += 1
                    return None
                self.migration_migrated += 1
                self._remember(name, self._fingerprint(text, voice, speed))
            if not self.external_provenance:
                self._record_provenance(name, voice, current)
            return os.path.relpath(current, self.out_dir)
        if self.backend == "chatterbox":
            rel = self._say_chatterbox(name, text, voice, speed, speaker or "narrator", kind, profile)
        else:
            rel = self._say_kokoro(name, text, voice, speed)
        if rel is not None:
            self._record_provenance(name, voice, os.path.join(self.out_dir, rel))
        return rel

    def _say_kokoro(self, name, text, voice, speed):
        import numpy as np
        import soundfile as sf

        fp = self._fingerprint(text, voice, speed)
        current = self._cached(name, fp)
        if current is not None:
            self._remember(name, fp)
            return os.path.relpath(current, self.out_dir)

        audio = np.concatenate([
            a.detach().cpu().numpy() if hasattr(a, "detach") else np.asarray(a)
            for _, _, a in self._pipe(voice)(text, voice=voice, speed=speed)
        ])
        path = self._write(name, audio, 24000)
        self._remember(name, fp)
        return os.path.relpath(path, self.out_dir)

    def _say_chatterbox(self, name, text, voice, speed, speaker, kind, profile=None):
        import soundfile as sf
        import torch

        fp = self._fingerprint(text, voice, speed)
        current = self._cached(name, fp)
        if current is not None:
            self._remember(name, fp)
            return os.path.relpath(current, self.out_dir)

        # `profile` is a calibration hook (tools/content/intonation_ab.py); the
        # content pipeline always computes it from the speaker, voice and text.
        if profile is None:
            profile = line_profile(text, character_profile(speaker, voice), kind)
        model, language_id = self._chatterbox_model(voice)
        if model.sr != 24000:
            raise SystemExit(
                f"chatterbox: unexpected sample rate {model.sr} Hz; the app contract "
                "is OGG 24 kHz mono and the cache fingerprint does not cover the rate"
            )
        if voice not in self._chatterbox_conds:
            self._chatterbox_prepare(voice)
        # generate() mutates model.conds; re-assign this voice's prepared
        # conditionals so voices sharing a model never bleed into each other.
        model.conds = self._chatterbox_conds[voice]
        torch.manual_seed(profile["seed"])
        kwargs = dict(exaggeration=profile["exaggeration"], cfg_weight=profile["cfg"])
        if language_id:
            kwargs["language_id"] = language_id
        audio = model.generate(text, **kwargs).squeeze(0).cpu().numpy()
        if speed != 1.0:
            audio = self._time_stretch(audio, model.sr, speed)
        path = self._write(name, audio, model.sr)
        self._remember(name, fp)
        return os.path.relpath(path, self.out_dir)

    def _write(self, name, audio, sample_rate):
        import soundfile as sf

        path = os.path.join(self.audio_dir, name + ".ogg")
        try:
            sf.write(path, audio, sample_rate, format="OGG", subtype="VORBIS")
        except Exception:
            path = os.path.join(self.audio_dir, name + ".wav")
            sf.write(path, audio, sample_rate)
        return path

    def _chatterbox_model(self, voice):
        # Imported lazily so a data-only run (--audio-kinds "") needs no torch,
        # whichever backend is selected.
        import torch
        from chatterbox.mtl_tts import ChatterboxMultilingualTTS
        from chatterbox.tts import ChatterboxTTS

        lang = voice[0]
        # One checkpoint per family: every af_*/am_*/bf_*/bm_* voice shares the
        # single English model, MTL_LANGUAGES share the multilingual one, so
        # English never loads two copies into VRAM. language_id stays per
        # voice (two MTL languages could share the checkpoint).
        family = "mtl" if lang in MTL_LANGUAGES else "en"
        if family not in self._chatterbox_models:
            device = self.device or ("cuda" if torch.cuda.is_available() else "cpu")
            # Same input must render the same clip: the per-clip seed only
            # pays off if the CUDA kernels are deterministic too. Opt out
            # (faster, less reproducible) with CHATTERBOX_NONDETERMINISTIC=1.
            if os.environ.get("CHATTERBOX_NONDETERMINISTIC") != "1":
                torch.backends.cudnn.benchmark = False
                torch.backends.cudnn.deterministic = True
                torch.use_deterministic_algorithms(True)
            if family == "mtl":
                marker = "grapheme_mtl_merged_expanded_v1.json"
                ckpt = self.model_dir
                if ckpt and not os.path.isfile(os.path.join(ckpt, marker)):
                    ckpt = _chatterbox_mtl_dir()
                if ckpt and os.path.isdir(ckpt):
                    model = ChatterboxMultilingualTTS.from_local(ckpt, device)
                else:
                    model = ChatterboxMultilingualTTS.from_pretrained(device)
            else:
                ckpt = self.model_dir or _chatterbox_en_dir()
                if ckpt and os.path.isdir(ckpt):
                    model = ChatterboxTTS.from_local(ckpt, device)
                else:
                    model = ChatterboxTTS.from_pretrained(device)
            self._chatterbox_models[family] = model
        return self._chatterbox_models[family], MTL_LANGUAGES.get(lang)

    def _chatterbox_prepare(self, voice):
        model, _ = self._chatterbox_model(voice)
        ref = os.path.join(self.refs_dir, f"{voice}.wav")
        if not os.path.isfile(ref):
            raise SystemExit(
                f"chatterbox: missing reference clip {ref}; generate the Kokoro "
                "references first (tools/content/make_voice_refs.py)"
            )
        # prepare_conditionals only needs the default exaggeration: generate()
        # rewrites emotion_adv per clip, so the conditionals are reusable.
        model.prepare_conditionals(ref)
        self._chatterbox_conds[voice] = model.conds

    def _time_stretch(self, audio, sample_rate, speed):
        # Chatterbox has no speed knob: the '.slow' variant is a pitch
        # preserving time stretch with ffmpeg atempo. `speed` is the target
        # speed multiplier (0.8 = slower), and atempo IS that multiplier, so
        # atempo=0.8 makes the clip 1/0.8 = 1.25x longer, matching Kokoro.
        import soundfile as sf
        import subprocess
        import tempfile

        ffmpeg = shutil.which("ffmpeg")
        if not ffmpeg:
            raise SystemExit(
                "--tts chatterbox needs ffmpeg for the '.slow' variant "
                "(pitch-preserving atempo time stretch); install ffmpeg or "
                "render slow variants with another backend"
            )
        factor = round(float(speed), 4)
        if not 0.5 <= factor <= 2.0:
            raise SystemExit(f"speed {speed} out of ffmpeg atempo range (factor {factor})")
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "in.wav")
            dst = os.path.join(tmp, "out.wav")
            sf.write(src, audio, sample_rate)
            subprocess.run(
                [ffmpeg, "-y", "-loglevel", "error", "-i", src,
                 "-filter:a", f"atempo={factor}", "-ar", str(sample_rate), "-ac", "1", dst],
                check=True,
            )
            stretched, _ = sf.read(dst, dtype="float32")
        return stretched

    def all_voices(self, name, text, slow=False, voices=None, kind="drill", speaker=None):
        out = {}
        for key, voice in (voices or self.voices).items():
            path = self.say(
                f"{name}.{key}" + (".slow" if slow else ""), text, voice,
                SLOW_SPEED if slow else 1.0, speaker=speaker, kind=kind,
            )
            if path is not None:
                out[key] = path
        return out


def first_text(kind, item):
    if kind == "drills":
        return item["text"]
    if kind == "words":
        return item["lemma"]
    if item.get("title"):
        return item["title"]
    return (item.get("paragraphs") or item.get("prompt") or [""])[0]


def pick_voice(narrators, accent, gender):
    """The narrator for an accent+gender, falling back to any voice of the accent."""
    exact = narrators.get(f"{accent}-{gender}")
    if exact:
        return exact
    for key, voice in narrators.items():
        if key.startswith(f"{accent}-"):
            return voice
    return next(iter(narrators.values()), None)


def add_audio(kind, item, tts, language):
    narrators = tts.narrators
    if kind == "drills":
        item["audio"] = tts.all_voices(item["id"], item["text"], voices=narrators)
        item["audio"].update({
            f"{k}:slow": v
            for k, v in tts.all_voices(item["id"], item["text"], slow=True, voices=narrators).items()
        })
    elif kind == "words":
        item["audio"] = tts.all_voices(item["id"], item["lemma"], kind="word")
    elif kind in ("stories", "articles"):
        long_kind = "story" if kind == "stories" else "article"
        item["paragraphAudio"] = [
            tts.all_voices(f"{item['id']}.p{i}", p, kind=long_kind)
            for i, p in enumerate(item.get("paragraphs", []))
        ]
    elif kind == "dialogues":
        by_speaker = {c["name"]: c.get("gender", "MALE") for c in item.get("cast", [])}
        for i, turn in enumerate(item.get("turns", [])):
            gender = by_speaker.get(turn["speaker"], "MALE").lower()
            turn["audio"] = {}
            for accent in LANGUAGES[language]["accents"]:
                key = f"{accent}-{gender}"
                voice = pick_voice(narrators, accent, gender)
                if voice:
                    path = tts.say(
                        f"{item['id']}.t{i}.{key}", turn["text"], voice,
                        speaker=turn["speaker"], kind="dialogue",
                    )
                    if path is not None:
                        turn["audio"][key] = path


def _empty_audio_map(kind, item):
    if kind in ("STORY", "ARTICLE"):
        return not any(item.get("paragraphAudio") or [])
    if kind == "DIALOGUE":
        return not any(t.get("audio") for t in item.get("turns") or [])
    return not item.get("audio")


def _map_paths(kind, item):
    if kind in ("STORY", "ARTICLE"):
        return [p for m in item.get("paragraphAudio") or [] for p in m.values()]
    if kind == "DIALOGUE":
        return [p for t in item.get("turns") or [] for p in (t.get("audio") or {}).values()]
    return list((item.get("audio") or {}).values())


def _clip_prefix(kind, item_id):
    # The file name the app reads always starts with the item id, then `.p` for a
    # paragraph / `.t` for a dialogue turn / `.` for a single clip. Checking the
    # prefix (not a full name) survives a voice change: we only need to know a
    # clip exists, not which voice made it.
    if kind in ("STORY", "ARTICLE"):
        return item_id + ".p"
    if kind == "DIALOGUE":
        return item_id + ".t"
    return item_id + "."


def verify_audio_maps(conn, out_dir):
    """Return the ways the DB audio maps disagree with the clips on disk.

    Fail-closed guard around the bug this pipeline shipped once: stories and
    articles were rendered and paid for, but excluded from `--audio-kinds`, so
    `paragraphAudio` went out empty and the app never played them. It checks both
    directions: a clip on disk that no map points at (the bug), and a map entry
    that points at a missing clip (broken reference).
    """
    audio_dir = os.path.join(out_dir, "audio")
    stems = []
    if os.path.isdir(audio_dir):
        stems = sorted(
            f.rsplit(".", 1)[0]
            for f in os.listdir(audio_dir)
            if f.endswith((".ogg", ".wav"))
        )

    def has_clip(prefix):
        i = bisect.bisect_left(stems, prefix)
        return i < len(stems) and stems[i].startswith(prefix)

    problems = []
    for folder in AUDIO_KINDS:
        kind = KINDS[folder][0]
        for item_id, data in conn.execute("select id, data from content where kind = ?", (kind,)):
            item = json.loads(data)
            if has_clip(_clip_prefix(kind, item_id)) and _empty_audio_map(kind, item):
                problems.append(f"{item_id}: clips exist on disk but its {folder} audio map is empty")
            for rel in _map_paths(kind, item):
                if not os.path.isfile(os.path.join(out_dir, rel)):
                    problems.append(f"{item_id}: map points at a missing clip {rel}")
    return problems


def asset_sizes(root):
    """Real byte size of every published asset, keyed by asset-relative path.

    Only the downloadable kinds the manifest keys on (`audio/`, `images/`), and
    the size always comes from the file on disk, never an estimate, so the
    published catalogue is exact. Sorted so re-publishing an unchanged catalogue
    is a byte-for-byte no-op.
    """
    sizes = {}
    for sub in ("audio", "images"):
        base = os.path.join(root, sub)
        if not os.path.isdir(base):
            continue
        for dirpath, _dirs, names in os.walk(base):
            for name in names:
                if name.startswith("."):
                    # Render metadata (.cache.json, .provenance.json) is not a
                    # downloadable asset; only what the app can request counts.
                    continue
                path = os.path.join(dirpath, name)
                sizes[os.path.relpath(path, root)] = os.path.getsize(path)
    return dict(sorted(sizes.items()))


def _item_asset_paths(kind, item):
    """Every asset path an item points at: its audio maps plus its artwork."""
    paths = list(_map_paths(kind, item))
    image = item.get("image")
    if image:
        paths.append(image)
    return paths


def bank_asset_bytes(conn, sizes):
    """Total bytes of the assets each bank's items reference, keyed by kind.

    Same files the per-asset map measures, added up per bank, so a surface can
    show a bank's weight without walking the whole map.
    """
    totals = {}
    for kind, data in conn.execute("select kind, data from content"):
        item = json.loads(data)
        totals[kind] = totals.get(kind, 0) + sum(
            sizes.get(path, 0) for path in _item_asset_paths(kind, item)
        )
    return totals


def stamp_manifest_sizes(manifest_path, root, conn=None):
    """Write the real per-asset and per-bank sizes into a published manifest.

    The app adds a lesson's size up locally from `assets`, so it needs no
    network round trip per file. `root` is the published content dir where the
    audio and images live; `conn` (or `root/content.db`) supplies the per-bank
    totals. Returns the number of assets measured.
    """
    if not os.path.isfile(manifest_path):
        return 0
    with open(manifest_path, encoding="utf-8") as fh:
        manifest = json.load(fh)
    sizes = asset_sizes(root)
    manifest["assets"] = sizes

    own_conn = conn is None
    if own_conn:
        db_path = os.path.join(root, "content.db")
        conn = (
            sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
            if os.path.isfile(db_path)
            else None
        )
    if conn is not None:
        totals = bank_asset_bytes(conn, sizes)
        for bank in manifest.get("banks") or []:
            bank["bytes"] = totals.get(bank.get("kind"), 0)
        if own_conn:
            conn.close()

    with open(manifest_path, "w", encoding="utf-8") as fh:
        # Match the hand-written manifest's 4-space style, so adding the sizes
        # does not reformat the whole file.
        json.dump(manifest, fh, ensure_ascii=False, indent=4)
        fh.write("\n")
    return len(sizes)


def _chatterbox_en_dir():
    """English checkpoint dir: $CHATTERBOX_MODEL_DIR, the default GPU path, or None (HF download)."""
    env = os.environ.get("CHATTERBOX_MODEL_DIR")
    if env:
        return env
    return CHATTERBOX_MODEL_DIR if os.path.isdir(CHATTERBOX_MODEL_DIR) else None


def _chatterbox_mtl_dir():
    """Multilingual checkpoint dir: $CHATTERBOX_MTL_MODEL_DIR, a dir that already
    ships the mtl files, <en-dir>-mtl, or None (HF download)."""
    env = os.environ.get("CHATTERBOX_MTL_MODEL_DIR")
    if env:
        return env
    en = _chatterbox_en_dir()
    marker = "grapheme_mtl_merged_expanded_v1.json"
    if en:
        if os.path.isfile(os.path.join(en, marker)):
            return en
        mtl = en + "-mtl"
        if os.path.isdir(mtl):
            return mtl
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--language", default="en", help="target language code (content/<lang>)")
    ap.add_argument("--source", default=None, help="published dist dir (default content/<lang>/dist)")
    ap.add_argument("--out", default=None, help="content dir (default content/<lang>)")
    ap.add_argument("--audio-kinds", default=",".join(AUDIO_KINDS),
                    help="comma-separated folders that get audio; others are stored data-only "
                         f"(default: {','.join(AUDIO_KINDS)})")
    ap.add_argument("--force", action="store_true",
                    help="regenerate every clip, ignoring the audio cache")
    ap.add_argument("--no-render", action="store_true",
                    help="DB-only pass: write the audio maps for clips that already exist on "
                         "disk and skip the rest. Never loads the TTS backend or touches the GPU.")
    ap.add_argument("--migrate-cache", action="store_true",
                    help="re-tag the cache without rendering: for every clip on disk whose cache "
                         "entry and provenance prove it was made by this exact provider/checkpoint/"
                         "voice/profile, rewrite its fingerprint to the current tag and rebuild the "
                         "audio maps. Unverifiable clips are skipped, never blindly re-tagged. Run "
                         "with the same --tts/--model-dir/--refs-dir as the render. See docs/TTS-CACHE.md.")
    ap.add_argument("--skip-audio-check", action="store_true",
                    help="allow a publish that leaves clips on disk out of the DB audio maps "
                         "(the data-only and partial-render cases). Off by default so the "
                         "catalogue can never ship paid-for audio the app cannot see.")
    ap.add_argument("--voices", default=None,
                    help="voices for long-form kinds (stories/articles/words); drills always use all narrators")
    ap.add_argument("--tts", choices=("kokoro", "chatterbox"), default="kokoro",
                    help="TTS engine; both write the same file names, OGG 24 kHz mono")
    ap.add_argument("--device", default=None,
                    help="torch device for --tts chatterbox (cuda/cpu; default: auto)")
    ap.add_argument("--refs-dir", default=None,
                    help="dir with the <kokoro-id>.wav reference clips used to clone each voice "
                         "(default: tools/content/voices)")
    ap.add_argument("--model-dir", default=None,
                    help="chatterbox checkpoint dir (default: $CHATTERBOX_MODEL_DIR, "
                         f"{CHATTERBOX_MODEL_DIR}, <dir>-mtl for non-English, or the HF hub)")
    args = ap.parse_args()

    language = args.language
    if language not in LANGUAGES:
        raise SystemExit(f"unknown language '{language}'; known: {', '.join(sorted(LANGUAGES))}")
    config = LANGUAGES[language]
    base = f"../../../glosso-data/content/{language}"
    source = args.source or os.path.join(base, "dist")
    out = args.out or base
    default_voices = config["voices"]
    voices_arg = args.voices if args.voices is not None else default_voices

    if not config["narrators"]:
        reason = config.get("note", "add the voices to LANGUAGES in pipeline.py")
        raise SystemExit(
            f"no TTS voices configured for '{language}': {reason} "
            "(see docs/ADD_LANGUAGE.md)."
        )

    renderer = config.get("renderer")
    if renderer and not args.no_render:
        raise SystemExit(
            f"audio for '{language}' is rendered with {renderer}, not by this pipeline: "
            "run with --no-render to build content.db from the clips on disk "
            "(see docs/ADD_LANGUAGE.md)."
        )

    audio_kinds = {k.strip() for k in args.audio_kinds.split(",") if k.strip()}

    db_path = os.path.join(out, "content.db")
    os.makedirs(out, exist_ok=True)
    # Build into a temp DB and only swap it in on success: a publish that fails
    # the audio-map check below must not leave a content.db that looks valid but
    # is out of sync with the clips on disk, nor destroy the previous one.
    build_path = db_path + ".tmp"
    if os.path.exists(build_path):
        os.remove(build_path)
    conn = sqlite3.connect(build_path)
    conn.executescript(SCHEMA)
    narrators = config["narrators"]
    voices = {k: narrators[k] for k in (v.strip() for v in voices_arg.split(",")) if k in narrators}
    if not voices:
        raise SystemExit("--voices selected none of: " + ", ".join(narrators))
    tts = Tts(out, voices, force=args.force, backend=args.tts,
              device=args.device, refs_dir=args.refs_dir, model_dir=args.model_dir,
              dry=args.no_render, migrate=args.migrate_cache)
    tts.narrators = narrators
    tts.external_provenance = bool(renderer)

    # License gate: never publish clips from a provider without a verified
    # commercial license (see PROVIDER_INFO).
    if not tts.licensed:
        raise SystemExit(
            f"--tts {args.tts} has no verified commercial license in PROVIDER_INFO; "
            "refusing to publish clips"
        )

    # Flush the cache on a clean exit and on SIGTERM/SIGINT (the kill we send a
    # stuck render), so a restart resumes from the last periodic flush.
    import atexit
    import signal

    atexit.register(tts.flush)

    def _flush_and_exit(signum, _frame):
        tts.flush()
        raise SystemExit(128 + signum)

    for _sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(_sig, _flush_and_exit)

    total = 0
    for folder, (kind, list_key) in KINDS.items():
        for path in sorted(glob.glob(os.path.join(source, folder, "*.json"))):
            bank = json.load(open(path))
            for item in bank.get(list_key, []):
                if folder in audio_kinds:
                    add_audio(folder, item, tts, language)
                conn.execute(
                    "insert or replace into content values (?,?,?,?,?,?,?,?)",
                    (
                        item["id"], kind, item.get("language", language), item.get("level"),
                        item.get("theme"), first_text(folder, item),
                        item.get("storyId") or item.get("dialogueId") or item.get("articleId"),
                        json.dumps(item, ensure_ascii=False),
                    ),
                )
                total += 1
                print(f"{kind:8s} {item['id']}")

    # Fail-closed: a kind with clips on disk but no map entries means paid-for
    # audio the app cannot play (the stories/articles paragraphAudio bug). Run
    # before committing, and discard the temp DB on failure so the real
    # content.db and the assets are left untouched.
    problems = [] if args.skip_audio_check else verify_audio_maps(conn, out)
    if problems:
        conn.rollback()
        conn.close()
        os.remove(build_path)
        print(f"\nERROR: {len(problems)} audio map problems - the DB was NOT written:")
        for line in problems[:20]:
            print("  " + line)
        if len(problems) > 20:
            print(f"  ... and {len(problems) - 20} more")
        print("Add every kind that has clips on disk to --audio-kinds "
              "(or pass --skip-audio-check for a deliberate data-only pass).")
        raise SystemExit(1)

    conn.commit()
    conn.close()
    os.replace(build_path, db_path)
    tts.flush()

    if args.migrate_cache:
        print(f"\nmigrate-cache: re-tagged {tts.migration_migrated} clips, "
              f"skipped {tts.migration_skipped} unverifiable clips "
              "(same --tts/--model-dir/--refs-dir as the render? see docs/TTS-CACHE.md)")

    # Images ride along with the audio and the DB so the assets dir is complete.
    # PNG and SVG share this path, so the URL versioning covers either format.
    src_images = os.path.join(source, "images")
    if os.path.isdir(src_images):
        dst_images = os.path.join(out, "images")
        if os.path.abspath(src_images) != os.path.abspath(dst_images):
            os.makedirs(dst_images, exist_ok=True)
            copied = 0
            sources = glob.glob(os.path.join(src_images, "*.png")) + glob.glob(
                os.path.join(src_images, "*.svg")
            )
            for path in sorted(sources):
                target = os.path.join(dst_images, os.path.basename(path))
                if not os.path.exists(target) or os.path.getmtime(path) > os.path.getmtime(target):
                    shutil.copy2(path, target)
                    copied += 1
            print(f"{copied} images -> {dst_images}")

    # The compiled manifest lands in the published dist; copy it next to the DB so
    # the server finds `content/<lang>/manifest.json` without a second path, and
    # stamp it with the real per-asset sizes (from the files just rendered) plus
    # the per-bank totals, so the catalog is self-describing.
    src_manifest = os.path.join(source, "manifest.json")
    if os.path.isfile(src_manifest):
        dst_manifest = os.path.join(out, "manifest.json")
        shutil.copy2(src_manifest, dst_manifest)
        measured = stamp_manifest_sizes(dst_manifest, out)
        print(f"manifest sizes: {measured} assets -> {dst_manifest}")

    print(f"\n{total} items -> {db_path}")


if __name__ == "__main__":
    main()
