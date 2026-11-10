"""Synthesise the scoring-calibration clips with the project's Kokoro voices.

The pronunciation scorer is calibrated against real speech: a native English
take, which must score high, and an accented take, which must not be told
MASTERED. This renders both with Kokoro, the same TTS the content pipeline uses,
so the calibration is reproducible and matches the audio learners actually hear.

The accented take feeds English text to a Kokoro Spanish voice, which applies
Spanish phonology to the English words - the same way a Spanish speaker with a
strong accent pronounces them.

Usage:
    .venv/bin/python calibration_tts.py [out-dir]     (default: /tmp/glosso-calibration)

Then point the JVM calibration test at the two files:

    GLOSSO_WAV_NATIVE=<out>/native.wav GLOSSO_WAV_ACCENT=<out>/spanish-accent.wav
"""

import os
import sys

import numpy as np
import soundfile as sf
from kokoro import KPipeline

TEXT = "I think my brother is thirsty."
# af_heart: American English narrator. em_alex: Spanish voice reading English.
NATIVE = ("a", "af_heart")
ACCENTED = ("e", "em_alex")

KOKORO_RATE = 24000
# The acoustic model reads raw PCM as 16 kHz, so resample Kokoro's 24 kHz output.
MODEL_RATE = 16000


def resample(audio, sr_in, sr_out):
    if sr_in == sr_out:
        return audio
    n_out = int(round(len(audio) * sr_out / sr_in))
    return np.interp(
        np.linspace(0.0, len(audio) - 1, n_out, dtype=np.float64),
        np.arange(len(audio), dtype=np.float64),
        audio,
    ).astype(np.float32)


def synth(lang, voice, text, path):
    pipeline = KPipeline(lang_code=lang)
    chunks = [a for _, _, a in pipeline(text, voice=voice)]
    audio = np.concatenate(
        [c.detach().cpu().numpy() if hasattr(c, "detach") else np.asarray(c) for c in chunks]
    )
    audio = resample(audio, KOKORO_RATE, MODEL_RATE)
    sf.write(path, audio, MODEL_RATE, subtype="PCM_16")
    print(f"{path}: {len(audio) / MODEL_RATE:.2f}s  voice={voice}")


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/glosso-calibration"
    os.makedirs(out, exist_ok=True)
    synth(*NATIVE, TEXT, os.path.join(out, "native.wav"))
    synth(*ACCENTED, TEXT, os.path.join(out, "spanish-accent.wav"))


if __name__ == "__main__":
    main()
