"""Pipeline contract tests. Run:  python3 naming_test.py

- Both TTS backends produce exactly the same file names for every kind.
- `_write` really emits Ogg Vorbis 24 kHz mono (stdlib Ogg header parse, no
  torch; the soundfile round-trip check is skipped if soundfile is missing).
- The `.slow` time stretch makes the clip LONGER (1/0.8 = 1.25x) and keeps the
  pitch, so it matches Kokoro's `speed=0.8` instead of speeding the clip up.
- A missing cache entry is a miss: an existing clip must be re-rendered, not
  silently reused (guards the cross-engine stale-clip bug).
- The cache is flushed periodically (every N clips or 30 s), atomically, so an
  interrupted render resumes instead of re-rendering everything.
- `--no-render` (dry Tts) maps only clips already on disk: an existing file gets
  an entry, a missing one gets none, and the backend is never called.
- The cache tag is derived from the profile behaviour (not hand-written), and
  `--migrate-cache` re-tags existing clips to it without rendering.
- The migration fingerprint (used only once `USE_HASHED_PROFILE_VERSION` is
  activated) also covers the checkpoint and the voice reference, so switching
  `--model-dir` or regenerating `voices/*.wav` invalidates the cache.
- `--migrate-cache` only re-tags a clip it can prove was made by this exact
  provider/checkpoint/voice/profile; anything unverifiable is skipped, never
  blindly re-tagged (and never mapped into the DB), and re-running is idempotent.
- Every published clip records provenance (provider/model/voice/profile/date),
  and a provider without a verified commercial license is never published.
- Importing the pipeline (and instantiating either backend) pulls in no
  torch/kokoro/chatterbox: a data-only run must stay light.
- French, German and Russian (clips rendered elsewhere, with Cartesia) build
  their content.db with `--no-render`, wire the clips on disk under
  `<accent>-<gender>`, refuse to render, and record no provenance of their own.
"""

import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import intonation
import pipeline
from pipeline import LANGUAGES, Tts, add_audio

FAILURES = []


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print(f"FAIL {msg}")
    else:
        print(f"ok   {msg}")


class RecordingTts(Tts):
    """Stubs both renderers so naming logic runs with no torch anywhere."""

    def __init__(self, backend):
        self._tmp = tempfile.TemporaryDirectory()
        super().__init__(os.path.join(self._tmp.name, backend), backend=backend)
        self.calls = []

    def _say_kokoro(self, name, text, voice, speed):
        self.calls.append((name, text, voice, speed, None, "drill"))
        return f"audio/{name}.ogg"

    def _say_chatterbox(self, name, text, voice, speed, speaker, kind, profile=None):
        self.calls.append((name, text, voice, speed, speaker, kind))
        return f"audio/{name}.ogg"


def sample_items():
    narrators = LANGUAGES["en"]["narrators"]
    long_voices = {"en-GB-female": narrators["en-GB-female"], "en-GB-male": narrators["en-GB-male"]}
    drill = {"id": "dr1", "text": "Do you want to share a taxi?"}
    word = {"id": "w1", "lemma": "umbrella"}
    story = {"id": "s1", "paragraphs": ["It was raining. \"You came,\" he said."]}
    article = {"id": "a1", "paragraphs": ["The city opened a new tram line last month."]}
    dialogue = {
        "id": "d1",
        "cast": [{"name": "Maya", "gender": "FEMALE"}, {"name": "Ben", "gender": "MALE"}],
        "turns": [
            {"speaker": "Maya", "text": "Do you have a minute?"},
            {"speaker": "Ben", "text": "Sure, tell me."},
        ],
    }
    return narrators, long_voices, drill, word, story, article, dialogue


def render(backend):
    narrators, long_voices, drill, word, story, article, dialogue = sample_items()
    tts = RecordingTts(backend)
    tts.voices = long_voices
    tts.narrators = narrators
    add_audio("drills", drill, tts, "en")
    add_audio("words", word, tts, "en")
    add_audio("stories", story, tts, "en")
    add_audio("articles", article, tts, "en")
    add_audio("dialogues", dialogue, tts, "en")
    return tts, drill, word, story, article, dialogue


def ogg_vorbis_header(path):
    """Parse the Vorbis identification header with the standard library only."""
    with open(path, "rb") as fh:
        data = fh.read(128)
    if data[:4] != b"OggS":
        return None
    i = data.find(b"\x01vorbis")
    if i < 0:
        return None
    version = int.from_bytes(data[i + 7:i + 11], "little")
    channels = data[i + 11]
    sample_rate = int.from_bytes(data[i + 12:i + 16], "little")
    return version, channels, sample_rate


def test_write_format():
    try:
        import numpy as np
        import soundfile  # noqa: F401  (needed by Tts._write)
    except ImportError:
        print("SKIP _write format check (needs numpy+soundfile; run with the "
              "tools/content/.venv-chatterbox/bin/python or the GPU venv)")
        return

    with tempfile.TemporaryDirectory() as tmp:
        tts = Tts(tmp, backend="kokoro")
        path = tts._write("probe", np.zeros(12000, dtype="float32"), 24000)
        check(path.endswith(".ogg"), "_write emits .ogg")
        header = ogg_vorbis_header(path)
        check(header is not None, "_write emits a real Ogg container")
        if header:
            version, channels, sample_rate = header
            check(version == 0 and channels == 1 and sample_rate == 24000,
                  f"_write writes Vorbis 24 kHz mono (got v{version}, {channels}ch, {sample_rate} Hz)")
        check(os.path.getsize(path) > 0, "_write produces a non-empty clip")


def _dominant_hz(audio, sample_rate):
    import numpy as np

    window = np.hanning(len(audio))
    spectrum = np.abs(np.fft.rfft(audio * window))
    return float(np.fft.rfftfreq(len(audio), 1.0 / sample_rate)[int(np.argmax(spectrum))])


def test_slow_stretch():
    """`.slow` must last longer (1/0.8 = 1.25x) and keep the pitch (no resample)."""
    try:
        import numpy as np
        import soundfile  # noqa: F401  (needed by Tts._time_stretch / _write)
    except ImportError:
        print("SKIP slow-stretch check (needs numpy+soundfile; run with the "
              "tools/content/.venv-chatterbox/bin/python or the GPU venv)")
        return
    if shutil.which("ffmpeg") is None:
        print("SKIP slow-stretch check (needs ffmpeg)")
        return

    sample_rate = 24000
    tone_hz = 440.0
    t = np.arange(sample_rate) / sample_rate
    tone = (0.5 * np.sin(2 * np.pi * tone_hz * t)).astype("float32")

    with tempfile.TemporaryDirectory() as tmp:
        stretched = Tts(tmp, backend="chatterbox")._time_stretch(tone, sample_rate, pipeline.SLOW_SPEED)

    ratio = len(stretched) / len(tone)
    check(len(stretched) > len(tone), "slow clip is longer than normal, not shorter")
    check(1.20 <= ratio <= 1.30,
          f"slow clip lasts 1/speed longer (ratio {ratio:.3f}, expect 1.25)")
    before = _dominant_hz(tone, sample_rate)
    after = _dominant_hz(stretched, sample_rate)
    check(abs(after - before) <= 5.0,
          f"slow keeps the pitch (tone {before:.0f} Hz -> {after:.0f} Hz, not resampled)")


def test_cache_miss_renders():
    """The blocking bug: an existing file with no cache entry must re-render."""
    with tempfile.TemporaryDirectory() as tmp:
        tts = Tts(tmp, backend="chatterbox")
        stale = os.path.join(tts.audio_dir, "clip.ogg")
        with open(stale, "w") as fh:
            fh.write("old audio from another engine")
        fp = tts._fingerprint("some text", "af_kore", 1.0)
        check(tts._cached("clip", fp) is None,
              "existing file with an empty cache is a miss (re-renders)")
        tts._cache["clip"] = fp
        check(tts._cached("clip", fp) == stale, "matching cache entry is a hit")
        tts._cache["clip"] = "stale-fingerprint"
        check(tts._cached("clip", fp) is None, "mismatched cache entry is a miss")
        tts._cache["clip"] = fp
        tts.force = True
        check(tts._cached("clip", fp) is None, "--force ignores the cache")
        # A file missing on disk is a miss even with a matching entry.
        os.remove(stale)
        tts.force = False
        check(tts._cached("clip", fp) is None, "missing file is a miss even when cached")

    # The backend tag is part of the fingerprint, so engines never share clips.
    with tempfile.TemporaryDirectory() as tmp:
        kokoro = Tts(tmp, backend="kokoro")
        chatter = Tts(tmp, backend="chatterbox")
        check(kokoro._fingerprint("t", "af_kore", 1.0) != chatter._fingerprint("t", "af_kore", 1.0),
              "kokoro and chatterbox fingerprints never collide")


def test_cache_flush_durable():
    """An interrupted run must resume from the flushed cache, losing few clips."""
    # Clip threshold: flush only once the pending count reaches it.
    with tempfile.TemporaryDirectory() as tmp:
        tts = Tts(tmp, backend="chatterbox")
        tts._flush_every = 3
        tts._flush_interval = 3600.0
        tts._remember("c0", "fp0")
        tts._remember("c1", "fp1")
        check(not os.path.exists(tts._cache_path), "no flush before the clip threshold")
        tts._remember("c2", "fp2")
        check(os.path.exists(tts._cache_path), "flush once the clip threshold is reached")
        with open(tts._cache_path, encoding="utf-8") as fh:
            on_disk = json.load(fh)
        check(on_disk == {"c0": "fp0", "c1": "fp1", "c2": "fp2"},
              "the flushed cache holds every clip so far")
        check(not tts._cache_dirty and tts._since_flush == 0,
              "flush clears the dirty flag and the pending counter")
        check(not os.path.exists(tts._cache_path + ".tmp"),
              "flush leaves no .tmp behind (atomic replace)")

    # Time threshold: flush even when only one clip is done (slow clips).
    with tempfile.TemporaryDirectory() as tmp:
        tts = Tts(tmp, backend="chatterbox")
        tts._flush_every = 100000
        tts._flush_interval = 0.0
        tts._remember("t0", "fp0")
        check(os.path.exists(tts._cache_path), "flush by elapsed time, not only by count")

    # A fresh Tts picks up what the interrupted run already flushed.
    with tempfile.TemporaryDirectory() as tmp:
        first = Tts(tmp, backend="chatterbox")
        first._flush_every = 1
        first._remember("keep", "fp")
        second = Tts(tmp, backend="chatterbox")
        check(second._cache.get("keep") == "fp",
              "a restarted pipeline resumes from the flushed cache")


def test_no_render_db_maps():
    """A --no-render pass maps existing clips and never calls the backend."""
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "content")
        audio = os.path.join(out, "audio")
        os.makedirs(audio)
        with open(os.path.join(audio, "dr1.en-GB-female.ogg"), "w"):
            pass

        class NoBackend(Tts):
            def _say_kokoro(self, *a, **k):
                raise AssertionError("dry mode must not render (kokoro)")

            def _say_chatterbox(self, *a, **k):
                raise AssertionError("dry mode must not render (chatterbox)")

        tts = NoBackend(out, backend="chatterbox", dry=True)
        tts.narrators = LANGUAGES["en"]["narrators"]
        tts.voices = {"en-GB-female": "bf_lily", "en-GB-male": "bm_fable"}
        drill = {"id": "dr1", "text": "Do you want to share a taxi?"}
        add_audio("drills", drill, tts, "en")
        check(drill["audio"].get("en-GB-female") == "audio/dr1.en-GB-female.ogg",
              "no-render: an existing clip gets its map entry")
        check("en-GB-male" not in drill["audio"] and "en-GB-female:slow" not in drill["audio"],
              "no-render: missing clips get no map entry")
        check(tts.say("dr1.en-GB-male", "x", "bm_fable") is None,
              "no-render: say() returns None for a missing clip")
        check(not os.path.exists(os.path.join(audio, ".cache.json")),
              "no-render: writes no cache")
        check(not os.path.exists(os.path.join(audio, ".cache.json.tmp")),
              "no-render: leaves no cache tmp file")


def test_external_renderer_languages():
    """fr/de/ru: `--no-render` builds the DB and wires clips; a render is refused."""
    here = os.path.dirname(os.path.abspath(__file__))
    script = os.path.join(here, "pipeline.py")
    samples = {
        "fr": ("fr-FR", "Tu as vu la rue ?"),
        "de": ("de-DE", "Die Tür ist grün."),
        "ru": ("ru-RU", "Мы были дома."),
    }
    for language, (accent, text) in samples.items():
        config = LANGUAGES[language]
        check(set(config["narrators"]) == {f"{accent}-female", f"{accent}-male"},
              f"{language}: one narrator key per gender for {accent}")
        check(config["accents"] == (accent,) and config.get("renderer") == "cartesia",
              f"{language}: single accent, rendered outside the pipeline")

        with tempfile.TemporaryDirectory() as tmp:
            source = os.path.join(tmp, "dist")
            out = os.path.join(tmp, "out")
            os.makedirs(os.path.join(source, "drills"))
            os.makedirs(os.path.join(out, "audio"))
            drill = {"id": f"{language}.d1", "language": language, "level": "A1",
                     "theme": "home", "target": "x", "text": text}
            with open(os.path.join(source, "drills", "a1.json"), "w", encoding="utf-8") as fh:
                json.dump({"version": 1, "language": language, "drills": [drill]}, fh, ensure_ascii=False)
            clip = f"{language}.d1.{accent}-female.ogg"
            with open(os.path.join(out, "audio", clip), "w"):
                pass
            args = [sys.executable, script, "--language", language, "--source", source, "--out", out]

            refused = subprocess.run(args, capture_output=True, text=True)
            check(refused.returncode != 0 and "--no-render" in refused.stderr,
                  f"{language}: a render is refused and points at --no-render")
            check(not os.path.exists(os.path.join(out, "content.db")),
                  f"{language}: a refused render writes no DB")

            built = subprocess.run(args + ["--no-render"], capture_output=True, text=True)
            check(built.returncode == 0, f"{language}: --no-render builds ({built.stderr.strip()[-200:]})")
            db = os.path.join(out, "content.db")
            rows = []
            if os.path.isfile(db):
                conn = sqlite3.connect(db)
                rows = conn.execute("select language, data from content").fetchall()
                conn.close()
            check(len(rows) == 1 and rows[0][0] == language, f"{language}: the drill is in content.db")
            audio = json.loads(rows[0][1]).get("audio", {}) if rows else {}
            check(audio == {f"{accent}-female": f"audio/{clip}"},
                  f"{language}: only the clip on disk is wired, under its voice key")
            check(not os.path.exists(os.path.join(out, "audio", ".provenance.json")),
                  f"{language}: no pipeline provenance for clips another tool rendered")


def test_audio_map_check():
    """The fail-closed guard: clips on disk must be in the DB maps, and vice versa."""
    check("stories" in pipeline.AUDIO_KINDS and "articles" in pipeline.AUDIO_KINDS,
          "the default audio kinds cover stories and articles")

    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "content")
        audio = os.path.join(out, "audio")
        os.makedirs(audio)
        conn = sqlite3.connect(os.path.join(out, "content.db"))
        conn.executescript(pipeline.SCHEMA)

        def insert(item_id, kind, data):
            conn.execute(
                "insert or replace into content values (?,?,?,?,?,?,?,?)",
                (item_id, kind, "en", "A1", "t", "text", None, json.dumps(data)),
            )
            conn.commit()

        # A clip on disk whose map is empty: exactly the stories/articles bug.
        with open(os.path.join(audio, "s1.p0.en-GB-female.ogg"), "w"):
            pass
        insert("s1", "STORY", {"id": "s1", "paragraphs": ["Hello."], "paragraphAudio": []})
        problems = pipeline.verify_audio_maps(conn, out)
        check(any("s1" in p and "empty" in p for p in problems),
              "clips on disk with an empty paragraphAudio is reported")

        # The reverse: a map entry with no clip on disk.
        insert("s2", "STORY", {"id": "s2", "paragraphAudio": [{"en-GB-female": "audio/s2.p0.en-GB-female.ogg"}]})
        problems = pipeline.verify_audio_maps(conn, out)
        check(any("s2" in p and "missing" in p for p in problems),
              "a map entry with no clip on disk is reported")

        # A clip with its map entry is clean.
        with open(os.path.join(audio, "s3.p0.en-GB-female.ogg"), "w"):
            pass
        insert("s3", "STORY", {"id": "s3", "paragraphAudio": [{"en-GB-female": "audio/s3.p0.en-GB-female.ogg"}]})
        # s1 (empty map) and s2 (missing clip) are already in the DB, so only s3
        # is asserted clean here: clear them first to isolate the positive case.
        conn.execute("delete from content where id in ('s1','s2')")
        conn.commit()
        problems = pipeline.verify_audio_maps(conn, out)
        check(not problems, "a clip with its map entry is clean")
        conn.close()


def test_profile_version_derived():
    """The cache tag tracks the profile behaviour, not a hand-written string."""
    check(intonation.PROFILE_HASH and intonation.PROFILE_HASH != intonation.LEGACY_PROFILE_VERSION,
          "profile hash is distinct from the legacy tag")
    check(intonation.profile_hash() == intonation.PROFILE_HASH,
          "profile hash is deterministic")
    # The hash must actually depend on the profile: perturb a delta and it moves.
    saved = intonation.CFG_MIN
    try:
        intonation.CFG_MIN = saved - 0.01
        check(intonation.profile_hash() != intonation.PROFILE_HASH,
              "changing a profile bound changes the derived tag")
    finally:
        intonation.CFG_MIN = saved
    check(intonation.profile_hash() == intonation.PROFILE_HASH,
          "the tag is stable again once the profile is restored")
    # Activation is a deliberate, separate step (migration first).
    check(intonation.PROFILE_VERSION == intonation.LEGACY_PROFILE_VERSION,
          "hash tag is not active yet (cache migration pending)")


def test_hashed_fingerprint():
    """The activation fingerprint also covers the checkpoint and the voice ref."""
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "content")
        os.makedirs(out)
        model_a, model_b = os.path.join(tmp, "ma"), os.path.join(tmp, "mb")
        os.makedirs(model_a)
        os.makedirs(model_b)
        with open(os.path.join(model_a, "t3.pt"), "wb") as fh:
            fh.write(b"AAAA")
        with open(os.path.join(model_b, "t3.pt"), "wb") as fh:
            fh.write(b"BBBBBB")
        refs_a, refs_b = os.path.join(tmp, "ra"), os.path.join(tmp, "rb")
        os.makedirs(refs_a)
        os.makedirs(refs_b)
        with open(os.path.join(refs_a, "bf_lily.wav"), "wb") as fh:
            fh.write(b"ref-A")
        with open(os.path.join(refs_b, "bf_lily.wav"), "wb") as fh:
            fh.write(b"ref-B")

        def fp(model, refs):
            tts = Tts(out, backend="chatterbox", model_dir=model, refs_dir=refs)
            return tts._fingerprint("hello", "bf_lily", 1.0)

        import hashlib

        legacy_a, legacy_b = fp(model_a, refs_a), fp(model_b, refs_b)
        historical = hashlib.sha1(
            "hello|bf_lily|1.0|chatterbox|glosso-intonation-v1".encode("utf-8")).hexdigest()
        check(legacy_a == historical,
              "flag off: the fingerprint is byte-identical to the pre-migration one")
        check(legacy_a == legacy_b,
              "flag off: the legacy fingerprint ignores the checkpoint and the reference")
        check(legacy_a == Tts(out, backend="chatterbox", model_dir=model_a, refs_dir=refs_a)
              ._legacy_fingerprint("hello", "bf_lily", 1.0),
              "flag off: _fingerprint delegates to the legacy computation")

        saved_flag = intonation.USE_HASHED_PROFILE_VERSION
        saved_version = intonation.PROFILE_VERSION
        try:
            intonation.USE_HASHED_PROFILE_VERSION = True
            intonation.PROFILE_VERSION = intonation.PROFILE_HASH
            base = fp(model_a, refs_a)
            check(base != legacy_a, "flag on: the hashed fingerprint differs from legacy")
            check(base != fp(model_b, refs_a),
                  "flag on: changing the checkpoint changes the fingerprint")
            check(base != fp(model_a, refs_b),
                  "flag on: regenerating the reference changes the fingerprint")
            check(base == fp(model_a, refs_a),
                  "flag on: the hashed fingerprint is deterministic")
        finally:
            intonation.USE_HASHED_PROFILE_VERSION = saved_flag
            intonation.PROFILE_VERSION = saved_version


def test_migrate_cache():
    """--migrate-cache re-tags only clips it can prove are from this config."""
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "content")
        audio = os.path.join(out, "audio")
        os.makedirs(audio)

        text = "Do you want to share a taxi?"
        voice = "bf_lily"
        tts0 = Tts(out, backend="chatterbox")
        legacy = tts0._legacy_fingerprint(text, voice, 1.0)
        good_prov = {
            "provider": "chatterbox", "model": tts0.model_revision, "voice": voice,
            "profile": intonation.LEGACY_PROFILE_VERSION,
            "license": "MIT", "commercial_license": True, "date": "2026-09-22T00:00:00Z",
        }

        # dr1: legacy cache entry + matching provenance -> migratable.
        open(os.path.join(audio, "dr1.en-GB-female.ogg"), "w").close()
        tts0._cache["dr1.en-GB-female"] = legacy
        tts0._provenance["dr1.en-GB-female"] = dict(good_prov)
        # dr2: the cache entry does not match the clip -> unverifiable.
        open(os.path.join(audio, "dr2.en-GB-female.ogg"), "w").close()
        tts0._cache["dr2.en-GB-female"] = "bogus-fingerprint"
        tts0._provenance["dr2.en-GB-female"] = dict(good_prov)
        # dr3: matching cache but no provenance at all -> unverifiable.
        open(os.path.join(audio, "dr3.en-GB-female.ogg"), "w").close()
        tts0._cache["dr3.en-GB-female"] = legacy
        # dr4: matching cache but provenance from another checkpoint -> unverifiable.
        open(os.path.join(audio, "dr4.en-GB-female.ogg"), "w").close()
        tts0._cache["dr4.en-GB-female"] = legacy
        tts0._provenance["dr4.en-GB-female"] = dict(good_prov, model="some-other-checkpoint")
        # dr5: matching cache but provenance from another voice reference -> unverifiable.
        open(os.path.join(audio, "dr5.en-GB-female.ogg"), "w").close()
        tts0._cache["dr5.en-GB-female"] = legacy
        tts0._provenance["dr5.en-GB-female"] = dict(good_prov, ref="sha1:deadbeef0000")
        tts0._cache_dirty = True
        tts0._provenance_dirty = True
        tts0.flush()

        class NoBackend(Tts):
            def _say_kokoro(self, *a, **k):
                raise AssertionError("migrate must not render (kokoro)")

            def _say_chatterbox(self, *a, **k):
                raise AssertionError("migrate must not render (chatterbox)")

        saved_flag = intonation.USE_HASHED_PROFILE_VERSION
        saved_version = intonation.PROFILE_VERSION
        try:
            intonation.USE_HASHED_PROFILE_VERSION = True
            intonation.PROFILE_VERSION = intonation.PROFILE_HASH
            tts = NoBackend(out, backend="chatterbox", migrate=True)
            tts.narrators = LANGUAGES["en"]["narrators"]
            tts.voices = {"en-GB-female": "bf_lily", "en-GB-male": "bm_fable"}

            item = {"id": "dr1", "text": text}
            add_audio("drills", item, tts, "en")
            expected_new = tts._hashed_fingerprint(text, voice, 1.0)
            check(tts._cache.get("dr1.en-GB-female") == expected_new,
                  "migrate: a verified clip is re-tagged to the hashed fingerprint")
            check(item["audio"].get("en-GB-female") == "audio/dr1.en-GB-female.ogg",
                  "migrate: a verified clip keeps its map entry")
            check(tts._provenance["dr1.en-GB-female"]["profile"] == intonation.PROFILE_HASH,
                  "migrate: provenance is updated to the new profile tag")

            for bad in ("dr2", "dr3", "dr4", "dr5"):
                bad_item = {"id": bad, "text": text}
                add_audio("drills", bad_item, tts, "en")
                check(bad_item["audio"] == {},
                      f"migrate: the unverifiable clip {bad} is not mapped")
            check(tts._cache.get("dr2.en-GB-female") == "bogus-fingerprint",
                  "migrate: an unverifiable clip keeps its fingerprint untouched")
            check("dr3.en-GB-female" not in tts._provenance,
                  "migrate: no provenance means no sidecar write")
            check(tts.migration_migrated > 0 and tts.migration_skipped >= 4,
                  "migrate: reports the migrated and skipped clips")

            # Idempotent: a second pass over the already-migrated cache is a no-op.
            tts.flush()
            again = NoBackend(out, backend="chatterbox", migrate=True)
            again.narrators = LANGUAGES["en"]["narrators"]
            again.voices = {"en-GB-female": "bf_lily", "en-GB-male": "bm_fable"}
            item2 = {"id": "dr1", "text": text}
            add_audio("drills", item2, again, "en")
            check(again._cache.get("dr1.en-GB-female") == expected_new
                  and item2["audio"].get("en-GB-female") == "audio/dr1.en-GB-female.ogg",
                  "migrate: re-running is idempotent")
        finally:
            intonation.USE_HASHED_PROFILE_VERSION = saved_flag
            intonation.PROFILE_VERSION = saved_version


def test_license_gate():
    """A provider without a declared commercial license must not be published."""
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "content")
        audio = os.path.join(out, "audio")
        os.makedirs(audio)
        with open(os.path.join(audio, "dr1.en-GB-female.ogg"), "w"):
            pass
        tts = Tts(out, backend="some-unlicensed-engine", dry=True)
        check(tts.licensed is False, "unknown provider has no verified commercial license")
        tts.narrators = LANGUAGES["en"]["narrators"]
        tts.voices = {"en-GB-female": "bf_lily", "en-GB-male": "bm_fable"}
        drill = {"id": "dr1", "text": "Do you want to share a taxi?"}
        add_audio("drills", drill, tts, "en")
        check(drill["audio"] == {}, "an unlicensed clip is not published")
        check(not tts._provenance, "no provenance is recorded for an unlicensed provider")


def test_provenance_recorded():
    """Every published clip records provider/model/voice/profile/license/date."""
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "content")
        audio = os.path.join(out, "audio")
        os.makedirs(audio)
        with open(os.path.join(audio, "dr1.en-GB-female.ogg"), "w"):
            pass
        tts = Tts(out, backend="chatterbox", dry=True)
        tts.narrators = LANGUAGES["en"]["narrators"]
        tts.voices = {"en-GB-female": "bf_lily", "en-GB-male": "bm_fable"}
        drill = {"id": "dr1", "text": "Do you want to share a taxi?"}
        add_audio("drills", drill, tts, "en")
        rec = tts._provenance.get("dr1.en-GB-female")
        check(rec is not None, "a published clip records provenance")
        if rec:
            check(rec["provider"] == "chatterbox", "provenance: provider")
            check(rec["voice"] == "bf_lily", "provenance: voice is the cloned reference")
            check(rec["license"] == "MIT" and rec["commercial_license"] is True,
                  "provenance: carries the commercial license")
            check(rec["profile"] == intonation.PROFILE_VERSION,
                  "provenance: profile matches the cache tag")
            check(bool(rec["date"]) and bool(rec["model"]),
                  "provenance: has a date and a model revision")
        tts.flush()
        check(os.path.exists(tts._provenance_path), "flush writes .provenance.json")
        with open(tts._provenance_path, encoding="utf-8") as fh:
            on_disk = json.load(fh)
        check("dr1.en-GB-female" in on_disk, "the provenance sidecar holds the clip")


def main():
    kokoro, drill, word, story, article, dialogue = render("kokoro")
    chatter, *_ = render("chatterbox")

    names_kokoro = [c[0] for c in kokoro.calls]
    names_chatter = [c[0] for c in chatter.calls]
    check(names_kokoro == names_chatter, "both backends produce identical name lists")

    expected = [
        # drills: every narrator, plus the slow variant (British only for now)
        "dr1.en-GB-female", "dr1.en-GB-male",
        "dr1.en-GB-female.slow", "dr1.en-GB-male.slow",
        # words: long-form voices
        "w1.en-GB-female", "w1.en-GB-male",
        # stories and articles: paragraphAudio
        "s1.p0.en-GB-female", "s1.p0.en-GB-male",
        "a1.p0.en-GB-female", "a1.p0.en-GB-male",
        # dialogues: cast gender picks the narrator for the single en-GB accent
        "d1.t0.en-GB-female",
        "d1.t1.en-GB-male",
    ]
    check(sorted(names_chatter) == sorted(expected), "names match the app contract exactly")

    # The audio entries the app consumes must be the .ogg paths of those clips.
    audio_values = (
        list(drill["audio"].values()) + list(word["audio"].values())
        + [p[k] for p in story["paragraphAudio"] for k in p]
        + [p[k] for p in article["paragraphAudio"] for k in p]
        + [t["audio"][k] for t in dialogue["turns"] for k in t["audio"]]
    )
    expected_paths = {f"audio/{n}.ogg" for n in expected}
    check(set(audio_values) == expected_paths,
          "every audio entry the app consumes is the .ogg of its clip")

    # Speaker/kind threading (only the chatterbox path carries them).
    kinds = {(c[4], c[5]) for c in chatter.calls}
    check(("narrator", "drill") in kinds, "drills render kind=drill")
    check(("narrator", "word") in kinds, "words render kind=word")
    check(("narrator", "story") in kinds, "stories render kind=story")
    check(("narrator", "article") in kinds, "articles render kind=article")
    check(("Maya", "dialogue") in kinds and ("Ben", "dialogue") in kinds,
          "dialogues thread the cast speaker through")

    # Slow variant must exist at speed 0.8 with the '.slow' name suffix.
    slows = {c[0]: c[3] for c in chatter.calls if c[0].endswith(".slow")}
    check(slows and all(s == pipeline.SLOW_SPEED for s in slows.values()),
          "'.slow' clips are rendered at 0.8")

    test_write_format()
    test_slow_stretch()
    test_cache_miss_renders()
    test_cache_flush_durable()
    test_no_render_db_maps()
    test_audio_map_check()
    test_profile_version_derived()
    test_hashed_fingerprint()
    test_migrate_cache()
    test_license_gate()
    test_provenance_recorded()
    test_external_renderer_languages()

    # Importing the pipeline must not drag torch/kokoro/chatterbox in.
    here = os.path.dirname(os.path.abspath(__file__))
    with tempfile.TemporaryDirectory(prefix="glosso-probe-") as probe_dir:
        probe = (
            "import sys, json; sys.path.insert(0, %r); import pipeline; "
            "from pipeline import Tts; "
            "t = Tts(%r, backend='chatterbox'); t._fingerprint('a', 'af_kore', 1.0); "
            "json.dumps(t.voices); "
            "assert not {'torch', 'kokoro', 'chatterbox'} & set(sys.modules), "
            "[m for m in ('torch', 'kokoro', 'chatterbox') if m in sys.modules]; print('CLEAN')"
        ) % (here, probe_dir)
        out = subprocess.run([sys.executable, "-c", probe], capture_output=True, text=True)
    check(out.returncode == 0 and "CLEAN" in out.stdout,
          f"importing pipeline pulls no torch/kokoro/chatterbox ({out.stderr.strip()[-200:]})")

    if FAILURES:
        print(f"\n{len(FAILURES)} failures")
        sys.exit(1)
    print("\nall naming tests passed")


if __name__ == "__main__":
    main()
