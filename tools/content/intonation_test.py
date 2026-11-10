"""Unit tests for the pure intonation profiles. Run:  python3 intonation_test.py

No torch, no chatterbox: the profiles must stay dependency-free because
pipeline.py imports them unconditionally.
"""

import sys

import intonation
from intonation import (
    CFG_MAX,
    CFG_MIN,
    EXAGGERATION_MAX,
    EXAGGERATION_MIN,
    character_profile,
    line_profile,
)

FAILURES = []


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print(f"FAIL {msg}")
    else:
        print(f"ok   {msg}")


TEXTS = [
    "The meeting starts in twenty minutes.",
    "Do you want to share a taxi?",
    "What a morning!",
    "Well, I would rather wait under this awning...",
    "Hmm, let me think about that for a second.",
    "It",
    "Right, so let me walk you through the whole plan again because there are many steps "
    "and the last time we tried it we forgot the part about the tickets, remember?",
    "The rain had stopped. \"You came,\" he said, as if he had never doubted it.",
]

KINDS = ["drill", "word", "story", "article", "dialogue", "unknown-kind"]
SPEAKERS = ["narrator", "Maya", "Ben", "Clara"]


def main():
    # Determinism: same inputs, same outputs, always.
    for speaker in SPEAKERS:
        for voice in ("af_kore", "am_puck", "bf_lily", "bm_fable", "ff_siwis"):
            char = character_profile(speaker, voice)
            for _ in range(3):
                check(char == character_profile(speaker, voice),
                      f"character deterministic {speaker}/{voice}")
            for text in TEXTS:
                for kind in KINDS:
                    first = line_profile(text, char, kind)
                    again = line_profile(text, character_profile(speaker, voice), kind)
                    check(first == again, f"line deterministic {speaker}/{voice}/{kind}")

    # Stability and spread: a character never changes, but characters differ.
    traits = {s: character_profile(s, "af_kore") for s in SPEAKERS}
    for s, t in traits.items():
        check(EXAGGERATION_MIN <= t["exaggeration"] <= EXAGGERATION_MAX,
              f"character exaggeration in range {s}")
        check(CFG_MIN <= t["cfg"] <= CFG_MAX, f"character cfg in range {s}")
    check(len({t["exaggeration"] for t in traits.values()}) > 1,
          "characters differ in expressiveness")

    # Line effects, relative to the same character's neutral baseline.
    neutral = line_profile("The meeting starts in twenty minutes.", traits["Maya"], "drill")
    question = line_profile("The meeting starts in twenty minutes?", traits["Maya"], "drill")
    exclaim = line_profile("The meeting starts in twenty minutes!", traits["Maya"], "drill")
    hesitant = line_profile("Well, the meeting starts in twenty minutes...", traits["Maya"], "drill")
    long_line = line_profile(
        " ".join(["word"] * 30) + ".", traits["Maya"], "drill")
    check(question["exaggeration"] > neutral["exaggeration"], "question: more expression")
    check(question["cfg"] < neutral["cfg"], "question: pace held back (lower cfg slows)")
    check(exclaim["exaggeration"] > neutral["exaggeration"], "exclaim: more exaggeration")
    check(exclaim["cfg"] > neutral["cfg"], "exclaim: a touch quicker")
    check(hesitant["exaggeration"] < neutral["exaggeration"], "hesitation: softer")
    check(hesitant["cfg"] < neutral["cfg"], "hesitation: slower")
    check(long_line["exaggeration"] > neutral["exaggeration"], "long line: a bit more momentum")
    # Sub-0.05 moves are inaudible, so the line layer must move the knobs more.
    check(question["exaggeration"] - neutral["exaggeration"] >= 0.20,
          "question exaggeration delta is audible (>=0.20)")
    check(neutral["cfg"] - hesitant["cfg"] >= 0.15,
          "hesitant cfg delta is audible (>=0.15)")
    # Measured on a character away from the clamps, the deltas must be >0.30.
    roomy = character_profile("narrator", "am_puck")
    q = line_profile("Right?", roomy, "drill")
    n = line_profile("Right.", roomy, "drill")
    check(q["exaggeration"] - n["exaggeration"] >= 0.30,
          "question exaggeration travel >= 0.30 where it is not clamped")
    h = line_profile("Right...", roomy, "drill")
    check(n["cfg"] - h["cfg"] >= 0.15,
          "hesitant cfg travel >= 0.15 where it is not clamped")

    story_plain = line_profile("He said she was right.", traits["Maya"], "story")
    story_quoted = line_profile("\"You came,\" he said.", traits["Maya"], "story")
    check(story_quoted["exaggeration"] > story_plain["exaggeration"],
          "story: quoted speech lifts expression")
    drill = line_profile("The meeting starts.", traits["Maya"], "drill")
    check(story_plain["exaggeration"] < drill["exaggeration"], "story narration calmer than drills")

    # A sentence-final mark behind a closing quote still counts.
    quoted_q = line_profile("\"Really?\"", traits["Maya"], "drill")
    quoted_stmt = line_profile("\"Really.\"", traits["Maya"], "drill")
    check(quoted_q["exaggeration"] > quoted_stmt["exaggeration"],
          "question behind a closing quote still gets question expressiveness")

    # `word` shares the documented drill/dialogue baseline.
    word_kind = line_profile("umbrella", traits["Maya"], "word")
    drill_kind = line_profile("umbrella", traits["Maya"], "drill")
    check(word_kind["exaggeration"] == drill_kind["exaggeration"]
          and word_kind["cfg"] == drill_kind["cfg"],
          "word baseline matches the documented drill/dialogue baseline")

    # Per-line variety: different texts get different seeds (varied prosody).
    seeds = {line_profile(t, traits["Maya"], "drill")["seed"] for t in TEXTS}
    check(len(seeds) == len(TEXTS), "each line gets its own seed")

    # Clamps hold even for combined extremes.
    loud = character_profile("x", "af_kore")
    for text in TEXTS:
        for kind in KINDS:
            p = line_profile(text.upper().replace(".", "!"), loud, kind)
            check(EXAGGERATION_MIN <= p["exaggeration"] <= EXAGGERATION_MAX,
                  f"clamped exaggeration {kind}")
            check(CFG_MIN <= p["cfg"] <= CFG_MAX, f"clamped cfg {kind}")

    # The module stays torch-free.
    check("torch" not in sys.modules, "intonation does not import torch")

    if FAILURES:
        print(f"\n{len(FAILURES)} failures")
        sys.exit(1)
    print("\nall intonation tests passed")


if __name__ == "__main__":
    main()
