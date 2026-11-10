"""Render the cloud-vs-on-device score-parity fixtures.

Four takes of the same sentence, chosen to cover the two behaviours the design
directives demand from the scorer:

  good.wav     - a real, clean take. Must be scored.
  silence.wav  - digital silence. Must come back "not evaluable".
  noise.wav    - room-like white noise. Must come back "not evaluable".
  low.wav      - the good take played back far too quietly. Must come back
                 "not evaluable" (audio too low, directive 5/29).

The good take is rendered with the project's Kokoro voice (the same TTS the
content pipeline uses), so the fixture is reproducible and matches the audio a
learner actually hears. The other three are derived from it with numpy, so the
only non-deterministic step is the TTS itself - which is why the rendered WAV is
committed rather than generated at test time.

Usage (the project's venv has kokoro + soundfile + numpy):

    tools/content/.venv/bin/python tools/score-parity/make-fixtures.py \
        androidApp/src/androidTest/assets/score-parity/fixtures

This is a one-off regeneration step. See
androidApp/src/androidTest/assets/score-parity/README.md.
"""

import hashlib
import os
import sys

import numpy as np
import soundfile as sf
from kokoro import KPipeline

TEXT = "I think my brother is thirsty."
# af_heart is the American English narrator used for the calibration takes.
VOICE = "af_heart"
KOKORO_RATE = 24000
MODEL_RATE = 16000  # the acoustic model reads raw 16 kHz PCM

# The quiet take is attenuated to sit below SpeechGate.MIN_RMS (0.005) so the
# "audio too low" branch fires deterministically.
LOW_GAIN = 0.05
# Room noise: white noise at a realistic low level, above the silence floor but
# not speech. It makes the model emit low-confidence frames, which is exactly
# what the speech-likeness gate must reject.
NOISE_RMS = 0.02
SEED = 20260923


def resample(audio, sr_in, sr_out):
    if sr_in == sr_out:
        return audio
    n_out = int(round(len(audio) * sr_out / sr_in))
    return np.interp(
        np.linspace(0.0, len(audio) - 1, n_out, dtype=np.float64),
        np.arange(len(audio), dtype=np.float64),
        audio,
    ).astype(np.float32)


def synth_good(path):
    pipeline = KPipeline(lang_code="a")
    chunks = [a for _, _, a in pipeline(TEXT, voice=VOICE)]
    audio = np.concatenate(
        [c.detach().cpu().numpy() if hasattr(c, "detach") else np.asarray(c) for c in chunks]
    )
    audio = resample(audio, KOKORO_RATE, MODEL_RATE).astype(np.float32)
    sf.write(path, audio, MODEL_RATE, subtype="PCM_16")
    return audio


def write(path, audio):
    sf.write(path, audio, MODEL_RATE, subtype="PCM_16")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main():
    out = (
        sys.argv[1]
        if len(sys.argv) > 1
        else "androidApp/src/androidTest/assets/score-parity/fixtures"
    )
    os.makedirs(out, exist_ok=True)

    good = synth_good(os.path.join(out, "good.wav"))
    duration = len(good) / MODEL_RATE

    # Digital silence, same length as the good take.
    write(os.path.join(out, "silence.wav"), np.zeros_like(good))

    # Room-like white noise at a fixed seed so the fixture is reproducible.
    rng = np.random.default_rng(SEED)
    noise = rng.standard_normal(len(good)).astype(np.float32)
    noise *= NOISE_RMS / float(np.sqrt(np.mean(noise**2)))
    write(os.path.join(out, "noise.wav"), noise)

    # The good take, far too quiet to evaluate.
    write(os.path.join(out, "low.wav"), (good * LOW_GAIN).astype(np.float32))

    print(f"text      {TEXT}")
    print(f"duration  {duration:.2f}s")
    for name in ("good", "silence", "noise", "low"):
        path = os.path.join(out, f"{name}.wav")
        pcm, _ = sf.read(path, dtype="float32")
        rms = float(np.sqrt(np.mean(pcm**2))) if pcm.size else 0.0
        print(f"{name:8s} rms={rms:.5f} sha256={sha256(path)}")


if __name__ == "__main__":
    main()
