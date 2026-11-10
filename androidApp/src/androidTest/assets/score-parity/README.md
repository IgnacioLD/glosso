# Score parity: cloud vs on-device

The product promises that the score is the same in the cloud and on the device
because both run the same Kotlin engine. This directory turns that promise from a
design claim into a measured, repeatable gate.

`ScoreParityTest` (an instrumented Android test) scores four committed WAVs
through the real offline path, `OnDeviceScorer`, and compares each result with a
golden captured from the real server `/v1/score` route. It fails if the model,
the vocab or the scoring logic drift away from the golden without a deliberate
regeneration.

## Where this lives

This directory is the `androidTest` assets root
(`androidApp/src/androidTest/assets/score-parity`), so Gradle packages it into the
test APK only. It never enters the release app, and the test reads it with
`testAssets.open("score-parity/...")`. The files stay committed because the good
take comes from TTS and is committed rather than rendered at test time; the
golden is the scoring oracle, so it is deliberately kept out of any release
artifact. Regeneration is below.

## Contents

- `fixtures/good.wav` - a clean take of "I think my brother is thirsty.", rendered
  with the project's Kokoro voice (`tools/score-parity/make-fixtures.py`).
- `fixtures/silence.wav` - digital silence (directives 5 and 30: no fabricated
  0/100 for an attempt that was never made).
- `fixtures/noise.wav` - room-like white noise at a fixed seed.
- `fixtures/low.wav` - the good take attenuated below `SpeechGate.MIN_RMS` (audio
  too low).
- `golden.json` - the server results, the model/vocab SHA-256, the request, and
  the tolerance. Generated, not hand-written: see "Regenerating the golden".

## The four cases

| Fixture       | Cloud `/v1/score`                    | On device (`OnDeviceScorer`)      |
| ------------- | ------------------------------------ | --------------------------------- |
| `good.wav`    | 200, 100/100, PERFECT                | 100/100, PERFECT, delta 0         |
| `silence.wav` | 200, `evaluated=false`               | not evaluable (`evaluated=false`) |
| `noise.wav`   | 200, `evaluated=false`               | not evaluable (`evaluated=false`) |
| `low.wav`     | 200, `evaluated=false`               | not evaluable (`evaluated=false`) |

Cloud and device agree on all four: same score and verdict for the good take, and
the same "not evaluable" outcome for the three unscoreable ones. "Not evaluable"
is a learner state, not an error: the server answers 200 with
`evaluated: false` (an older deployment answered 422).

## Measured tolerance

**0 points.** The on-device score for `good.wav` is 100/100, exactly the cloud
score: delta 0, the same level (`PERFECT`), the same accuracy (100) and
completeness (100). Both sides run the same selective int8 model, the same
`PhoneticComparator.calculateScoringResult`, the model and vocab are
hash-identical, and greedy CTC decode over the same finite-precision logits is
deterministic, so 0 is the honest bound. If a future Android ONNX Runtime update
introduces floating-point divergence, that is the one number to revisit -
deliberately, not silently.

## Running the gate

```sh
# one-off: fetch the model + vocab (not committed, ~342 MB)
tools/fetch-assets.sh

# run the gate on a device with ~1 GB of free RAM
tools/score-parity/run-parity-test.sh --serial <device>
```

The harness stages the model on the device (`/data/local/tmp/glosso-parity`) and
the test copies it into the app's private scoring dir, so the gate needs no
network. It exits non-zero on any drift.

**Device RAM matters.** Loading the ~342 MB model needs roughly 1 GB of free RAM.
The default 2.5 GB emulator images are too small and get OOM-killed by
`lowmemorykiller` while loading it. Launch the emulator with more memory (4 GB is
enough and is what the gate was measured on):

```sh
$ANDROID_HOME/emulator/emulator -avd <avd> -port 5572 -memory 4096 \
    -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot
```

## Regenerating the golden

This is an explicit, reviewable step, never automatic. It should accompany a
deliberate model, vocab or scoring change - and the regenerated `golden.json` is
part of the review.

```sh
# 1. start the real server against the same assets the app uses
GLOSSO_ASSETS_DIR=$PWD/data/assets PORT=8099 ./gradlew :server:run

# 2. capture the golden from /v1/score
python3 tools/score-parity/regenerate-golden.py \
    --server http://localhost:8099 \
    --assets data/assets \
    --fixtures androidApp/src/androidTest/assets/score-parity/fixtures \
    --out androidApp/src/androidTest/assets/score-parity/golden.json \
    --tolerance-score 0 \
    --tolerance-note "<the freshly measured delta and why>"
```

To re-render the fixtures themselves (only if the sentence or voice changes):

```sh
tools/content/.venv/bin/python tools/score-parity/make-fixtures.py \
    androidApp/src/androidTest/assets/score-parity/fixtures
```

## What the gate catches

- **Model or vocab drift.** The test hashes the model and vocab on the device and
  compares them with `golden.provenance`. Any change fails until the golden is
  regenerated.
- **Scoring-logic drift.** Any change that moves the score or verdict for these
  takes fails against the golden. (A logic change that happens to leave these
  four results untouched is not caught; that is the price of a small, honest
  golden.)
- **Uncertainty regressions.** If silence, noise or too-quiet audio ever stopped
  being rejected - the exact "never punish the learner for a recognition failure"
  failure mode of directives 5 and 30 - the corresponding case fails.

## Note: large-download OOM found while building this

Writing this gate surfaced a real bug in the app's model download. `GlossoApi`
installs Ktor's `HttpCache` plugin, and for a cacheable response the plugin reads
the **entire body into memory** before storing it. The ~342 MB model body OOMed
the app (`java.lang.OutOfMemoryError` in `HttpCache.cacheResponse`) on a real
device. `downloadTo` streams straight to a file, so the fix is to send
`Cache-Control: no-store` for that request, which makes the plugin skip storing.
See `GlossoApi.downloadTo`.
