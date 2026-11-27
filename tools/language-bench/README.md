# Language bench

A per-language benchmark of small IPA phone recognizers, to decide (a) which
languages we can make content for and (b) which acoustic model to run. A model
has to fit both targets: the VPS (CPU, ~3 GB for the whole server process) and
the phone (int8 ONNX, ideally well under 400 MB).

    tools/keys/glosso-secret run CARTESIA_API_KEY -- tools/language-bench/run.sh

The decision and the tables in Spanish are in `docs/ADD_LANGUAGE.md`
(section 10). This file says how the numbers are made.

## The metric that decides

**A native speaker must score >= 98** (`tools/engine-bench/README.md`). The bench
reports it through the real engine comparator
(`NativeTranscriptBenchTest`, `PhoneticComparator`) for the languages that have
their own similarity matrix (`en`, `ru`, `es`, `fr`, `de`). Any other code falls
back to the English matrix (`getMatrix` in `PhoneticComparator`), so for `it`,
`pt`, `nl`, `pl`, `uk` only the phone error rate is reported: an engine score on
the wrong matrix would look like evidence and is not.

## Setup

- `uv` and Python 3.12 (`run.sh` creates `.venv` here from `requirements.txt`;
  it is gitignored).
- `espeak-ng` on the PATH (`brew install espeak-ng`). It only writes the
  reference IPA, as an external program at bench time, like
  `tools/content/phonemize.py`; it never reaches the app or the server
  (`tools/licensing/espeak_boundary_test.py`).
- `data/assets/` with `wav2vec2_phoneme_int8_dyn_pertensor.onnx` and
  `phoneme_vocab.json` (`tools/fetch-assets.sh`), for the deployed English model.
- Models download from Hugging Face on first use (~2.3 GB in the HF cache).
- Disk: ~0.4 GB of WAVs under `build/language-bench/`.

## Steps

| Command | What |
| --- | --- |
| `bench.py data` | First 100 clips of each FLEURS test set + espeak-ng reference |
| `bench.py tts` | 18 short sentences x 2 Cartesia voices per language |
| `bench.py transcribe MODEL` | Transcript of every clip, cached in `transcripts/MODEL.jsonl` |
| `bench.py perf MODEL --threads N` | Speed and memory in its own process (`N=0`: runtime default) |
| `bench.py score` | `results/per.csv`, `results/samples.json`, engine row files |
| `bench.py engine` | `results/engine.csv` through `NativeTranscriptBenchTest` |

`run.sh` chains them; every step skips what is already on disk. whisper runs
with `--max-fleurs 30` (30 FLEURS clips per language plus all TTS clips; en and
ru have 100 from a first full pass): it is autoregressive, ~25x slower than
ZIPA, and its hallucination loops already rule it out.

## Models

| Key | Model | Params | int8 ONNX | Licence |
| --- | --- | --- | --- | --- |
| `w2v2-lv60` | `facebook/wav2vec2-lv-60-espeak-cv-ft` (current multilingual server model) | 315M | 358 MB | Apache-2.0 |
| `w2v2-en-int8` | deployed English fine-tune (`data/assets`), en only, anchor | 315M | 358 MB | Apache-2.0 base |
| `zipa-cr-small` | `anyspeech/zipa-small-crctc-500k` (CTC) | 64M | 71 MB | CC-BY-4.0 |
| `zipa-t-small` | `anyspeech/zipa-small-noncausal-500k` (transducer) | 65M | 71 MB | CC-BY-4.0 |
| `zipa-cr-large` | `anyspeech/zipa-large-crctc-500k` (CTC) | 300M | 310 MB | CC-BY-4.0 |
| `zipa-cr-ns-small` | `anyspeech/zipa-small-crctc-ns-700k`, reference row only | 64M | 71 MB | CC-BY-NC-SA-4.0: **not usable** in a paid app |
| `whisper-ipa-base` | `neurlang/ipa-whisper-base` (whisper-base fine-tuned to goruut IPA) | 74M | 182 MB | Apache-2.0 |

ZIPA runs its int8 ONNX with the decoding of `inference/inference.py` in
github.com/lingjzhu/zipa (80-dim kaldi fbank via lhotse, greedy CTC or greedy
transducer, `tokens.txt` = `ipa_simplified`). The extra candidates: whisper-ipa
is the only small, permissive, multilingual IPA recognizer with a ready int8
ONNX export we found. Allosaurus was skipped: its code is GPL-3.0.
PhoneticXeus (CMU) is CC-BY-NC-SA. Phonon-2.5 (CC-BY-4.0, 25 languages) is
a text ASR, not a phone recognizer.

## Audio

- **Real native speech: Google FLEURS** (CC-BY-4.0), test split, configs
  `en_us es_419 fr_fr de_de it_it pt_br nl_nl pl_pl ru_ru uk_ua`, the first 100
  clips of each `audio/test.tar.gz` (sorted by file name, so effectively random
  speakers and sentences). The archive is streamed and the read stops after 100
  clips, so only its prefix is downloaded (~40 MB per language instead of the
  ~700 MB parquet). FLEURS sentences are long read Wikipedia sentences (10-20 s):
  much harder than a drill, a single slip keeps a clip under 98.
- **TTS native speech: Cartesia** (`sonic-3.6`, the voices the content pipeline
  uses for fr/de/ru/en, public-library voices for the rest, see `LANGS` in
  `bench.py`). The 18 shortest FLEURS test sentences of each language with at
  least 4 words and no digits, read by one female and one male voice: 36 clips
  per language, 23,644 characters in total. 30 sentences x 2 voices would have
  been ~43k characters, over the budget, so the set is 18. Closer to the app's
  drills (short, clean), but TTS, not humans.

## Reference and normalization

The reference is espeak-ng's IPA of the sentence (`espeak-ng -q --ipa -v <voice>`,
voices `en-us es-419 fr-fr de it pt-br nl pl ru uk`), with the language-switch
flags removed and the Russian folds of `phonemize.py` applied (`canonical_ipa`).

The multilingual wav2vec2 was trained on espeak-ng labels, so it has a home
advantage on an espeak-referenced score. Two phone error rates are reported:

- **PER raw**: the IPA cut into segments, one per letter, each keeping its
  diacritics and modifiers (`aː`, `tʲ`, `ɛ̃`). Only stress marks and word
  boundaries are dropped (no model writes stress the way espeak does).
  Affricates and diphthongs count as one segment per letter, for every model.
- **PER folded**: additionally every diacritic, modifier, length mark and tie bar
  is dropped (the cedilla of `ç` is kept: it is its own phone), then notation
  variants are rewritten (`FOLD` in `bench.py`): `g/ɡ`, the dark-l letters
  `ɫ ɭ`, the rhotic letters `ɾ ɹ ʁ ʀ ɻ`, `ɐ/ə`, `ᵻ/ɪ`, `ɑ/a`, `ʋ/v`, `ɦ/h`,
  `ɚ/ər`, ligatures `ʧ ʤ ʦ`, and the retroflex/postalveolar spellings
  `ʂ ʐ ʈ ɖ` -> `ʃ ʒ t d` and `ɨ/y` (espeak-ng writes Polish sz/cz and Russian
  ш/ч/ы as `ʃ tʃ y`, ZIPA as `ʂ ʈʂ ɨ`; none of the ten languages contrasts them).
  It does not fold real vowel contrasts (`e/ɛ`, `o/ɔ`, `i/ɪ` stay apart).

PER is corpus-level: total edit distance over total reference segments, per
model x language x audio kind.

**Empty/garbage**: share of clips with an empty transcript or a folded PER above
0.5 on that clip.

**Engine score**: every (model, language, kind) group is written as
`[id, voice, "", "true", text, referenceIpa, heardIpa]` rows and run through

    GLOSSO_NATIVE_TRANSCRIPTS=<rows.json> GLOSSO_NATIVE_LANGUAGE=<lang> \
      ./gradlew :engine:jvmTest --rerun --tests 'com.glosso.engine.phonetics.NativeTranscriptBenchTest' -i

`ge98_pct` is over every clip (the test drops empty transcripts; they count as
failures here), `mean` over the clips it scored. For English the production
reference is CMUdict, not espeak, so the English engine row is indicative.

## Speed and memory

`bench.py perf` loads one model in a fresh process and transcribes the first 20
English FLEURS clips (~2-3 min of audio) after a warm-up, single-threaded and
with the runtime's default threads. It reports ms per second of audio, the RSS
the model added when loaded, and the process peak RSS. wav2vec2 is timed through
the int8 ONNX the server loads (the deployed English file: same wav2vec2-large
graph and 392-token head as the multilingual int8, so the same cost); ZIPA
through its int8 ONNX; whisper through transformers fp32 (its ONNX decoder loop
is not wired), so its row is an upper bound.

## Results

`results/per.csv`, `results/engine.csv`, `results/perf.jsonl`,
`results/models.csv`, `results/samples.json` (three transcripts per group). No
audio and no model files are committed. Measured 2026-11-22 on an Apple M1 Max
laptop (10 cores), espeak-ng 1.52.

## Spanish course bench (`es_course.py`)

Is ZIPA (`zipa-cr-small`) better than the multilingual wav2vec2 int8 for the
es-ES course? Measured on what learners meet, through a local server's
`/v1/score` (`GLOSSO_MODEL_FILES=es=wav2vec2_phoneme_multilingual_int8_dyn_pertensor.onnx`),
with the content's own `ipa` as `expectedIpa` (espeak-ng `es`, per sentence,
`tools/content/phonemize.py`). Steps and scoring ways are in the script's
docstring; numbers in `results/es_course.csv`. Measured 2026-11-27.

- Course: 281 normal-speed reference clips (50 drills, 120 words, 111 dialogue
  turns; stories have no per-paragraph reference).
- Swap: 32 contrast drills (r/rr, θ/s, ñ/n, j/k) read right and with the
  minimal-pair word swapped in, same es-ES Cartesia voice. English voice: the 50
  drills read by an English Cartesia voice. 4,263 characters.

| | w2v2 (prod path) | zipa |
| --- | --- | --- |
| course mean / % >= 98 | 88.4 / 27.8 % | 96.5 / 63.0 % |
| course clips < 50 (w2v2: 4 one-word clips heard as nothing) | 3.2 % | 0 % |
| swap: drill rule fires on the error and not on the right take | 46.9 % | 25.0 % |
| right take wrongly flagged with the drill's rule | 3.1 % | 31.2 % |
| English voice: mean / drop vs the course clip | 84.7 / 9.2 | 86.8 / 9.8 |

ZIPA never writes `θ β ð ɣ ʝ` on the course audio (0 of 75 `θ`, 0 of 116 `β`)
and mostly writes `r` for the tap: its high native score is the `es` matrix
forgiving `s/θ`, `b/β`, `d/ð`, `ɡ/ɣ`, while `es.z.s` fires on 19 % of native
clips. wav2vec2 hears `θ` 62 of 75 times. Neither model's score drops on a
single swapped phone; neither flags r/rr (the 0.9 `ɾ/r` similarity).
