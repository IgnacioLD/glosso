# Reference self-score oracle

The engine must score its **own reference audio** - the TTS clip the learner
hears in "Normal / Lenta" - at ~100 against the expected IPA of that same text.
If it does not, the score is measuring the mismatch between the reference
pronunciation and the acoustic model, not the learner, and nothing the app says
can be trusted.

This directory holds the mechanism to check that, and the tool that builds it.
The clips are content (derived from the published catalogue) and are **not**
committed; only the ids and the criterion are.

## Build the sample

    python tools/reference-selfscore/make_samples.py \
        --db <content.db> \
        --audio-root <content/en/audio> \
        --ids-file tools/reference-selfscore/golden-ids.txt \
        --out /tmp/glosso-refscore

`--db` is a published `content.db` (https://api.glossostudio.com/v1/content or a
local publish); `--audio-root` is the directory holding the `.ogg` clips the
manifest points at. Each clip is converted to the 16 kHz mono WAV the recognizer
expects and a `manifest.json` is written next to it.

Without `--ids-file` the tool takes a deterministic random sample (30-50 clips
across words, drills and dialogue turns) for measurement; that broad sample is
the honest picture and includes the clips the model mis-hears (docs/FINDINGS.md
F5).

## Run the oracle

    GLOSSO_REFERENCE_MANIFEST=/tmp/glosso-refscore/manifest.json \
    GLOSSO_MODEL=<model_fp16.onnx> \
    GLOSSO_VOCAB=<phoneme_vocab.json> \
    GLOSSO_CMUDICT=<cmudict.dict> \
        ./gradlew :engine:jvmTest --tests '*ReferenceSelfScoreTest*'

`ReferenceSelfScoreTest` scores every clip against its own `ipa`, widened with
the CMUdict alternates exactly like the server (`widenReferenceVariants`), and
requires each to reach 98. It skips quietly when the manifest or the assets are
missing, so a plain `jvmTest` still works. `GLOSSO_REQUIRE_REFERENCE_SAMPLE=1`
turns a missing input into a hard failure for a CI job that stages them.
`GLOSSO_DEBUG=1` enables the engine log, which prints the gate's reason when a
clip is rejected.
