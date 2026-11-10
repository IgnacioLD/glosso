# Engine benchmark

One command that measures the engine with real audio and fails when it gets
worse. It is the only bench: it reuses the reference-self-score oracle already in
the repo (`tools/reference-selfscore/make_samples.py` + `ReferenceSelfScoreTest`)
instead of a second script of its own.

    tools/engine-bench/run.sh

## The metric that decides

**How many native reference clips reach 98.** The owner: *"lo importante es que
un nativo vaya bien, le de 98 como minimo"*. Measured on a fixed, **non-curated**
set (`native-ids.txt`, 45 clips across words, drills and dialogue turns) so the
clips the model mis-hears count against it. The gate is **>= 90%** (95% is the
stretch), a rejected clip counts as a failure.

    antes de #93 (16e5f37): 18/45 = 40%  (7 rechazados)
    hoy (main, 2026-09-24):  28/45 = 62%  (0 rechazados)

Today is 62%, so this gate is **RED** until the natural-speech fix lands - that
is the number to bring down.

## The guard

Separation AUC of the score the app shows, on the committed 810-clip arbiter
probe. The walls are the **pre-#93** product target. #93 lowered them and
accepting that is a **pending product decision**, so by default the walls are
printed and **not enforced** - but do **not** lower them to make the build pass.
Set `GLOSSO_AUC_STRICT=1` to enforce them.

| Metric | Pre-#93 | Post-#93 (`main` today) | Wall |
| --- | ---: | ---: | ---: |
| `single` | 0,4700 | 0,4617 | 0,465 |
| `deployed` | 0,4759 | 0,4673 | 0,470 |
| `current` (shown score) | 0,4762 | 0,4671 | 0,470 |
| en-GB accepted-both | 0,4446 | 0,4233 | 0,440 |

## What it runs

| Test | Set | Criterion | By default? |
| --- | --- | --- | --- |
| `ReferenceSelfScoreTest` golden | `tools/reference-selfscore/golden-ids.txt` | every clip >= 98 | no (needs model + audio) |
| `ReferenceSelfScoreTest` native rate | `tools/engine-bench/native-ids.txt` | rate >= 90% | no (needs model + audio) |
| `ReferenceVariantAucProbeTest` | `benchmark/arbiter/out/probe_tts_ref.json` | AUC guard (pending), printed | yes (`single`) |
| `EnGbReferenceAucProbeTest` | same + `tools/content/data/en_gb_probe.tsv` | AUC guard (pending), printed | yes |

The cheap AUC guard runs on a plain `./gradlew :engine:jvmTest`. The full audio
bench is the explicit `run.sh` (the model is ~600 MB and the clips are content),
which is why it is not in the default build.

## Why the clip audio is not in git

`golden-ids.txt` and `native-ids.txt` are versioned catalogue ids. The WAVs are
built locally from the published `content.db` and the `.ogg` clips in the
`glosso-data` checkout; they are content, not code, and are not committed.

## Inputs

| Env | Default | What |
| --- | --- | --- |
| `GLOSSO_ASSETS` | `<repo>/data/assets` | model, vocab, cmudict (`tools/fetch-assets.sh`) |
| `GLOSSO_DATA` | auto-detected sibling | checkout with `content/en/content.db` and `content/en/audio/` |
| `GLOSSO_BENCH_WORK` | `<repo>/build/engine-bench` | scratch dir for WAVs + manifests + gradle log |
