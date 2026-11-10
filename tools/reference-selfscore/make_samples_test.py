#!/usr/bin/env python3
"""Contract tests for the sample builder. Run: python3 make_samples_test.py

- A selection with no clips (a voice absent from content.db, or every clip
  missing on disk) is an error and writes no manifest. An empty manifest turns
  into `ACCENT98 ... clips=0` and a bench PASS indistinguishable from real
  evidence, and that metric is what turns the multi-accent flag on.
- `pick_audio` prefers the exact voice key and only falls back to the on-disk
  file naming when the key is part of the clip's file name.
"""

import json
import os
import sqlite3
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "make_samples.py")
sys.path.insert(0, HERE)

import make_samples  # noqa: E402


def make_db(path, rows):
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE content (id TEXT, kind TEXT, text TEXT, data TEXT)")
    conn.executemany("INSERT INTO content VALUES (?, ?, ?, ?)", rows)
    conn.commit()
    conn.close()


def build(db, audio_root, out, extra=()):
    return subprocess.run(
        [sys.executable, SCRIPT, "--db", db, "--audio-root", audio_root, "--out", out, *extra],
        capture_output=True,
        text=True,
    )


def test_empty_voice_fails_and_writes_no_manifest():
    with tempfile.TemporaryDirectory() as tmp:
        db = os.path.join(tmp, "content.db")
        make_db(db, [])
        out = os.path.join(tmp, "out")
        result = build(db, os.path.join(tmp, "audio"), out, ["--voice", "en-IE-male"])
        assert result.returncode != 0, "an empty set must fail, not write a manifest"
        assert not os.path.exists(os.path.join(out, "manifest.json")), "no manifest on failure"
        assert "0 clips" in (result.stderr + result.stdout), result.stderr


def test_voice_with_every_clip_missing_fails():
    with tempfile.TemporaryDirectory() as tmp:
        db = os.path.join(tmp, "content.db")
        data = json.dumps({"ipa": "k\u00e6t", "audio": {"en-IE-male": "w1_en-IE-male.ogg"}})
        make_db(db, [("w1", "WORD", "cat", data)])
        out = os.path.join(tmp, "out")
        result = build(db, os.path.join(tmp, "audio"), out, ["--voice", "en-IE-male"])
        assert result.returncode != 0, "every clip missing is an empty manifest: fail"
        assert not os.path.exists(os.path.join(out, "manifest.json"))


def test_pick_audio():
    exact = {"en-US-female": "clip_en-US-female.ogg"}
    assert make_samples.pick_audio(exact, "en-US-female") == ("en-US-female", "clip_en-US-female.ogg")

    # A voice on disk but absent from the map follows the file naming: the
    # requested key swaps into the path of the key whose name the file carries.
    # This is how the unlisted en-US clips are found in an en-GB content.db.
    assert make_samples.pick_audio(exact, "en-US-South-female") == (
        "en-US-South-female",
        "clip_en-US-South-female.ogg",
    )

    # The map key, not the requested voice, is what gets replaced: the swap is a
    # single substring, it never appends a second gender.
    swapped = make_samples.pick_audio({"en-GB-female": "clip.en-GB-female.ogg"}, "en-US-female")
    assert swapped == ("en-US-female", "clip.en-US-female.ogg"), swapped

    # No key name in the path, no clip.
    assert make_samples.pick_audio({"en-US-female": "clip_other.ogg"}, "en-IE-male") == (None, None)
    assert make_samples.pick_audio({}, "en-IE-male") == (None, None)
    # A composite key (contains ':') is never rewritten from a file name.
    assert make_samples.pick_audio({"a:b": "clip_a.ogg"}, "en-IE-male") == (None, None)


def main():
    tests = [(name, fn) for name, fn in sorted(globals().items()) if name.startswith("test_") and callable(fn)]
    for name, test in tests:
        test()
        print(f"ok {name}")
    print(f"{len(tests)} checks passed")


if __name__ == "__main__":
    main()
