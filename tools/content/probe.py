import base64
import json
import urllib.request
import numpy as np
import soundfile as sf

API = "https://api.glossostudio.com/v1/score"
CASES = [
    ("out/test0.wav", "I think my brother is thirsty."),
    ("out/test1.wav", "The cat sat on the mat."),
]


def to_16k(path, out):
    data, sr = sf.read(path, dtype="float32")
    if data.ndim > 1:
        data = data.mean(axis=1)
    n = int(len(data) * 16000 / sr)
    res = np.interp(np.linspace(0, len(data), n, endpoint=False), np.arange(len(data)), data)
    sf.write(out, (np.clip(res, -1, 1) * 32767).astype("<i2"), 16000, subtype="PCM_16")
    return out


for wav, text in CASES:
    pcm = to_16k(wav, wav.replace(".wav", ".16k.wav"))
    b64 = base64.b64encode(open(pcm, "rb").read()).decode()
    body = json.dumps({"text": text, "audioBase64": b64}).encode()
    req = urllib.request.Request(API, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        d = json.load(r)
    print(f"\n=== {text}")
    print("kokoro -> wav2vec transcription:", d.get("transcription"))
    print("score:", d.get("score"), "accuracy:", d.get("accuracy"), "level:", d.get("level"))
    print("timings:", len(d.get("timings", [])))
    errs = [(e.get("phoneme"), e.get("actual"), e.get("ruleId")) for e in d.get("errors", [])]
    print("errors:", errs)
