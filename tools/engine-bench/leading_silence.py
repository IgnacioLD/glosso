#!/usr/bin/env python3
"""Leading silence of each bench WAV: 10 ms windows, speech starts at the first
window whose peak exceeds 2% of the clip peak.

    python3 tools/engine-bench/leading_silence.py build/engine-bench/native [max_ms]

Prints one line per clip under max_ms (default 30) and a summary.
"""
import glob
import os
import struct
import sys
import wave


def leading_ms(path):
    with wave.open(path) as w:
        rate = w.getframerate()
        samples = struct.unpack("<%dh" % (w.getnframes() * w.getnchannels()), w.readframes(w.getnframes()))
    window = rate // 100
    threshold = 0.02 * max(abs(v) for v in samples)
    for start in range(0, len(samples), window):
        if max(abs(v) for v in samples[start:start + window]) > threshold:
            return start * 1000 // rate
    return len(samples) * 1000 // rate


def main():
    folder = sys.argv[1]
    max_ms = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    clips = sorted(glob.glob(os.path.join(folder, "*.wav")))
    short = [(os.path.basename(c)[:-4], leading_ms(c)) for c in clips]
    short = [(name, ms) for name, ms in short if ms < max_ms]
    for name, ms in short:
        print(f"LEADING_SILENCE {name} {ms}ms")
    print(f"LEADING_SILENCE_SUMMARY clips={len(clips)} under_{max_ms}ms={len(short)}")


if __name__ == "__main__":
    main()
