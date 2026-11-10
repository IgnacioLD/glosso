"""Render the Chatterbox approval pack into docs/audio-samples/.

Run on the GPU box from tools/content (refs in ./voices):
  /opt/gen/venv-tts/bin/python render_samples.py --device cuda \
      --model-dir /opt/gen/models/chatterbox --refs-dir voices --out ../../docs/audio-samples

The pack demonstrates: one line per cloned voice, neutral vs question vs
exclamation for one voice, a two-character dialogue, a story paragraph with
quoted speech, and the .slow variant (atempo time stretch, pitch preserved).
"""

import argparse
import hashlib
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import soundfile as sf

from pipeline import SLOW_SPEED, Tts

NEUTRAL_EN = "The meeting starts in twenty minutes, so let's go over the plan once more."

# Two speakers, one per US voice family: the per-character profiles make
# Maya and Ben sound distinct even though the lines are similar.
DIALOGUE = [
    ("Maya", "af_kore", "Hey Ben, do you have a minute? I keep mixing up borrow and lend."),
    ("Ben", "am_puck", "Sure. You borrow something from someone, and you lend it to them."),
    ("Maya", "af_kore", "So I can borrow your pen, and you lend it to me? Nice, that one sticks."),
]

STORY = ("The rain had stopped by the time Clara reached the bridge. "
         "\"You came,\" Tomas said, as if he had never doubted it. "
         "She laughed and shook out her umbrella.")

SAMPLES = [
    # (output name, voice, text, kind, speaker, speed)
    ("voice-af_kore", "af_kore", NEUTRAL_EN, "drill", "narrator", 1.0),
    ("voice-am_puck", "am_puck", NEUTRAL_EN, "drill", "narrator", 1.0),
    ("voice-bf_lily", "bf_lily", NEUTRAL_EN, "drill", "narrator", 1.0),
    ("voice-bm_fable", "bm_fable", NEUTRAL_EN, "drill", "narrator", 1.0),
    ("intonation-neutral", "bf_lily", "I can't believe you actually did it.", "drill", "narrator", 1.0),
    ("intonation-question", "bf_lily", "I can't believe you actually did it?", "drill", "narrator", 1.0),
    ("intonation-exclaim", "bf_lily", "I can't believe you actually did it!", "drill", "narrator", 1.0),
    ("dialogue-turn0", "af_kore", DIALOGUE[0][2], "dialogue", "Maya", 1.0),
    ("dialogue-turn1", "am_puck", DIALOGUE[1][2], "dialogue", "Ben", 1.0),
    ("dialogue-turn2", "af_kore", DIALOGUE[2][2], "dialogue", "Maya", 1.0),
    ("story-quoted", "bf_lily", STORY, "story", "narrator", 1.0),
    # The `.slow` sample ships with its normal pair (same text and voice) so the
    # 1.25x lengthening is audible side by side.
    ("normal-af_kore", "af_kore", NEUTRAL_EN, "drill", "narrator", 1.0),
    ("slow-af_kore", "af_kore", NEUTRAL_EN, "drill", "narrator", SLOW_SPEED),
]


def pcm_sha256(path):
    # Ogg randomises the stream serial per file, so compare decoded audio.
    data, _ = sf.read(path, dtype="float32")
    return hashlib.sha256(data.tobytes()).hexdigest()


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(here, "..", "..", "docs", "audio-samples"))
    ap.add_argument("--tts", choices=("kokoro", "chatterbox"), default="chatterbox")
    ap.add_argument("--device", default=None, help="cuda/cpu (default: auto)")
    ap.add_argument("--refs-dir", default=os.path.join(here, "voices"))
    ap.add_argument("--model-dir", default=None)
    ap.add_argument("--force", action="store_true", help="re-render even if the cache matches")
    args = ap.parse_args()

    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    # Render into a scratch dir so the committed folder keeps only the .ogg
    # samples (no audio/ subdir, no .cache.json) and every run is fresh.
    with tempfile.TemporaryDirectory(prefix="glosso-samples-") as stage:
        tts = Tts(stage, backend=args.tts, device=args.device,
                  refs_dir=args.refs_dir, model_dir=args.model_dir, force=args.force)

        def render(final_name, text, voice, kind, speaker, speed):
            rel = tts.say(final_name, text, voice, speed, speaker=speaker, kind=kind)
            final = os.path.join(out, os.path.basename(rel))
            os.replace(os.path.join(stage, rel), final)
            return final

        durations = {}
        for name, voice, text, kind, speaker, speed in SAMPLES:
            final = render(name, text, voice, kind, speaker, speed)
            info = sf.info(final)
            durations[name] = info.duration
            print(f"{name:20s} {info.duration:5.2f}s  {info.samplerate} Hz  {info.channels}ch  {final}")

        # The rendered clips must be identical across runs (same profile seed +
        # deterministic CUDA kernels); Ogg only randomises its container serial.
        probe = render("determinism-probe", NEUTRAL_EN, "af_kore", "drill", "narrator", 1.0)
        first = pcm_sha256(probe)
        tts.force = True
        probe = render("determinism-probe", NEUTRAL_EN, "af_kore", "drill", "narrator", 1.0)
        second = pcm_sha256(probe)
        print(f"determinism: {'OK' if first == second else 'MISMATCH'} ({first[:12]} vs {second[:12]})")
        os.remove(probe)

    print("durations (s): " + " ".join(f"{k}={v:.2f}" for k, v in durations.items()))


if __name__ == "__main__":
    main()
