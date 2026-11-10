"""Regenerate the cloud-vs-on-device score-parity golden from the real server.

This is the *explicit* step that turns a server run into the committed baseline
the instrumented test compares against. It is deliberately not automatic: a new
golden is a deliberate act, reviewed together with the model/vocab/scoring change
that motivated it.

It talks to a running `:server` instance over HTTP (the real `/v1/score` route,
not a reimplementation), for every fixture, and records:

  * the SHA-256 of each fixture, and of the exact model + vocab the server used
    (the same shared assets the app downloads onto the device);
  * the server's full result for the evaluable ("good") take;
  * the server's HTTP status for the three non-evaluable takes.

Usage:

    # 1. start the server against the same assets the app uses
    GLOSSO_ASSETS_DIR=$PWD/data/assets PORT=8099 ./gradlew :server:run

    # 2. regenerate
    python3 tools/score-parity/regenerate-golden.py \
        --server http://localhost:8099 \
        --assets data/assets \
        --fixtures androidApp/src/androidTest/assets/score-parity/fixtures \
        --out androidApp/src/androidTest/assets/score-parity/golden.json

The tolerance is passed in and recorded verbatim; it is measured, not guessed.
See androidApp/src/androidTest/assets/score-parity/README.md.
"""

import argparse
import base64
import hashlib
import json
import os
import urllib.error
import urllib.request

TEXT = "I think my brother is thirsty."
EXPECTED_IPA = "ˈaɪ θˈɪŋk mˈaɪ bɹˈʌðɚ ˈɪz θˈɝsti"
LANGUAGE = "en_GB"
UI_LANGUAGE = "en"

# The four cases the design directives call for: one real take that must score,
# and silence / noise / too-quiet that must come back "not evaluable" instead of
# a fabricated 0/100 (directives 5 and 30).
CASES = [
    ("good.wav", True),
    ("silence.wav", False),
    ("noise.wav", False),
    ("low.wav", False),
]

MODEL_FILE = "wav2vec2_phoneme_int8_dyn_pertensor.onnx"
VOCAB_FILE = "phoneme_vocab.json"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def post_score(server, audio_b64):
    body = json.dumps(
        {
            "text": TEXT,
            "expectedIpa": EXPECTED_IPA,
            "audioBase64": audio_b64,
            "language": LANGUAGE,
            "uiLanguage": UI_LANGUAGE,
        }
    ).encode()
    req = urllib.request.Request(
        f"{server}/v1/score",
        data=body,
        # A browser-like UA: the public deployment sits behind Cloudflare, which
        # answers 403 to the default Python-urllib user agent.
        headers={"Content-Type": "application/json", "User-Agent": "glosso-score-parity/1.0"},
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://localhost:8099")
    ap.add_argument("--assets", default="data/assets")
    ap.add_argument("--fixtures", default="androidApp/src/androidTest/assets/score-parity/fixtures")
    ap.add_argument("--out", default="androidApp/src/androidTest/assets/score-parity/golden.json")
    ap.add_argument("--tolerance-score", type=int, default=0)
    ap.add_argument(
        "--tolerance-note",
        default="Set from the measured on-device vs cloud delta.",
    )
    args = ap.parse_args()

    cases = []
    for name, evaluable in CASES:
        path = os.path.join(args.fixtures, name)
        with open(path, "rb") as f:
            audio = f.read()
        status, payload = post_score(args.server, base64.b64encode(audio).decode())
        case = {
            "fixture": name,
            "sha256": sha256(path),
            "evaluable": evaluable,
        }
        if evaluable:
            if status != 200:
                raise SystemExit(f"{name}: expected HTTP 200, got {status}: {payload}")
            case["server"] = {
                "httpStatus": status,
                "score": payload["score"],
                "level": payload["level"],
                "isMastery": payload["isMastery"],
                "accuracy": payload["accuracy"],
                "completeness": payload["completeness"],
                "transcription": payload["transcription"],
            }
        else:
            # "Not evaluable" is a learner state, not an error. The server answers
            # 200 with `evaluated: false` (an older deployment answered 422).
            # Record exactly what it returned either way.
            if status >= 400:
                case["server"] = {"httpStatus": status, "error": payload.get("error")}
            elif payload.get("evaluated") is False:
                case["server"] = {"httpStatus": status, "evaluated": False}
            else:
                raise SystemExit(
                    f"{name}: expected a not-evaluable result, got HTTP {status} "
                    f"evaluated={payload.get('evaluated')}",
                )
        cases.append(case)
        print(f"{name}: {status} {case.get('server')}")

    golden = {
        "formatVersion": 1,
        "generatedBy": "tools/score-parity/regenerate-golden.py against the real server /v1/score",
        "provenance": {
            "scoringPath": "Ktor /v1/score -> Wav2Vec2PhonemeRecognizer -> PhoneticComparator.calculateScoringResult -> FeedbackAssembler",
            "modelFile": MODEL_FILE,
            "modelSha256": sha256(os.path.join(args.assets, MODEL_FILE)),
            "vocabFile": VOCAB_FILE,
            "vocabSha256": sha256(os.path.join(args.assets, VOCAB_FILE)),
        },
        "request": {
            "text": TEXT,
            "expectedIpa": EXPECTED_IPA,
            "language": LANGUAGE,
            "uiLanguage": UI_LANGUAGE,
        },
        "tolerance": {
            "scoreAbs": args.tolerance_score,
            "note": args.tolerance_note,
        },
        "cases": cases,
    }

    with open(args.out, "w") as f:
        json.dump(golden, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
