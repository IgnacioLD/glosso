"""Regenerate the Kotlin parity fixtures from the Python cheap-scorers reference.

This produces `signals/*.wav` and `golden.json` consumed by
`engine/src/jvmTest/kotlin/com/glosso/engine/phonetics/cheap/CheapParityTest.kt`.

The reference itself lives in `benchmark/cheap-scorers` (PR #43, branch
`research/cheap-scorers`) and is NOT imported by the engine; only its outputs are
committed. To run this:

    cd benchmark/cheap-scorers
    .venv/bin/python ../../engine/src/jvmTest/resources/cheap/gen_fixtures.py \
        --out ../../engine/src/jvmTest/resources/cheap

The accent anchors below are the fixed production constants (see
`FluencyScorer.ENGLISH`); they were derived from the report's per-feature
native/non-native means and per-feature AUC under an equal-variance Gaussian
assumption, so parity between Python and Kotlin is exact and the report's
numbers keep describing production.
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys

import numpy as np

# The reference `cheap` package lives in the working directory (benchmark/cheap-scorers).
sys.path.insert(0, os.getcwd())
from cheap.features import extract  # noqa: E402

SR = 16000

NORM = {
    "speech_rate_syl_per_s": (2.62190, 0.95527, -1),
    "pause_ratio": (0.05955, 0.07272, 1),
    "longest_pause_seconds": (0.20565, 0.33455, 1),
    "f0_std_semitones": (4.30015, 1.61123, -1),
    "f0_range_semitones": (9.90005, 5.17643, -1),
    "npvi": (65.64355, 32.58372, -1),
}

PORTED = [
    "f1", "f2", "f3", "f2_minus_f1", "f1_std", "f2_std",
    "fric_centroid", "fric_spread", "fric_skew", "fric_kurtosis", "fric_flatness",
    "fric_high_ratio", "fric_zcr", "fric_duration",
    "f0_mean", "f0_median", "f0_std_semitones", "f0_range_semitones",
    "f0_slope_semitones_per_s", "f0_final_delta_semitones", "voiced_ratio",
    "speech_seconds", "n_syllables", "speech_rate_syl_per_s", "articulation_rate_syl_per_s",
    "mean_nucleus_seconds", "std_nucleus_seconds", "npvi",
    "pause_count", "pause_ratio", "longest_pause_seconds", "mean_pause_seconds",
    "focus_class", "nucleus_seconds", "onset_seconds", "coda_seconds",
]


def write_wav(path: str, audio: np.ndarray) -> None:
    pcm = np.clip(audio, -1.0, 1.0)
    pcm16 = np.round(pcm * 32767.0).astype("<i2")
    data = pcm16.tobytes()
    header = b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVE"
    header += b"fmt " + struct.pack("<IHHIIHH", 16, 1, 1, SR, SR * 2, 2, 16)
    header += b"data" + struct.pack("<I", len(data))
    with open(path, "wb") as fh:
        fh.write(header + data)


def envelope(freqs, formants):
    freqs = np.asarray(freqs, dtype=np.float64)
    env = np.full_like(freqs, 0.02)
    for f, bw, gain in formants:
        env += gain * np.exp(-0.5 * ((freqs - f) / bw) ** 2)
    return env


def harmonic_voice(duration, f0_start, f0_end, formants, seed=0):
    n = int(round(duration * SR))
    t = np.arange(n) / SR
    f0 = np.linspace(f0_start, f0_end, n)
    phase = 2 * np.pi * np.cumsum(f0) / SR
    out = np.zeros(n)
    k = 1
    while k * f0_start < SR / 2 - 200:
        amp = envelope(np.array([k * f0_start]), formants)[0] / k
        out += amp * np.sin(k * phase)
        k += 1
    out *= np.hanning(n)
    return out / (np.max(np.abs(out)) + 1e-9) * 0.6


def noise(duration, kind, seed=0):
    n = int(round(duration * SR))
    rng = np.random.default_rng(seed)
    x = rng.standard_normal(n)
    if kind == "high":
        x = np.diff(x, prepend=x[0])
    elif kind == "mid":
        x = np.convolve(x, np.ones(4) / 4.0, mode="same")
    x *= np.hanning(n)
    return x / (np.max(np.abs(x)) + 1e-9) * 0.5


def sentence():
    parts = []
    for i, (f0a, f0b, dur, gap) in enumerate([
        (185, 170, 0.20, 0.10),
        (170, 155, 0.18, 0.14),
        (155, 120, 0.26, 0.0),
    ]):
        parts.append(harmonic_voice(dur, f0a, f0b, [(520, 130, 1.0), (1850, 180, 0.5), (2650, 200, 0.3)], seed=i))
        if gap > 0:
            parts.append(np.zeros(int(round(gap * SR))))
    out = np.concatenate(parts)
    return out / (np.max(np.abs(out)) + 1e-9) * 0.7


def build_signals():
    return [
        ("vowel_ih", harmonic_voice(0.45, 215, 205, [(560, 130, 1.0), (2265, 180, 0.6), (3280, 220, 0.35)]), "ɪ"),
        ("vowel_iy", harmonic_voice(0.45, 215, 205, [(340, 120, 1.0), (2915, 180, 0.7), (4200, 220, 0.4)]), "i"),
        ("fric_s", noise(0.30, "high", seed=7), "s"),
        ("fric_theta", noise(0.30, "mid", seed=11), "θ"),
        ("sentence", sentence(), None),
        ("silence", np.zeros(int(round(0.5 * SR))), None),
        ("short", np.sin(2 * np.pi * 440 * np.arange(100) / SR) * 0.5, None),
    ]


def clean(v):
    if v is None or isinstance(v, str) or isinstance(v, bool):
        return v
    v = float(v)
    return None if not np.isfinite(v) else v


def accent_score(feat):
    parts = []
    for key, (mean, std, sign) in NORM.items():
        v = feat.get(key)
        if v is None or not np.isfinite(v):
            continue
        parts.append(sign * (float(v) - mean) / std)
    return float(np.mean(parts)) if parts else None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.dirname(os.path.abspath(__file__)))
    args = ap.parse_args()
    out_dir = os.path.abspath(args.out)
    os.makedirs(out_dir, exist_ok=True)

    records = []
    for sid, audio, focus in build_signals():
        path = os.path.join(out_dir, sid + ".wav")
        write_wav(path, audio)
        with open(path, "rb") as fh:
            raw = fh.read()
        pcm = np.frombuffer(raw[44:], dtype="<i2").astype(np.float64) / 32768.0
        feat = extract(pcm, SR, [], focus)
        records.append({
            "id": sid,
            "file": sid + ".wav",
            "sampleRate": SR,
            "focus": focus,
            "features": {k: clean(feat.get(k)) for k in PORTED},
            "accentScore": accent_score(feat),
        })
        print(f"{sid:14s} accent={accent_score(feat):.4f}")

    golden = {
        "provenance": {
            "source": "benchmark/cheap-scorers (PR #43, branch research/cheap-scorers)",
            "generator": "engine/src/jvmTest/resources/cheap/gen_fixtures.py",
            "numpy": np.__version__,
        },
        "norm": {k: {"mean": v[0], "std": v[1], "sign": v[2]} for k, v in NORM.items()},
        "portedFeatures": PORTED,
        "signals": records,
    }
    with open(os.path.join(out_dir, "golden.json"), "w", encoding="utf-8") as fh:
        json.dump(golden, fh, ensure_ascii=False, indent=2)
    print("wrote", os.path.join(out_dir, "golden.json"))


if __name__ == "__main__":
    main()
