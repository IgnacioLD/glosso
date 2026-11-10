# Engine benchmark (2026-09-24)

The rule for measuring the engine, and the gate that fails when it gets worse.
This exists because the audit (`docs/ENGINE-STATUS-2026-09-24.md`) found that no
default test ran audio -> ONNX -> score, the two AUC probes printed numbers
without asserting them, and the `acceptance_gate` compared a baseline with
itself. A fix to the engine without a real measurement is faith, not engineering.

## The one command

    tools/engine-bench/run.sh

It builds two fixed manifests from the `glosso-data` checkout (using the oracle
builder already in the repo, `tools/reference-selfscore/make_samples.py`) and runs
the checks with the threshold written in each test. Assets and audio are not in
git (the model is ~600 MB, the clips are content); the script says what is
missing and how to get it.

## The metric the owner mandates: natives reaching 98

> "Lo importante es que un nativo vaya bien, le de 98 como minimo."

Of the fixed, **non-curated** native reference set (`tools/engine-bench/native-ids.txt`,
45 clips across words, drills and dialogue turns), how many reach 98. The set is
deliberately not curated to pass: it keeps the clips the acoustic model
mis-hears, because the point is to bring the failure rate down, not to hide
behind a set that already passes. The gate is **>= 90%** (95% is the stretch).

    antes de #93 (16e5f37): 18/45 = 40%  (7 clips rechazados por la puerta de habla)
    hoy (main, 2026-09-24):  28/45 = 62%  (0 rechazados)
    meta:                    >= 90% (95% objetivo)

Same fixed set, same metric (a rejected clip does not "go well", so it counts as
a failure), measured with this bench on both commits. Today is 62%, so this gate
is **RED** until the natural-speech fix lands. This is the number the owner looks
at, and `engine-natural` reports before/after against it. The per-clip bar stays
98; the fix is the cause, not a lower bar.

The curated `golden-ids.txt` set (28 clips) is kept as a separate per-clip oracle:
every clip that ships as reference must reach 98 (post-#93 it does).

## The guard: separation AUC

IPA -> IPA on the committed arbiter probe (`engine/src/jvmTest/resources/probe_tts_ref.json`),
rows with `role` in `{native, nonnative}`: 810 clips (216 native / 594
non-native) of Common Voice test, re-scored with the engine. It is the AUC of the
score the app shows (folded edit distance). It is **not** the Common Voice
PyTorch quality head (~0,591, a different signal, not shown in the app) and
**not** the en-GB probe. Every run prints the set.

The walls are the **pre-#93 product target**. #93 lowered the separation, and
accepting that is a **pending product decision**, so by default the walls are
printed and **not enforced** (marked `BELOW_PENDING_PRODUCT_DECISION`). They are
**not** lowered to make the build pass; `GLOSSO_AUC_STRICT=1` enforces them once
the decision lands.

| Metric | Pre-#93 | Post-#93 (`main` today) | Wall |
| --- | ---: | ---: | ---: |
| `single` | 0,4700 | 0,4617 | 0,465 |
| `deployed` | 0,4759 | 0,4673 | 0,470 |
| `current` (shown score) | 0,4762 | 0,4671 | 0,470 |
| en-GB accepted-both | 0,4446 | 0,4233 | 0,440 |

Product target is > 0,65; it is reported, not asserted.

## Does it run by default?

Partly, on purpose.

- **Yes, in `./gradlew :engine:jvmTest`** (hence `./gradlew build`): the
  separation-AUC `single` guard and the en-GB guard need no model and no audio,
  so they run on every build and a nested regression in `PhoneticComparator` is
  visible there.
- **No, the audio bench** needs the ~600 MB model and the converted clips, so it
  is the explicit target `tools/engine-bench/run.sh`. Documented here because **a
  test nobody runs does not watch**: this one is wired into the command above,
  not into the default build, and that is a deliberate tradeoff (time and asset
  size), not an accident. There is no GitHub Actions workflow in this repo, so
  "CI" today means the local default build plus this explicit command.

## One bench, not several

This bench **reuses** the oracle merged in #93
(`tools/reference-selfscore/make_samples.py`, `golden-ids.txt`,
`ReferenceSelfScoreTest`); there is no second measurement script. The native-rate
criterion was added to `ReferenceSelfScoreTest` as a second test method over a
second manifest, so all reference numbers come out of one place and one command.

## The `acceptance_gate` fix

`benchmark/finetune/acceptance_gate.py` compared a `--baseline` report with a
`--candidate` that, in the committed `acceptance_gate.json`, pointed at the
**same file**: the native-PER check could only ever pass. It now:

- defaults `--baseline` to the committed snapshot
  `benchmark/finetune/acceptance_baseline.json` (a saved value, not a live
  candidate);
- **refuses** to run when baseline and candidate resolve to the same file;
- updates the snapshot only with an explicit, manual command:

      benchmark/.venv/bin/python benchmark/finetune/acceptance_gate.py \
          --write-baseline --candidate benchmark/finetune/out/eval_baseline_cv_test.json

The committed evidence is the production fp16 baseline
(`eval_baseline_cv_test.json`) against the `ft_v1` fine-tune
(`eval_ftv1_cv_test.json`), a real candidate: the gate reports the difference
instead of an echo.

## Files

- `tools/engine-bench/run.sh` - the one command.
- `tools/engine-bench/native-ids.txt` - the fixed native set (owner's metric).
- `tools/reference-selfscore/` - the oracle builder and the golden set (#93).
- `engine/src/jvmTest/.../ReferenceSelfScoreTest.kt` - per-clip oracle + native rate.
- `engine/src/jvmTest/.../ReferenceVariantAucProbeTest.kt` - AUC guard (default build).
- `engine/src/jvmTest/.../EnGbReferenceAucProbeTest.kt` - en-GB guard (default build).
- `benchmark/finetune/acceptance_gate.py`, `benchmark/finetune/acceptance_baseline.json` - the fixed gate.
