#!/usr/bin/env python3
"""Spanish (es-ES) course bench: does ZIPA score the course better than wav2vec2?

Measures on what learners meet, through the real server `/v1/score`:

    es_course.py clips  --content DIR      the course's own reference clips (drills, words, dialogue turns)
    es_course.py tts    --content DIR      Cartesia takes: each contrast drill read right and with the
                                           minimal-pair word swapped in (same voice), plus every drill read
                                           by an English voice (needs CARTESIA_API_KEY)
    es_course.py transcribe                ZIPA transcripts of every clip (bench.py load_model)
    es_course.py score  --server URL       every clip scored three ways (see below)
    es_course.py report                    results/es_course.csv + the table

`--content` is a glosso-data `content/es` with `dist/` and `audio/`. The server
runs with `GLOSSO_MODEL_FILES=es=wav2vec2_phoneme_multilingual_int8_dyn_pertensor.onnx`,
as production would. Reference = the content's own `ipa` (espeak-ng `es`, per
sentence, `tools/content/phonemize.py`), sent as `expectedIpa` like the app.

Ways a clip is scored:
  w2v2        audio -> server (multilingual int8 ONNX, confidences): the production path
  w2v2-ipa    the transcript w2v2 heard, sent back as actualIpa (no confidences)
  zipa        zipa-cr-small int8 transcript, TO_ESPEAK-mapped, sent as actualIpa
w2v2-ipa vs zipa is the like-for-like model comparison; w2v2 is what users get.
"""

import argparse
import base64
import csv
import json
import re
import subprocess
import time
import urllib.request
from pathlib import Path

import bench

WORK = bench.WORK / "es-course"
ROWS = WORK / "rows.jsonl"
HEARD = WORK / "zipa-cr-small.jsonl"
SCORED = WORK / "scored.jsonl"
ES_VOICES = [("isabel", "c0c374aa-09be-42d9-9828-4d2d7df86962"), ("luis", "b5aa8098-49ef-475d-89b0-c9262ecf33fd"),
             ("rosa", "fb936dd1-66ea-43a0-86bd-18a6203dcda2"), ("gonzalo", "58e531e3-b212-49df-adee-c335a19c2429")]
EN_VOICE = bench.LANGS["en"][2][0]  # skylar, the en bench voice
WAYS = ("w2v2", "w2v2-ipa", "zipa")


def load_jsonl(path):
    return [json.loads(l) for l in path.read_text().splitlines()] if path.exists() else []


def save_rows(rows):
    WORK.mkdir(parents=True, exist_ok=True)
    ROWS.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows))


def course_items(content):
    """id -> (kind, text, ipa, drill) for every scored item of the published course."""
    items = {}
    for f in sorted((content / "dist").glob("drills/*.json")):
        for d in json.loads(f.read_text())["drills"]:
            items[d["id"]] = ("drill", d["text"], d["ipa"], d)
    for f in sorted((content / "dist").glob("words/*.json")):
        for w in json.loads(f.read_text())["words"]:
            items[w["id"]] = ("word", w["lemma"], w["ipa"], None)
    for f in sorted((content / "dist").glob("dialogues/*.json")):
        for d in json.loads(f.read_text())["dialogues"]:
            for i, t in enumerate(d["turns"]):
                items[f"{d['id']}.t{i}"] = ("dialogue", t["text"], t["ipa"], None)
    return items


def cmd_clips(args):
    """The course's normal-speed reference clips, decoded to 16 kHz mono WAV."""
    items = course_items(args.content)
    rows = [r for r in load_jsonl(ROWS) if r["set"] != "course"]
    for ogg in sorted((args.content / "audio").glob("es.*.ogg")):
        if ogg.name.endswith(".slow.ogg"):
            continue
        item_id, voice = ogg.stem.rsplit(".", 1)
        if item_id not in items:
            continue  # stories: their paragraphs carry no per-paragraph reference
        kind, text, ipa, _ = items[item_id]
        wav = WORK / "audio" / "course" / f"{ogg.stem}.wav"
        if not wav.exists():
            wav.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-i", str(ogg), "-ac", "1", "-ar", str(bench.SR),
                            "-sample_fmt", "s16", str(wav)], check=True)
        rows.append(dict(id=f"course.{ogg.stem}", set="course", kind=kind, voice=voice, text=text, ipa=ipa,
                         wav=str(wav.relative_to(WORK))))
    save_rows(rows)
    print(f"[clips] {sum(r['set'] == 'course' for r in rows)} course clips")


def swap(text, word, other):
    """Every whole-word `word` -> `other`, keeping a leading capital."""
    def repl(m):
        return other[0].upper() + other[1:] if m.group(0)[0].isupper() else other
    return re.sub(rf"(?<!\w){re.escape(word)}(?!\w)", repl, text, flags=re.IGNORECASE)


def cmd_tts(args):
    items = course_items(args.content)
    drills = [v[3] for v in items.values() if v[0] == "drill"]
    rows = [r for r in load_jsonl(ROWS) if r["set"] == "course"]
    es, en = bench.cartesia_client("es"), bench.cartesia_client("en")
    chars = 0

    def take(set_, d, text, client, voice):
        nonlocal chars
        wav = WORK / "audio" / set_ / f"{d['id']}.{voice[0]}.wav"
        if not wav.exists():
            bench.write_wav(wav, client.synthesize(text, voice[1], 1.0))
            chars += len(text)
        rows.append(dict(id=f"{set_}.{d['id']}.{voice[0]}", set=set_, kind="drill", voice=voice[0], text=d["text"],
                         said=text, ipa=d["ipa"], rule=d["ruleId"], target=d["target"], contrast=d["contrast"],
                         wav=str(wav.relative_to(WORK))))

    contrast = [d for d in drills if d["contrast"]]
    for i, d in enumerate(contrast):
        wrong = swap(d["text"], d["focus"][0], d["focus"][1])
        assert wrong != d["text"], d["id"]
        voice = ES_VOICES[i % len(ES_VOICES)]
        take("ok", d, d["text"], es, voice)       # the same voice reading it right
        take("swap", d, wrong, es, voice)         # ... and with the minimal-pair word
    for d in drills:
        take("en-voice", d, d["text"], en, EN_VOICE)  # an English speaker reading Spanish
    save_rows(rows)
    print(f"[tts] {len(rows)} rows, {chars} characters billed now")


def cmd_transcribe(args):
    done = {h["id"] for h in load_jsonl(HEARD)}
    todo = [r for r in load_jsonl(ROWS) if r["id"] not in done]
    if not todo:
        return
    run = bench.load_model("zipa-cr-small")
    with HEARD.open("a") as out:
        for r in todo:
            out.write(json.dumps(dict(id=r["id"], heard=run(bench.read_wav(WORK / r["wav"]))), ensure_ascii=False) + "\n")


def post(server, body):
    # A fresh edge client key per call: the local server's 60/min per-client limit is not under test.
    req = urllib.request.Request(f"{server}/v1/score", json.dumps(body).encode(),
                                 {"Content-Type": "application/json", "X-Real-Client-IP": f"bench-{time.monotonic_ns()}"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        f = json.loads(resp.read())
    return dict(evaluated=f.get("evaluated", True), score=f.get("score", 0), heard=f.get("transcription", ""),
                rules=sorted({e["ruleId"] for e in f.get("errors", []) if e.get("ruleId")}),
                misses=[[a["expected"], a["actual"]] for a in f.get("alignment", []) if a.get("status") != "PERFECT"])


def to_espeak(ipa):
    for a, b in bench.TO_ESPEAK:
        ipa = ipa.replace(a, b)
    return ipa


def cmd_score(args):
    heard = {h["id"]: h["heard"] for h in load_jsonl(HEARD)}
    done = {s["id"] for s in load_jsonl(SCORED)}
    with SCORED.open("a") as out:
        for r in load_jsonl(ROWS):
            if r["id"] in done:
                continue
            base = dict(text=r["text"], expectedIpa=r["ipa"], language="es")
            audio = base64.b64encode((WORK / r["wav"]).read_bytes()).decode()
            w = post(args.server, dict(base, audioBase64=audio))
            res = {"w2v2": w,
                   "w2v2-ipa": post(args.server, dict(base, actualIpa=w["heard"])) if w["heard"] else None,
                   "zipa": post(args.server, dict(base, actualIpa=to_espeak(heard[r["id"]]))) if heard[r["id"]].strip() else None}
            res["zipa-raw"] = heard[r["id"]]
            out.write(json.dumps(dict(id=r["id"], **res), ensure_ascii=False) + "\n")
            out.flush()


def score_of(res):
    return res["score"] if res and res["evaluated"] else 0  # nothing heard counts as a failure


def cmd_report(args):
    rows = {r["id"]: r for r in load_jsonl(ROWS)}
    scored = {s["id"]: s for s in load_jsonl(SCORED)}
    out = []
    for way in WAYS:
        def scores(set_, kind=None):
            return [score_of(scored[i][way]) for i, r in rows.items()
                    if r["set"] == set_ and (kind is None or r["kind"] == kind) and i in scored]
        line = dict(way=way)
        for kind in (None, "drill", "word", "dialogue"):
            s = scores("course", kind)
            k = kind or "all"
            line[f"course_{k}_n"] = len(s)
            line[f"course_{k}_mean"] = round(sum(s) / len(s), 1)
            line[f"course_{k}_ge98_pct"] = round(100 * sum(x >= 98 for x in s) / len(s), 1)
        line["course_empty_or_lt50_pct"] = round(100 * sum(x < 50 for x in scores("course")) / len(scores("course")), 1)
        pairs = [(scored[f"ok.{r['id'][5:]}"], s, r) for i, r in rows.items() if r["set"] == "swap"
                 for s in [scored.get(i)] if s and f"ok.{r['id'][5:]}" in scored]
        ok = [score_of(p[0][way]) for p in pairs]
        bad = [score_of(p[1][way]) for p in pairs]
        line["swap_n"] = len(pairs)
        line["ok_mean"] = round(sum(ok) / len(ok), 1)
        line["swap_mean"] = round(sum(bad) / len(bad), 1)
        line["swap_drop_mean"] = round(sum(o - b for o, b in zip(ok, bad)) / len(ok), 1)
        line["swap_dropped_pct"] = round(100 * sum(o - b >= 2 for o, b in zip(ok, bad)) / len(ok), 1)
        line["swap_rule_flag_pct"] = round(100 * sum(r["rule"] in (p[1][way] or {}).get("rules", [])
                                                     for p in pairs for r in [p[2]]) / len(pairs), 1)
        line["ok_false_flag_pct"] = round(100 * sum(r["rule"] in (p[0][way] or {}).get("rules", [])
                                                    for p in pairs for r in [p[2]]) / len(pairs), 1)
        # The pair is told apart: the drill's rule fires on the swapped take and not on the right one.
        line["swap_discriminated_pct"] = round(100 * sum(
            r["rule"] in (p[1][way] or {}).get("rules", []) and r["rule"] not in (p[0][way] or {}).get("rules", [])
            for p in pairs for r in [p[2]]) / len(pairs), 1)
        en = [score_of(scored[i][way]) for i, r in rows.items() if r["set"] == "en-voice" and i in scored]
        native = {r["text"]: score_of(scored[i][way]) for i, r in rows.items()
                  if r["set"] == "course" and r["kind"] == "drill" and i in scored}
        en_drop = [native[r["text"]] - score_of(scored[i][way]) for i, r in rows.items()
                   if r["set"] == "en-voice" and i in scored and r["text"] in native]
        line["en_voice_mean"] = round(sum(en) / len(en), 1)
        line["en_voice_drop_vs_course"] = round(sum(en_drop) / len(en_drop), 1)
        line["en_voice_ge98_pct"] = round(100 * sum(x >= 98 for x in en) / len(en), 1)
        out.append(line)
    bench.RESULTS.mkdir(exist_ok=True)
    path = bench.RESULTS / "es_course.csv"
    with path.open("w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(out[0]))
        w.writeheader()
        w.writerows(out)
    for line in out:
        print(json.dumps(line))


def self_check():
    assert swap("Para mí, para ti.", "para", "parra") == "Parra mí, parra ti."
    assert swap("El cazo está", "cazo", "caso") == "El caso está"
    assert swap("Lucía tiene cien amigas, ¡cien!", "cien", "sien") == "Lucía tiene sien amigas, ¡sien!"
    assert swap("comparamos", "para", "parra") == "comparamos"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["clips", "tts", "transcribe", "score", "report", "check"])
    ap.add_argument("--content", type=Path)
    ap.add_argument("--server", default="http://[::1]:8090")
    args = ap.parse_args()
    self_check()
    {"clips": cmd_clips, "tts": cmd_tts, "transcribe": cmd_transcribe, "score": cmd_score, "report": cmd_report,
     "check": lambda a: print("ok")}[args.command](args)


if __name__ == "__main__":
    main()
