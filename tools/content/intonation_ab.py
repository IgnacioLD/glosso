"""Long-form intonation A/B for review.

One real 41-word story paragraph (en.story.city.006), same voice and the SAME
seed for every clip, so only the profile knobs change:

  long-neutral / long-question / long-exclaim / long-hesitant
      the real per-text profiles the content pipeline computes. These are the
      profiles that ship, so they are what lands in --out.

  long-extreme-high (exaggeration 0.80, cfg 0.30) and long-extreme-low
      (0.25, 0.70) are a knob-range PROBE only (they show the model's ceiling,
      not a shipped voice). They are written to a separate --probe-dir and are
      only rendered with --with-extremes, so the committed pack stays clean.

Run on the GPU box from tools/content (refs in ./voices):
  /opt/gen/venv-tts/bin/python intonation_ab.py --device cuda \
      --model-dir /opt/gen/models/chatterbox --refs-dir voices \
      --out ../../docs/audio-samples
"""

import argparse
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from intonation import character_profile, line_profile
from pipeline import Tts

BASE = ("The fountain in the central square had not worked for as long as anyone "
        "could remember, and the city had grown oddly comfortable with its silence. "
        "Tourists photographed it anyway, because it was old and because it appeared "
        "on every postcard.")

TEXTS = {
    "long-neutral": BASE,
    "long-question": BASE[:-1] + "?",
    "long-exclaim": BASE[:-1] + "!",
    "long-hesitant": BASE[:-1] + "...",
}

PROBES = {
    "long-extreme-high": {"exaggeration": 0.80, "cfg": 0.30},
    "long-extreme-low": {"exaggeration": 0.25, "cfg": 0.70},
}

VOICE = "af_kore"
KIND = "story"


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(here, "..", "..", "docs", "audio-samples"))
    ap.add_argument("--probe-dir", default=None,
                    help="where the extreme-range probes go (default: <out>/probe)")
    ap.add_argument("--with-extremes", action="store_true",
                    help="also render the knob-range probe clips (not committed)")
    ap.add_argument("--device", default=None)
    ap.add_argument("--refs-dir", default=os.path.join(here, "voices"))
    ap.add_argument("--model-dir", default=None)
    ap.add_argument("--voice", default=VOICE)
    args = ap.parse_args()

    out = os.path.abspath(args.out)
    probe_dir = os.path.abspath(args.probe_dir or os.path.join(out, "probe"))
    os.makedirs(out, exist_ok=True)
    character = character_profile("narrator", args.voice)
    seed = line_profile(BASE, character, KIND)["seed"]

    with tempfile.TemporaryDirectory(prefix="glosso-ab-") as stage:
        tts = Tts(stage, backend="chatterbox", device=args.device,
                  refs_dir=args.refs_dir, model_dir=args.model_dir, force=True)

        def render(dest, name, text, profile):
            rel = tts.say(name, text, args.voice, 1.0, speaker="narrator",
                          kind=KIND, profile=profile)
            final = os.path.join(dest, os.path.basename(rel))
            os.replace(os.path.join(stage, rel), final)
            import soundfile as sf

            print(f"{name:20s} ex={profile['exaggeration']:.2f} cfg={profile['cfg']:.2f} "
                  f"seed={profile['seed']}  {sf.info(final).duration:.2f}s  {final}")

        for name, text in TEXTS.items():
            profile = line_profile(text, character, KIND)
            profile["seed"] = seed
            render(out, name, text, profile)
        if args.with_extremes:
            os.makedirs(probe_dir, exist_ok=True)
            for name, knobs in PROBES.items():
                render(probe_dir, name, BASE, {"seed": seed, **knobs})


if __name__ == "__main__":
    main()

