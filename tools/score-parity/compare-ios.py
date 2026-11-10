"""Compare the JVM and iOS recognizer parity records.

Reads the JSON lines `RecognizerParityHarness` writes on each platform (one per
clip) and the raw logits next to them, and reports:

  * final score, verdict, evaluated flag and transcript per clip (must match);
  * token confidences and forced-alignment values (raw float bits);
  * the full feedback the server would return (numbers compared as values);
  * the max absolute difference of the raw logits;
  * the four score-parity fixtures against the real server golden.

Exit code 1 when any score, verdict, evaluated flag or transcript differs, or a
fixture disagrees with the server golden. Float-only differences are reported,
not failed: the gate is the score the learner sees.

    python3 tools/score-parity/compare-ios.py --jvm build/ios-parity/jvm.jsonl \
        --ios build/ios-parity/ios.jsonl \
        --golden androidApp/src/androidTest/assets/score-parity/golden.json
"""

import argparse
import json
import os
import struct
import sys
from array import array


def load(path):
    with open(path, encoding="utf-8") as f:
        return {r["id"]: r for r in (json.loads(line) for line in f if line.strip())}


def bits_to_float(bits):
    return struct.unpack("<f", struct.pack("<i", bits))[0]


def logits(path):
    if not os.path.exists(path):
        return None
    a = array("f")
    with open(path, "rb") as f:
        a.frombytes(f.read())
    return a


def diff_values(a, b, path, out):
    """Collects (path, |a-b|) for every leaf that differs; structure mismatches are inf."""
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a or k not in b:
                out.append((f"{path}.{k}", float("inf")))
            else:
                diff_values(a[k], b[k], f"{path}.{k}", out)
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            out.append((f"{path}[len]", float("inf")))
        for i, (x, y) in enumerate(zip(a, b)):
            diff_values(x, y, f"{path}[{i}]", out)
    elif isinstance(a, bool) or isinstance(b, bool) or isinstance(a, str) or isinstance(b, str) or a is None or b is None:
        if a != b:
            out.append((path, float("inf")))
    elif isinstance(a, (int, float)) and isinstance(b, (int, float)):
        if a != b:
            out.append((path, abs(a - b)))
    elif a != b:
        out.append((path, float("inf")))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jvm", required=True)
    ap.add_argument("--ios", required=True)
    ap.add_argument("--golden")
    args = ap.parse_args()

    jvm, ios = load(args.jvm), load(args.ios)
    hard = []
    ids = sorted(set(jvm) | set(ios))
    missing = [i for i in ids if i not in jvm or i not in ios]
    hard += [f"{i}: missing on one side" for i in missing]
    both = [i for i in ids if i in jvm and i in ios]

    same_score = 0
    max_score_diff = 0
    conf_diff_clips = 0
    max_conf = 0.0
    timing_diff_clips = 0
    max_timing = 0.0
    feedback_diff_clips = 0
    max_feedback = 0.0
    feedback_examples = []
    logit_identical = 0
    logit_compared = 0
    max_logit = 0.0
    shape_mismatch = []

    for i in both:
        a, b = jvm[i], ios[i]
        for key in ("evaluated", "ipa", "score"):
            if a.get(key) != b.get(key):
                hard.append(f"{i}: {key} jvm={a.get(key)!r} ios={b.get(key)!r}")
        if a.get("score") == b.get("score") and a.get("evaluated") == b.get("evaluated"):
            same_score += 1
        if isinstance(a.get("score"), int) and isinstance(b.get("score"), int):
            max_score_diff = max(max_score_diff, abs(a["score"] - b["score"]))
        fa, fb = a.get("feedback", {}), b.get("feedback", {})
        if fa.get("level") != fb.get("level"):
            hard.append(f"{i}: level jvm={fa.get('level')} ios={fb.get('level')}")

        pa, pb = a.get("phones", []), b.get("phones", [])
        if [p[0] for p in pa] != [p[0] for p in pb]:
            hard.append(f"{i}: token sequence differs")
        else:
            d = [abs(bits_to_float(x[1]) - bits_to_float(y[1])) for x, y in zip(pa, pb)]
            if any(x[1] != y[1] for x, y in zip(pa, pb)):
                conf_diff_clips += 1
            max_conf = max([max_conf] + d)

        ta, tb = a.get("timings", []), b.get("timings", [])
        if len(ta) != len(tb) or any(x[:3] != y[:3] or x[6] != y[6] for x, y in zip(ta, tb)):
            timing_diff_clips += 1
            max_timing = float("inf")
        elif ta != tb:
            timing_diff_clips += 1
            for x, y in zip(ta, tb):
                for k in (3, 4, 5):
                    if x[k] is not None and y[k] is not None:
                        max_timing = max(max_timing, abs(bits_to_float(x[k]) - bits_to_float(y[k])))

        out = []
        diff_values(fa, fb, "feedback", out)
        if out:
            feedback_diff_clips += 1
            max_feedback = max([max_feedback] + [d for _, d in out])
            if len(feedback_examples) < 5:
                feedback_examples.append((i, out[:3]))

        if a.get("time_steps") != b.get("time_steps") or a.get("vocab_size") != b.get("vocab_size"):
            shape_mismatch.append(i)
        la = logits(args.jvm + ".logits/" + i + ".f32")
        lb = logits(args.ios + ".logits/" + i + ".f32")
        if la is not None and lb is not None and len(la) == len(lb):
            logit_compared += 1
            m = max((abs(x - y) for x, y in zip(la, lb)), default=0.0)
            max_logit = max(max_logit, m)
            if m == 0.0:
                logit_identical += 1
    hard += [f"{i}: logits shape differs" for i in shape_mismatch]

    golden_lines = []
    if args.golden:
        golden = json.load(open(args.golden, encoding="utf-8"))
        for case in golden["cases"]:
            cid = "score-parity." + case["fixture"].removesuffix(".wav")
            r = ios.get(cid)
            if r is None:
                hard.append(f"{cid}: not run on iOS")
                continue
            server = case["server"]
            if not case["evaluable"]:
                ok = r.get("evaluated") is False
            else:
                fb = r.get("feedback", {})
                ok = (
                    r.get("score") == server["score"]
                    and fb.get("level") == server["level"]
                    and fb.get("accuracy") == server["accuracy"]
                    and fb.get("completeness") == server["completeness"]
                    and r.get("ipa") == server["transcription"]
                )
            golden_lines.append(f"  {case['fixture']:12s} server={json.dumps(server, ensure_ascii=False)} ios_score={r.get('score')} {'OK' if ok else 'DIFFERS'}")
            if not ok:
                hard.append(f"{cid}: iOS disagrees with the server golden")

    n = len(both)
    evaluated = sum(1 for i in both if jvm[i].get("evaluated"))
    print(f"clips compared         {n} ({evaluated} evaluated, {n - evaluated} not evaluable)")
    print(f"identical final scores {same_score}/{n}  (max |score diff| = {max_score_diff})")
    print(f"token confidences      {n - conf_diff_clips}/{n} clips bit-identical, max |diff| = {max_conf:.3g}")
    print(f"alignment/prosody      {n - timing_diff_clips}/{n} clips bit-identical, max |diff| = {max_timing:.3g}")
    print(f"full feedback JSON     {n - feedback_diff_clips}/{n} clips identical, max numeric |diff| = {max_feedback:.3g}")
    for cid, ex in feedback_examples:
        print(f"    {cid}: {ex}")
    print(f"raw logits             {logit_identical}/{logit_compared} clips bit-identical, max |diff| = {max_logit:.3g}")
    if golden_lines:
        print("score-parity fixtures vs the real server golden:")
        print("\n".join(golden_lines))
    for side, path in (("jvm", args.jvm), ("ios", args.ios)):
        s = path + ".summary.json"
        if os.path.exists(s):
            print(f"{side} timing/memory     {open(s).read().strip()}")
    if hard:
        print("\nPARITY FAIL")
        print("\n".join("  " + h for h in hard))
        sys.exit(1)
    print("\nPARITY PASS")


if __name__ == "__main__":
    main()
