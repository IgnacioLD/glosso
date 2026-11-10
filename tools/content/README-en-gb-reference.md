# en-GB reference regeneration

`content.db` ships the **primary CMUdict reading**, which is American and rhotic:
the drill `en.ih.iy.airport.001` ("The bin is near the gate.") carries
`ðə bˈɪn ˈɪz nˈɪɹ ðə ɡˈeɪt`, so a correct British reading (`nˈiə`, no /ɹ/) is
scored against a sound the learner never says. `en_gb_reference.py` rebuilds the
reference for every scorable item with espeak-ng's `en-gb` voice - the phonemizer
the acoustic model was fine-tuned on - and measures what changes.

## What it writes

| file | contents |
| --- | --- |
| `data/en_gb_ipa.tsv` | one row per scorable item: `key  kind  old_ipa  new_ipa` (dialogue turns key as `<id>#tN`) |
| `data/en_gb_probe.tsv` | `us_ipa -> en_gb_ipa` token map for the 810-clip arbiter probe, so the engine AUC probe can score the same audio against both references |

Only the **IPA text** ships. espeak-ng is GPL-3.0 and stays a build-time tool:
it is never linked, packaged or shipped, and only the generated IPA (data derived
from the input text) is distributed. See `docs/LICENSING.md` 4.b and the guard
`tools/licensing/espeak_boundary_test.py`. The phonemizer itself lives in
`tools/content/phonemize.py`; this tool reuses it and adds no new espeak
reference.

## Run

Run it with the same environment as `tools/content/phonemize.py` (its module
docstring has the exact command), from this directory:

```sh
python en_gb_reference.py \
    --db ../../../glosso-data/content/en/content.db \
    --probe ../../benchmark/arbiter/out/probe_tts_ref.json \
    --cmudict /path/to/cmudict.dict \
    --frequent-words /path/to/frequent-words.txt
```

`--cmudict` is a CMUdict file (`tools/fetch-assets.sh`). `--frequent-words` is a
newline-separated English word list used to disambiguate CMUdict homophones, so a
rare spelling does not hijack the GB reading (`tˈu` -> "thuy" would give `/θaɪ/`
instead of "to" -> `/tuː/`). The list used for the committed data is
`google-10000-english`
(`raw.githubusercontent.com/first20hours/google-10000-english/master/google-10000-english.txt`,
not committed). Omit both and the map falls back to a shorter/first spelling,
which is only safe for the catalogue (there the word is known, not guessed from
IPA).

## Apply to `content.db`

`--apply` never edits the source in place: it hashes the source, writes a **copy**
with each item's `ipa` rewritten, and prints both hashes.

```sh
python en_gb_reference.py --db .../content.db --apply /tmp/content.en-gb.db
```

To ship it, replace `content/en/content.db` in the content repo with the copy and
run the normal publish/sync (`tools/content/pipeline.py` rebuilds `content.db`
from the `dist` banks; regenerating there with `phonemize.py --language en` is the
other, longer path). The app sends the item's `ipa` as `expectedIpa`, so once the
copy is served the reference is British everywhere, cloud and on-device.

## What the numbers say

Over the catalogue (`content.db`), comparing the CMUdict reference with the
espeak en-GB one:

| metric | value |
| --- | ---: |
| scorable records | 2187 |
| records whose IPA changes | 1806 |
| distinct words whose IPA changes | 1273 |
| coda-rhotic tokens (all kinds) | 1528 |
| coda-rhotic, DRILL+WORD (the F3.1 metric) | **278 words / 579 tokens / 467 items** |
| of those, tokens whose coda r is gone | 579 (100%) |

Effect on the fixed 810-clip arbiter probe (216 native / 594 non-native),
measured by `EnGbReferenceAucProbeTest`:

| reference | AUC native > non-native |
| --- | ---: |
| current production (US + CMUdict variants + #77 coda-r widening) | 0.4765 |
| en-GB canonical | 0.4204 |
| en-GB + CMUdict variants fused | 0.4460 |

The en-GB reference **lowers** the separation AUC on this probe. The probe's
native group is 149/216 United States English (only 37 England English), so a
British reference systematically moves away from what those speakers say
(native mean drops 78.3 -> 74.6; non-native 82.2 -> 80.2). The 36 England-English
and other non-rhotic natives benefit; the US majority does not. This is a
dialect-trade-off, not a bug in the remap (each token was hand-checked and the
homophones resolved with a frequency list).

The reported case and the controls are fixed regardless: a British reading of
"The bin is near the gate." reaches 100/PERFECT, and "three thirty" stays 100
with the British /t/ and with the American flap. See `EnGbReferenceTest`.

## Recommendation

Because a wholesale canonical swap costs separation, do not replace the scored
reference in one step. Two safer options for the owner to choose:

1. Keep the US canonical for scoring (the 0.4765 pipeline) and use the generated
   en-GB IPA for **display** only. The owner's case is already 100 via the #77
   coda-r widening.
2. Keep the US canonical and add the en-GB reading as one more **accepted**
   full-sentence reference (the same mechanism as #77, but data-driven from this
   tool). It can only raise a score, so the AUC cannot drop, and both dialects
   are accepted - "accept a variant, never require it".

Whichever is chosen, note that after a canonical swap the server no longer
accepts a rhotic `near` against the non-rhotic reference, because CMUdict has a
single reading for it and `withG2pVariants` only appends alternates when the
dictionary has more than one. Requiring British is exactly the trap F3.1 fixed
from the other side.

## Display notation

The **display** half of option 1 is built by `tools/content/textbook_ipa.py`,
which turns the generated en-GB IPA into learner-dictionary notation and stores
it in each item's `displayIpa` field, leaving the scored `ipa` untouched. See
`README-textbook-phonetics.md` for the mapping, the before/after sample and the
apply steps.
