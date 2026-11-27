#!/usr/bin/env python3
"""Per-language benchmark of small IPA phone recognizers.

Answers two questions with numbers: which languages can we make content for (a
native speaker must score >= 98) and which acoustic model to run. Every step
caches its output under the work dir, so a re-run only does what is missing.

    bench.py data                 FLEURS clips + espeak-ng references
    bench.py tts                  Cartesia clips (needs CARTESIA_API_KEY)
    bench.py transcribe MODEL     transcripts of every clip with one model
    bench.py perf MODEL           speed and memory of one model (own process)
    bench.py score                PER tables + engine rows -> results/
    bench.py engine               engine score of the rows (NativeTranscriptBenchTest)

`run.sh` chains them. espeak-ng is only called as an external program to write
the reference IPA (build-time, like tools/content/phonemize.py); nothing here
ships in the app or the server.
"""

import argparse
import csv
import io
import json
import os
import re
import resource
import subprocess
import sys
import tarfile
import time
import unicodedata
import urllib.request
import wave
from pathlib import Path

import numpy as np
import soundfile as sf

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
WORK = Path(os.environ.get("GLOSSO_LANGBENCH_WORK", REPO / "build" / "language-bench"))
ASSETS = Path(os.environ.get("GLOSSO_ASSETS", REPO / "data" / "assets"))
RESULTS = HERE / "results"
ESPEAK = os.environ.get("ESPEAK_NG", "espeak-ng")
SR = 16000
FLEURS = "https://huggingface.co/datasets/google/fleurs/resolve/main/data"
FLEURS_CLIPS = int(os.environ.get("GLOSSO_LANGBENCH_FLEURS", "100"))
TTS_SENTENCES = int(os.environ.get("GLOSSO_LANGBENCH_TTS", "18"))

sys.path.insert(0, str(REPO / "tools" / "content"))
from phonemize import canonical_ipa  # noqa: E402  same reference folds as the content

# FLEURS config, espeak-ng voice, Cartesia voices (female, male). fr/de/ru/en are
# the voices of the LanguagePack / cartesia_a1_audio palettes; the rest were
# picked from Cartesia's public library (2026-11-22) to match the FLEURS accent.
LANGS = {
    "en": ("en_us", "en-us", [("skylar", "db6b0ed5-d5d3-463d-ae85-518a07d3c2b4"), ("daniel", "47c38ca4-5f35-497b-b1a3-415245fb35e1")]),
    "ru": ("ru_ru", "ru", [("tatiana", "064b17af-d36b-4bfb-b003-be07dba1b649"), ("sergei", "1e4176b1-3db9-44d6-a601-4fe68b041942")]),
    "es": ("es_419", "es-419", [("ximena", "3597a26f-80ef-4bd5-8101-9699bc764917"), ("mateo", "2fc4f1ec-bfd0-46f1-8e6d-d4279eaaf838")]),
    "fr": ("fr_fr", "fr-fr", [("ines", "7c58f4a4-a72c-42fa-a503-41b9408820f3"), ("etienne", "c6ccfe32-6bee-484a-a8a2-7a51bee93f99")]),
    "de": ("de_de", "de", [("lena", "4ab1ff51-476d-42bb-8019-4d315f7c0c05"), ("clemens", "57a3a9e0-a91c-4c94-a2bb-e6cbab3ae649")]),
    "it": ("it_it", "it", [("elena", "00e9ec78-2002-41dd-8d19-6b1d3b17a461"), ("lorenzo", "ee16f140-f6dc-490e-a1ed-c1d537ea0086")]),
    "pt": ("pt_br", "pt-br", [("larissa", "8d826d43-20ad-4c56-8d37-1048eccca1bf"), ("rafael", "07b6f895-78b9-4921-8e10-8a21c99c2e8a")]),
    "nl": ("nl_nl", "nl", [("noa", "96355f3d-0179-4c9a-a8d8-11ef0779a9b8"), ("stijn", "da743a82-ddf2-4d9b-8eb8-ff67ca0b138e")]),
    "pl": ("pl_pl", "pl", [("ewa", "f8aacd6e-1ac2-42d3-bcbf-125336ecd0f2"), ("kacper", "43e52207-96fc-4e01-aaf8-cae317e43fdb")]),
    "uk": ("uk_ua", "uk", [("yaryna", "7246935f-0668-4490-a403-309608178d8d"), ("taras", "89fc495e-2a13-45b1-a4f1-3fe66a72d9ec")]),
}

# Languages with their own similarity matrix in PhoneticComparator; any other
# code falls back to the English matrix, so its engine score would not mean much.
ENGINE_LANGS = {"en", "ru", "es", "fr", "de"}

# params, int8 ONNX bytes on disk (HF listings 2026-11-22), licence, runtime used
# for accuracy. Sizes of the files the app/server would load, not of what we run.
MODELS = {
    "w2v2-lv60": dict(params="315M", int8_mb=358, licence="Apache-2.0",
                      note="facebook/wav2vec2-lv-60-espeak-cv-ft; accuracy in fp32 (transformers), int8 = the server file"),
    "w2v2-en-int8": dict(params="315M", int8_mb=358, licence="Apache-2.0 (base) / own fine-tune",
                         note="deployed English model, en only (anchor)", langs={"en"}),
    "zipa-cr-small": dict(params="64M", int8_mb=71, licence="CC-BY-4.0",
                          note="anyspeech/zipa-small-crctc-500k, model.int8.onnx"),
    "zipa-cr-large": dict(params="300M", int8_mb=310, licence="CC-BY-4.0",
                          note="anyspeech/zipa-large-crctc-500k, model.int8.onnx"),
    "zipa-t-small": dict(params="65M", int8_mb=71, licence="CC-BY-4.0",
                         note="anyspeech/zipa-small-noncausal-500k, transducer int8"),
    "zipa-cr-ns-small": dict(params="64M", int8_mb=71, licence="CC-BY-NC-SA-4.0 (NOT usable)",
                             note="anyspeech/zipa-small-crctc-ns-700k, reference only"),
    "whisper-ipa-base": dict(params="74M", int8_mb=182, licence="Apache-2.0",
                             note="neurlang/ipa-whisper-base, fp32 (transformers); int8 = onnx-community encoder+merged decoder"),
}


# ----------------------------------------------------------------- IPA handling

STRESS = "ˈˌ"
FLAG = re.compile(r"\([a-z-]+\)")
# Notation variants that are the same sound written differently by espeak-ng,
# ZIPA's ipa_simplified and goruut (applied to single characters after the
# diacritics are gone). Not phonetic folding: e/ɛ, i/ɪ, o/ɔ stay apart.
FOLD = {
    "g": "ɡ", "ɫ": "l", "ɭ": "l",
    "ɾ": "r", "ɹ": "r", "ʁ": "r", "ʀ": "r", "ɻ": "r",  # rhotic notation varies by convention
    "ɐ": "ə", "ᵻ": "ɪ", "ɑ": "a", "ʋ": "v", "ɦ": "h",
    # espeak-ng writes Polish/Russian sz, ż, cz as ʃ ʒ tʃ and Russian Ы as y, Polish
    # y as ɨ; ZIPA writes ʂ ʐ ʈʂ and ɨ. None of the ten languages contrasts them.
    "ʂ": "ʃ", "ʐ": "ʒ", "ʈ": "t", "ɖ": "d", "ɨ": "y",
    "ɚ": "ər", "ɝ": "ɜr", "ʧ": "tʃ", "ʤ": "dʒ", "ʦ": "ts",
}
KEEP_MARKS = {chr(0x327)}  # cedilla: ç is its own phone, not a diacritic on c

# What a non-espeak model (ZIPA, goruut whisper) needs before the engine, which
# compares symbols in espeak-ng notation: the first cut of the notation mapping a
# switch would ship (cf. ModelInventory). Engine rows only; PER uses FOLD.
TO_ESPEAK = [(chr(0x334), ""), ("g", "ɡ"), ("ɜ˞", "ɚ"), ("ʀ", "ʁ"), ("ʈʂ", "tʃ"), ("ʂ", "ʃ"), ("ʐ", "ʒ")]


def _is_base(ch):
    return unicodedata.category(ch).startswith("L") and unicodedata.category(ch) != "Lm"


def _is_modifier(ch):
    return unicodedata.category(ch) in ("Mn", "Lm", "Sk")


def segments(ipa, folded):
    """IPA -> list of phone segments; affricates and diphthongs count per letter.

    raw: a base letter keeps its diacritics and modifiers (`aː`, `tʲ`, `ɛ̃`),
    stress marks and word boundaries are dropped. folded: every diacritic,
    modifier, length and tie bar goes too, then FOLD rewrites notation variants.
    """
    s = FLAG.sub("", ipa or "")
    for ch in STRESS:
        s = s.replace(ch, "")
    if folded:
        s = unicodedata.normalize("NFD", s)
        s = "".join(c for c in s if not _is_modifier(c) or c in KEEP_MARKS)
        s = unicodedata.normalize("NFC", s)
        s = "".join(FOLD.get(c, c) for c in s)
        return [c for c in s if _is_base(c)]
    s = unicodedata.normalize("NFC", s)
    out = []
    for c in s:
        if _is_base(c):
            out.append(c)
        elif _is_modifier(c) and out:
            out[-1] += c
    return out


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def espeak_ipa(text, voice, language):
    """Sentence IPA with stress and word spaces, as the content pipeline writes it."""
    out = subprocess.run([ESPEAK, "-q", "--ipa", "-v", voice, "--stdin"], input=text,
                         capture_output=True, text=True, check=True).stdout
    return canonical_ipa(" ".join(FLAG.sub("", out).split()), language)


def ctc_tokens(ids, vocab, blank):
    """Greedy CTC collapse, dropping specials and Mandarin tone tokens like the engine."""
    out, prev = [], None
    for i in ids:
        if i != prev and i != blank:
            tok = vocab.get(int(i), "")
            if tok and not (tok.startswith("<") and tok.endswith(">")) and not any(c.isdigit() for c in tok):
                out.append(tok)
        prev = i
    return out


# ----------------------------------------------------------------------- audio

def read_wav(path):
    with wave.open(str(path)) as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0


def write_wav(path, pcm16):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm16)


def manifest_path():
    return WORK / "manifest.jsonl"


def load_manifest():
    rows = {}
    if manifest_path().exists():
        for line in manifest_path().read_text().splitlines():
            row = json.loads(line)
            rows[row["id"]] = row
    return rows


def save_manifest(rows):
    WORK.mkdir(parents=True, exist_ok=True)
    manifest_path().write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows.values()))


def fleurs_tsv(config):
    cache = WORK / "fleurs" / f"{config}.test.tsv"
    if not cache.exists():
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(urllib.request.urlopen(f"{FLEURS}/{config}/test.tsv").read())
    rows = {}
    for line in cache.read_text(encoding="utf-8").splitlines():
        cols = line.split("\t")
        rows[cols[1]] = cols[2]  # file name -> raw transcription
    return rows


def cmd_data(args):
    """First FLEURS_CLIPS test clips per language, streamed from the tar.gz prefix."""
    rows = load_manifest()
    for lang in args.langs:
        config, voice, _ = LANGS[lang]
        texts = fleurs_tsv(config)
        have = [r for r in rows.values() if r["lang"] == lang and r["kind"] == "fleurs"]
        if len(have) >= FLEURS_CLIPS:
            continue
        print(f"[data] {lang}: streaming {config} test.tar.gz", flush=True)
        n = 0
        # Reading stops after FLEURS_CLIPS members: only the archive prefix is downloaded.
        with urllib.request.urlopen(f"{FLEURS}/{config}/audio/test.tar.gz") as resp, \
                tarfile.open(fileobj=resp, mode="r|gz") as tar:
            for member in tar:
                name = Path(member.name).name
                if not member.isfile() or name not in texts:
                    continue
                audio, rate = sf.read(io.BytesIO(tar.extractfile(member).read()), dtype="float32", always_2d=True)
                if rate != SR:
                    continue
                # FLEURS stores float WAVs; keep the 16-bit PCM the app records.
                pcm = (np.clip(audio[:, 0], -1, 1) * 32767).astype(np.int16).tobytes()
                path = WORK / "audio" / lang / "fleurs" / name
                write_wav(path, pcm)
                text = texts[name]
                cid = f"fleurs.{lang}.{Path(name).stem}"
                rows[cid] = dict(id=cid, lang=lang, kind="fleurs", voice="fleurs", path=str(path.relative_to(WORK)),
                                 text=text, ref=espeak_ipa(text, voice, lang), secs=len(pcm) / 2 / SR)
                n += 1
                if n >= FLEURS_CLIPS:
                    break
        save_manifest(rows)
        print(f"[data] {lang}: {n} clips", flush=True)


def tts_sentences(lang):
    """The shortest distinct FLEURS test sentences without digits (TTS and espeak
    read numbers differently), at least 4 words: short keeps Cartesia usage low."""
    texts = sorted({t for t in fleurs_tsv(LANGS[lang][0]).values()
                    if not re.search(r"\d", t) and len(t.split()) >= 4}, key=lambda t: (len(t), t))
    return texts[:TTS_SENTENCES]


def cartesia_client(language):
    """The content pipeline's Cartesia client, returning raw 16 kHz PCM."""
    if not os.environ.get("SSL_CERT_FILE") and os.path.isfile("/etc/ssl/cert.pem"):
        os.environ["SSL_CERT_FILE"] = "/etc/ssl/cert.pem"
    from cartesia_a1_audio import CartesiaClient, DEFAULT_API_VERSION, DEFAULT_BASE_URL, DEFAULT_MODEL, read_api_key

    class Client16k(CartesiaClient):
        # Same request as the content client, at the recognizer's 16 kHz.
        def synthesize(self, text, voice_id, speed):
            body = json.dumps({
                "model_id": self.model, "transcript": text, "voice": {"mode": "id", "id": voice_id},
                "language": self.language,
                "output_format": {"container": "raw", "encoding": "pcm_s16le", "sample_rate": SR},
                "generation_config": {"speed": speed},
            }).encode()
            status, data, _ = self._request("POST", f"{self.base}/tts/bytes", {
                "Authorization": f"Bearer {self.token()}", "Content-Type": "application/json",
                "Cartesia-Version": self.version}, body)
            if status != 200:
                raise RuntimeError(f"cartesia {status}: {data[:200]!r}")
            return data

    key = read_api_key(str(Path.home() / "glosso-keys" / ".env"))
    return Client16k(key, DEFAULT_BASE_URL, DEFAULT_API_VERSION, DEFAULT_MODEL, language=language)


def cmd_tts(args):
    rows = load_manifest()
    chars = 0
    for lang in args.langs:
        _, voice, voices = LANGS[lang]
        client = cartesia_client(lang)
        for i, text in enumerate(tts_sentences(lang)):
            for name, vid in voices:
                cid = f"tts.{lang}.{name}.{i:02d}"
                path = WORK / "audio" / lang / "tts" / f"{name}.{i:02d}.wav"
                if not path.exists():
                    write_wav(path, client.synthesize(text, vid, 1.0))
                    chars += len(text)
                rows[cid] = dict(id=cid, lang=lang, kind="tts", voice=name, path=str(path.relative_to(WORK)),
                                 text=text, ref=espeak_ipa(text, voice, lang), secs=path.stat().st_size / 2 / SR)
        save_manifest(rows)
        print(f"[tts] {lang}: done ({chars} characters billed so far)", flush=True)


# ---------------------------------------------------------------------- models

def load_model(name, threads=0):
    """-> transcribe(float32 16 kHz mono) -> IPA string (words split by spaces when the model marks them)."""
    if name in ("w2v2-lv60", "w2v2-lv60-int8", "w2v2-en-int8"):
        if name == "w2v2-lv60":
            import torch
            from huggingface_hub import hf_hub_download
            from transformers import Wav2Vec2ForCTC
            if threads:
                torch.set_num_threads(threads)
            repo = "facebook/wav2vec2-lv-60-espeak-cv-ft"
            model = Wav2Vec2ForCTC.from_pretrained(repo).eval()
            vocab = {v: k for k, v in json.loads(Path(hf_hub_download(repo, "vocab.json")).read_text()).items()}

            def logits(x):
                with torch.inference_mode():
                    return model(torch.from_numpy(x)[None]).logits[0].numpy()
        else:
            # The deployed English file; also the speed/RAM proxy of the multilingual
            # int8 (same wav2vec2-large graph, same 392-token head, same quantization).
            import onnxruntime as ort
            opts = ort.SessionOptions()
            opts.intra_op_num_threads = threads
            sess = ort.InferenceSession(str(ASSETS / "wav2vec2_phoneme_int8_dyn_pertensor.onnx"), opts)
            vocab = {v: k for k, v in json.loads((ASSETS / "phoneme_vocab.json").read_text()).items()}

            def logits(x):
                return sess.run(None, {"input_values": x[None]})[0][0]

        def run(audio):
            x = ((audio - audio.mean()) / max(audio.std(), 1e-9)).astype(np.float32)
            return "".join(ctc_tokens(logits(x).argmax(-1), vocab, 0))
        return run

    if name.startswith("zipa"):
        import onnxruntime as ort
        import torch
        from huggingface_hub import hf_hub_download
        from lhotse.features.kaldi.extractors import Fbank, FbankConfig
        repo = {"zipa-cr-small": "anyspeech/zipa-small-crctc-500k", "zipa-cr-large": "anyspeech/zipa-large-crctc-500k",
                "zipa-t-small": "anyspeech/zipa-small-noncausal-500k",
                "zipa-cr-ns-small": "anyspeech/zipa-small-crctc-ns-700k"}[name]
        vocab = {}
        for line in Path(hf_hub_download(repo, "tokens.txt")).read_text(encoding="utf-8").splitlines():
            tok, idx = line.split()
            vocab[int(idx)] = " " if tok == "▁" else tok
        fbank = Fbank(FbankConfig(num_filters=80, dither=0.0, snip_edges=False))
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = threads
        torch.set_num_threads(max(threads, 1) if threads else torch.get_num_threads())

        def feats(audio):
            f = fbank.extract_batch([torch.from_numpy(audio)], sampling_rate=SR)[0].numpy()[None]
            return {"x": f.astype(np.float32), "x_lens": np.array([f.shape[1]], dtype=np.int64)}

        if name == "zipa-t-small":
            enc, dec, join = (ort.InferenceSession(hf_hub_download(repo, f"{p}-epoch-999-avg-1.int8.onnx"), opts)
                              for p in ("encoder", "decoder", "joiner"))

            def run(audio):
                out = enc.run(None, feats(audio))[0][0]
                ctx = np.zeros((1, 2), dtype=np.int64)
                d = dec.run(None, {"y": ctx})[0]
                toks = []
                for t in range(out.shape[0]):
                    for _ in range(3):  # max symbols per frame, as ZIPA's own greedy search
                        k = int(join.run(None, {"encoder_out": out[t:t + 1], "decoder_out": d})[0].argmax())
                        if k == 0:
                            break
                        toks.append(vocab.get(k, ""))
                        ctx = np.array([[ctx[0, 1], k]], dtype=np.int64)
                        d = dec.run(None, {"y": ctx})[0]
                return " ".join("".join(t for t in toks if not t.startswith("<")).split())
            return run

        sess = ort.InferenceSession(hf_hub_download(repo, "model.int8.onnx"), opts)

        def run(audio):
            toks = ctc_tokens(sess.run(None, feats(audio))[0][0].argmax(-1), vocab, 0)
            return " ".join("".join(toks).split())
        return run

    if name == "whisper-ipa-base":
        import torch
        from transformers import WhisperForConditionalGeneration, WhisperProcessor
        if threads:
            torch.set_num_threads(threads)
        repo = "neurlang/ipa-whisper-base"
        proc = WhisperProcessor.from_pretrained(repo)
        model = WhisperForConditionalGeneration.from_pretrained(repo).eval()
        model.generation_config.forced_decoder_ids = None  # as the model card: language left to the model

        def run(audio):
            feats = proc(audio, sampling_rate=SR, return_tensors="pt").input_features
            with torch.inference_mode():
                ids = model.generate(feats, max_new_tokens=440)
            return " ".join(proc.batch_decode(ids, skip_special_tokens=True)[0].split())
        return run

    raise SystemExit(f"unknown model {name}")


def cmd_transcribe(args):
    rows = load_manifest()
    langs = MODELS.get(args.model, {}).get("langs") or set(args.langs)
    out_path = WORK / "transcripts" / f"{args.model}.jsonl"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    done = {json.loads(l)["id"] for l in out_path.read_text().splitlines()} if out_path.exists() else set()
    picked = [r for r in rows.values() if r["lang"] in langs]
    if args.max_fleurs:
        # The first N FLEURS clips of each language (manifest order), all TTS clips.
        seen = {}
        picked = [r for r in picked if r["kind"] != "fleurs"
                  or seen.setdefault(r["lang"], []).append(r) or len(seen[r["lang"]]) <= args.max_fleurs]
    todo = [r for r in picked if r["id"] not in done]
    if not todo:
        return
    run = load_model(args.model)
    start = time.time()
    with out_path.open("a") as out:
        for i, r in enumerate(todo, 1):
            heard = run(read_wav(WORK / r["path"]))
            out.write(json.dumps(dict(id=r["id"], heard=heard), ensure_ascii=False) + "\n")
            out.flush()
            if i % 50 == 0:
                print(f"[{args.model}] {i}/{len(todo)} ({time.time() - start:.0f}s)", flush=True)


def cmd_perf(args):
    """Speed and memory of one model, in its own process so RSS is its own.

    The clips are the first 20 English FLEURS clips (any language costs the same).
    w2v2 is timed through the int8 ONNX the server loads; ZIPA through its int8
    ONNX; whisper through transformers fp32 (its ONNX decoder loop is not wired)."""
    import psutil
    name = {"w2v2-lv60": "w2v2-lv60-int8"}.get(args.model, args.model)
    rows = [r for r in load_manifest().values() if r["lang"] == "en" and r["kind"] == "fleurs"][:20]
    audios = [read_wav(WORK / r["path"]) for r in rows]
    # Runtimes first, so model_rss_mb is the model, not torch (the app/server
    # would compute fbank/mel features natively, without torch).
    import onnxruntime  # noqa: F401
    import torch  # noqa: F401
    import transformers  # noqa: F401
    from lhotse.features.kaldi.extractors import Fbank  # noqa: F401
    base = psutil.Process().memory_info().rss
    run = load_model(name, threads=args.threads)
    loaded = psutil.Process().memory_info().rss
    run(audios[0])  # warm-up
    secs = sum(len(a) for a in audios) / SR
    start = time.time()
    for a in audios:
        run(a)
    wall = time.time() - start
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    peak = peak if sys.platform == "darwin" else peak * 1024
    print(json.dumps(dict(model=args.model, threads=args.threads or "default", audio_s=round(secs, 1),
                          ms_per_audio_s=round(1000 * wall / secs, 1), rtf=round(wall / secs, 4),
                          model_rss_mb=round((loaded - base) / 2**20), peak_rss_mb=round(peak / 2**20))))


# --------------------------------------------------------------------- scoring

def cmd_score(args):
    rows = load_manifest()
    RESULTS.mkdir(exist_ok=True)
    summary, samples = [], []
    for tpath in sorted((WORK / "transcripts").glob("*.jsonl")):
        model = tpath.stem
        heard = {json.loads(l)["id"]: json.loads(l)["heard"] for l in tpath.read_text().splitlines()}
        groups = {}
        for cid, h in heard.items():
            r = rows.get(cid)
            if r:
                groups.setdefault((r["lang"], r["kind"]), []).append((r, h))
        for (lang, kind), items in sorted(groups.items()):
            acc = dict(raw_e=0, raw_n=0, fold_e=0, fold_n=0, bad=0)
            engine_rows = []
            for r, h in items:
                ref_r, hyp_r = segments(r["ref"], False), segments(h, False)
                ref_f, hyp_f = segments(r["ref"], True), segments(h, True)
                acc["raw_e"] += edit_distance(ref_r, hyp_r)
                acc["raw_n"] += len(ref_r)
                fe = edit_distance(ref_f, hyp_f)
                acc["fold_e"] += fe
                acc["fold_n"] += len(ref_f)
                # Garbage: nothing heard, or more than half the reference phones wrong after folding.
                acc["bad"] += (not hyp_f) or fe / max(len(ref_f), 1) > 0.5
                eh = h
                if not model.startswith("w2v2"):
                    for a, b in TO_ESPEAK:
                        eh = eh.replace(a, b)
                engine_rows.append([r["id"], r["voice"], "", "true", r["text"], r["ref"], eh])
            summary.append(dict(model=model, lang=lang, kind=kind, clips=len(items),
                                per_raw=round(acc["raw_e"] / acc["raw_n"], 3),
                                per_folded=round(acc["fold_e"] / acc["fold_n"], 3),
                                garbage_pct=round(100 * acc["bad"] / len(items), 1)))
            if lang in ENGINE_LANGS:
                path = WORK / "engine" / f"{model}.{lang}.{kind}.json"
                path.parent.mkdir(exist_ok=True)
                path.write_text(json.dumps(engine_rows, ensure_ascii=False))
            for r, h in items[:3]:
                samples.append(dict(model=model, lang=lang, kind=kind, id=r["id"], text=r["text"], ref=r["ref"], heard=h))
    write_csv(RESULTS / "per.csv", summary)
    write_csv(RESULTS / "models.csv", [dict(model=m, params=v["params"], int8_mb=v["int8_mb"], licence=v["licence"],
                                            note=v["note"]) for m, v in MODELS.items()])
    (RESULTS / "samples.json").write_text(json.dumps(samples, ensure_ascii=False, indent=1) + "\n")
    print(f"wrote {RESULTS / 'per.csv'} ({len(summary)} rows)")


def cmd_engine(args):
    """Each engine row file through NativeTranscriptBenchTest (the real comparator).

    The test drops rows with an empty transcript; those count as < 98 here, so
    ge98_pct is over every clip and mean over the scored ones."""
    out = []
    files = sorted((WORK / "engine").glob("*.json"))
    for i, path in enumerate(files, 1):
        model, lang, kind = path.stem.rsplit(".", 2)
        total = len(json.loads(path.read_text()))
        env = dict(os.environ, GLOSSO_NATIVE_TRANSCRIPTS=str(path), GLOSSO_NATIVE_LANGUAGE=lang)
        log = subprocess.run(["./gradlew", "-q", ":engine:jvmTest", "--rerun", "--tests",
                              "com.glosso.engine.phonetics.NativeTranscriptBenchTest", "-i"],
                             cwd=REPO, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True).stdout
        line = next((l.strip() for l in log.splitlines() if l.strip().startswith("NATIVE98|") and "|kind=drill|" in l), None)
        if not line:
            print(f"[engine] no NATIVE98 line for {path.name}", flush=True)
            continue
        f = dict(p.split("=", 1) for p in line.split("|")[1:])
        out.append(dict(model=model, lang=lang, kind=kind, clips=total, scored=int(f["clips"]), mean=float(f["mean"].replace(",", ".")),
                        ge98=int(f["ge98"]), ge98_pct=round(100 * int(f["ge98"]) / total, 1),
                        ge90_pct=round(100 * int(f["ge90"]) / total, 1)))
        print(f"[engine] {i}/{len(files)} {out[-1]}", flush=True)
    write_csv(RESULTS / "engine.csv", out)


def write_csv(path, rows):
    if not rows:
        return
    with path.open("w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)


def self_check():
    assert segments("ˈaːtʲ ɛ̃(en)", False) == ["aː", "tʲ", "ɛ̃"]
    assert segments("ˈaːtʲ ɛ̃", True) == ["a", "t", "ɛ"]
    assert segments("ɡʁˈyːsə ɐ", True) == segments("g r y s ə ə", True)
    assert segments("ç", True) == ["ç"]
    assert segments("ʈʂɨ", True) == segments("tʃy", True)
    assert edit_distance(list("abc"), list("axc")) == 1 and edit_distance([], list("ab")) == 2
    assert ctc_tokens([0, 5, 5, 0, 5, 7, 9], {5: "a", 7: "<s>", 9: "i5"}, 0) == ["a", "a"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["data", "tts", "transcribe", "perf", "score", "engine", "check"])
    ap.add_argument("model", nargs="?")
    ap.add_argument("--langs", default=",".join(LANGS))
    ap.add_argument("--max-fleurs", type=int, default=0, help="transcribe: only the first N FLEURS clips per language")
    ap.add_argument("--threads", type=int, default=0, help="perf: intra-op threads, 0 = runtime default")
    args = ap.parse_args()
    args.langs = args.langs.split(",")
    self_check()
    {"data": cmd_data, "tts": cmd_tts, "transcribe": cmd_transcribe, "perf": cmd_perf,
     "score": cmd_score, "engine": cmd_engine, "check": lambda a: print("ok")}[args.command](args)


if __name__ == "__main__":
    main()
