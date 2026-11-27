# /// script
# dependencies = ["numpy"]
# ///
"""Synthesize Glosso's feedback cues (all original, no samples).

One soft-mallet timbre and one key (C major) for every cue, so they read as a
family. Deterministic: the same script always writes the same bytes.

    uv run tools/sounds/make_feedback_sounds.py

Writes 16-bit mono 22.05 kHz WAVs to shared/src/androidMain/res/raw. Android
plays them from res/raw, iOS bundles them (iosApp/project.yml) and the web
build serves them next to the page (shared/build.gradle.kts). The file names
must match FeedbackCue in shared/src/commonMain/kotlin/com/glosso/app/FeedbackSounds.kt.
"""

import os
import wave

import numpy as np

RATE = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "shared", "src", "androidMain", "res", "raw")


def hz(note: str) -> float:
    """'C6' or 'F#5' to Hz (A4 = 440)."""
    names = {"C": -9, "D": -7, "E": -5, "F": -4, "G": -2, "A": 0, "B": 2}
    semis = names[note[0]] + (1 if note[1] == "#" else 0)
    octave = int(note[-1])
    return 440.0 * 2 ** ((semis + 12 * (octave - 4)) / 12)


def mallet(freq: float, ring: float, length: float) -> np.ndarray:
    """A soft struck bar: fundamental plus two quickly fading overtones."""
    t = np.arange(int(length * RATE)) / RATE
    out = np.zeros_like(t)
    for ratio, amp, decay in ((1.0, 1.0, 1.0), (2.0, 0.22, 0.45), (3.98, 0.08, 0.2)):
        if freq * ratio < RATE * 0.45:
            out += amp * np.sin(2 * np.pi * freq * ratio * t) * np.exp(-t / (ring * decay))
    attack = np.minimum(t / 0.004, 1.0)
    # Fade the cut tail to zero, or the note's end clicks.
    release = np.minimum((t[-1] - t) / 0.04, 1.0)
    return out * attack * release


def thump(length: float) -> np.ndarray:
    """A low, soft 'stamp': a sine falling from 180 to 90 Hz."""
    t = np.arange(int(length * RATE)) / RATE
    freq = 90 + 90 * np.exp(-t / 0.03)
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    envelope = np.exp(-t / 0.06) * np.minimum(t / 0.003, 1.0) * np.minimum((t[-1] - t) / 0.04, 1.0)
    return np.sin(phase) * envelope


def breath(length: float, seed: int) -> np.ndarray:
    """A gentle rising air swell (low-passed noise), the ember under the streak cue."""
    rng = np.random.default_rng(seed)
    n = int(length * RATE)
    noise = rng.standard_normal(n)
    kernel = np.hanning(24)
    noise = np.convolve(noise, kernel / kernel.sum(), mode="same")
    t = np.arange(n) / RATE
    swell = np.sin(np.pi * t / length) ** 2
    return noise * swell


def mix(length: float, events) -> np.ndarray:
    """events: (start_s, signal, gain). Ends with a short fade so nothing clicks."""
    out = np.zeros(int(length * RATE))
    for start, sig, gain in events:
        i = int(start * RATE)
        sig = sig[: len(out) - i]
        out[i : i + len(sig)] += gain * sig
    fade = int(0.03 * RATE)
    out[-fade:] *= np.linspace(1, 0, fade)
    return out


def notes(start: float, names, step: float, ring: float, gain: float, length: float):
    return [(start + k * step, mallet(hz(n), ring, length), gain) for k, n in enumerate(names)]


def correct(root: str, third: str, fifth: str) -> np.ndarray:
    # A quick strummed major triad, top note brightest: one bright "ding".
    return mix(0.5, notes(0, [root, third, fifth], 0.03, 0.16, 0.6, 0.5))


CUES = {
    # (signal, peak dBFS)
    "correct": (correct("C6", "E6", "G6"), -8),
    # Combo steps: the same ding a whole tone higher each time.
    "correct_2": (correct("D6", "F#6", "A6"), -8),
    "correct_3": (correct("E6", "G#6", "B6"), -8),
    # Soft and low: two muted notes falling a minor third, nothing harsh.
    "wrong": (mix(0.42, notes(0, ["A4", "F#4"], 0.12, 0.09, 1.0, 0.3)), -11),
    # Lesson or set done: a rising arpeggio landing on a held, ringing chord.
    "complete": (
        mix(
            1.5,
            notes(0, ["C5", "E5", "G5"], 0.09, 0.12, 0.7, 0.5)
            + notes(0.27, ["C6", "E6", "G6", "C7"], 0.012, 0.45, 0.45, 1.23),
        ),
        -7,
    ),
    # A perfect take: a light sparkle running up the pentatonic.
    "perfect": (mix(0.8, notes(0, ["G6", "A6", "C7", "D7", "E7"], 0.05, 0.12, 0.55, 0.6)), -9),
    # A seal stamps itself: a soft thump under a bright chord.
    "achievement": (
        mix(
            1.0,
            [(0, thump(0.3), 0.9)]
            + notes(0.02, ["G5", "B5", "D6"], 0.015, 0.08, 0.5, 0.2)
            + notes(0.16, ["C6", "E6", "G6", "C7"], 0.015, 0.3, 0.45, 0.84),
        ),
        -7,
    ),
    # The daily streak grows: warm notes rising over a soft breath.
    "streak": (
        mix(
            0.9,
            [(0, breath(0.6, seed=7), 0.25)]
            + notes(0.08, ["F5", "A5", "C6"], 0.1, 0.18, 0.6, 0.6),
        ),
        -8,
    ),
}


def write(name: str, signal: np.ndarray, peak_db: float) -> None:
    scaled = signal / np.abs(signal).max() * 10 ** (peak_db / 20)
    pcm = np.round(scaled * 32767).astype("<i2")
    # Self-check: quiet enough, short enough, and starts and ends in silence.
    assert np.abs(pcm).max() < 32767 * 0.5, name
    assert len(pcm) / RATE <= (1.5 if name == "complete" else 1.0), name
    assert pcm[0] == 0 and abs(int(pcm[-1])) <= 1, name
    with wave.open(os.path.join(OUT, f"feedback_{name}.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())


if __name__ == "__main__":
    for cue, (signal, peak) in CUES.items():
        write(cue, signal, peak)
        print(f"feedback_{cue}.wav  {len(signal) / RATE:.2f} s  peak {peak} dBFS")
