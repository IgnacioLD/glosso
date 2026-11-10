"""Deterministic delivery profiles for Chatterbox rendering.

Two pure layers combine into the generation knobs (exaggeration, cfg_weight)
and a per-clip torch seed, so the same (speaker, voice, text) always renders
the same clip while different lines still sound naturally varied:

  character: stable trait per (speaker, voicekey), from a fixed seed. Some
             characters are dryer, some more expressive.
  line:      text modulation. Questions get more expression with the pace
             held back; exclamations get more exaggeration; hesitations
             ("Well,", "Hmm,", ellipsis) get softer and slower; long lines
             pick up pace; quoted speech lifts calm narration.

No torch here: pipeline.py imports this module unconditionally, so it must
stay dependency-free (stdlib only).

Chatterbox knobs (chatterbox.tts.ChatterboxTTS.generate), calibrated by
measuring the installed model on the L40S (tools/content/intonation_ab.py):
  exaggeration  emotion_adv in [0, 1]; 0.5 is the model default. Higher values
                lift the mean pitch (~+6% for 0.25 -> 0.80) and widen the pitch
                range, and read as "more expressive".
  cfg_weight    classifier-free guidance. Deliberately below 0.5 it slows the
                delivery (~+12% duration for 0.50 -> 0.30); at/above 0.5 it
                plateaus. So lower cfg = slower, not faster.
Deltas smaller than ~0.05 are not audible, so the line layer moves the knobs
by 0.13-0.38 (raised once already: at 0.10-0.30 the owner heard "almost no
difference, at least it is not flat").
"""

import hashlib

# The chatterbox cache fingerprint folds in a version of this profile. That
# version is DERIVED from the profile behaviour at the bottom of this file (a
# hash of the knob outputs for a fixed probe set), never hand-written, so
# changing a delta changes the tag and stale clips cannot be reused. See
# LEGACY_PROFILE_VERSION / profile_hash() / USE_HASHED_PROFILE_VERSION, and
# docs/TTS-CACHE.md for the full activation plan (why the flag is off today,
# and the ordered migration).

# Sane bounds: outside these Chatterbox audio starts sounding broken.
EXAGGERATION_MIN = 0.25
EXAGGERATION_MAX = 0.80
CFG_MIN = 0.30
CFG_MAX = 0.70

# Per-kind baselines (exaggeration, cfg_weight). Long-form narration is
# calmer than drills, words and dialogue turns.
KIND_BASELINES = {
    "drill": (0.50, 0.50),
    "word": (0.50, 0.50),
    "story": (0.40, 0.55),
    "article": (0.40, 0.55),
    "dialogue": (0.50, 0.50),
}

# Line openings that read as a hesitation: render them softer and slower.
HESITANT_STARTS = (
    "well,", "hmm,", "hmm.", "uh,", "um,", "erm,", "oh,", "ah,",
    "let's see,", "let me think", "let me see,", "you know,",
)

# Quoted speech in narration uses double quotes (straight or curly).
QUOTE_MARKS = "\"\u201c\u201d"

# Closing quotes/brackets to ignore when reading the sentence-final mark, so
# `"Really?"` still counts as a question.
TRAILING_CLOSERS = "\"'\u201c\u201d\u2018\u2019)]}"


def _stable(*parts):
    digest = hashlib.sha1("|".join(str(p) for p in parts).encode("utf-8")).digest()
    return int.from_bytes(digest, "big")


def _ends_with(text, mark):
    return text.rstrip(TRAILING_CLOSERS).endswith(mark)


def character_profile(speaker, voicekey):
    """Stable delivery trait for one (speaker, voicekey) pair.

    Returns {"exaggeration", "cfg", "seed"}; deterministic across runs and
    machines. Expressive characters push emotion up and let the pace
    breathe; dry ones sit flatter and steadier.
    """
    h = _stable("glosso-character-v1", speaker, voicekey)
    expressiveness = (h % 1001) / 1000.0
    return {
        "exaggeration": round(0.40 + 0.20 * expressiveness, 3),
        "cfg": round(0.55 - 0.15 * expressiveness, 3),
        "seed": h % (2 ** 31),
    }


def line_profile(text, character, kind="drill"):
    """Character trait plus text modulation, clamped to sane bounds.

    Pure: same (text, character, kind) always returns the same profile.
    """
    base_ex, base_cfg = KIND_BASELINES.get(kind, KIND_BASELINES["drill"])
    exaggeration = base_ex + (character["exaggeration"] - 0.50)
    cfg = base_cfg + (character["cfg"] - 0.475)

    stripped = " ".join(text.split()).strip()
    lowered = stripped.lower()

    # Deltas sized from the measured knob response: below ~0.05 nothing is
    # audible, so the line layer moves the knobs by 0.13-0.38. Lower cfg slows.
    if _ends_with(stripped, "?"):
        # Questions lean in (more energy) with the pace held back.
        exaggeration += 0.32
        cfg -= 0.13
    elif _ends_with(stripped, "!"):
        # Exclamations push emotion to the top and move a touch quicker.
        exaggeration += 0.38
        cfg += 0.13
    elif lowered.startswith(HESITANT_STARTS) or "..." in stripped or "\u2026" in stripped:
        # Trailing off: flatter and slower.
        exaggeration -= 0.26
        cfg -= 0.19

    if len(stripped.split()) > 24:
        # Long lines get a touch more momentum so they do not drag.
        exaggeration += 0.05

    if kind in ("story", "article") and any(q in stripped for q in QUOTE_MARKS):
        # Narration stays serene, quoted speech lifts it.
        exaggeration += 0.15

    seed = _stable("glosso-clip-v1", character["seed"], kind, stripped) % (2 ** 31)
    return {
        "exaggeration": round(min(EXAGGERATION_MAX, max(EXAGGERATION_MIN, exaggeration)), 2),
        "cfg": round(min(CFG_MAX, max(CFG_MIN, cfg)), 2),
        "seed": seed,
    }


# --- cache tag ---------------------------------------------------------------
# The tag that pipeline.py folds into the chatterbox cache fingerprint is a hash
# of the profile outputs for a FIXED probe set (every trigger and kind), so it
# tracks the actual knobs instead of a hand-written string. The legacy tag and a
# switch stay for the one-time migration: the clips already rendered use the
# current profile, so activating the hash and rewriting their fingerprints
# (pipeline.py --migrate-cache, which only re-tags verifiable clips) reuses them
# with no re-render. Do not flip the switch until the in-flight render finishes:
# see docs/TTS-CACHE.md for the ordered migration and rollback.
LEGACY_PROFILE_VERSION = "glosso-intonation-v1"
USE_HASHED_PROFILE_VERSION = False

_PROBE_SPEAKERS = ("narrator", "Maya", "Ben", "Clara")
_PROBE_VOICES = ("bf_lily", "bm_fable", "af_kore", "am_puck", "ff_siwis")
_PROBE_TEXTS = (
    "The meeting starts in twenty minutes.",
    "Do you want to share a taxi?",
    "What a morning!",
    "Well, I would rather wait under this awning...",
    "Hmm, let me think about that for a second.",
    "The rain had stopped. \"You came,\" he said, as if he had never doubted it.",
    " ".join(["word"] * 30) + ".",
)
_PROBE_KINDS = ("drill", "word", "story", "article", "dialogue", "unknown-kind")


def _canon(profile):
    return ",".join("%s=%s" % (k, profile[k]) for k in sorted(profile))


def profile_hash():
    """Stable hash of the profile outputs for a fixed probe set."""
    h = hashlib.sha1()
    for speaker in _PROBE_SPEAKERS:
        for voice in _PROBE_VOICES:
            char = character_profile(speaker, voice)
            h.update(_canon(char).encode())
            for text in _PROBE_TEXTS:
                for kind in _PROBE_KINDS:
                    h.update(_canon(line_profile(text, char, kind)).encode())
    return h.hexdigest()[:12]


PROFILE_HASH = profile_hash()
PROFILE_VERSION = PROFILE_HASH if USE_HASHED_PROFILE_VERSION else LEGACY_PROFILE_VERSION
