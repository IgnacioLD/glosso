import base64
import json
import ssl
import urllib.request
import certifi
import numpy as np
import soundfile as sf

API = "https://api.glossostudio.com/v1/score"
SSL_CONTEXT = ssl.create_default_context(cafile=certifi.where())
TEXT = "I think my brother is thirsty."
VOICES = ["af_kore", "am_puck", "bf_lily", "bm_fable", "af_nova", "am_onyx", "am_eric", "bf_alice"]


def to_16k(path):
    data, sr = sf.read(path, dtype="float32")
    if data.ndim > 1:
        data = data.mean(axis=1)
    n = int(len(data) * 16000 / sr)
    res = np.interp(np.linspace(0, len(data), n, endpoint=False), np.arange(len(data)), data)
    out = path.replace(".wav", ".16k.wav")
    sf.write(out, (np.clip(res, -1, 1) * 32767).astype("<i2"), 16000, subtype="PCM_16")
    return out


for voice in VOICES:
    pcm = to_16k(f"out/voices_all/{voice}.wav")
    b64 = base64.b64encode(open(pcm, "rb").read()).decode()
    body = json.dumps({"text": TEXT, "audioBase64": b64}).encode()
    req = urllib.request.Request(
        API,
        data=body,
        headers={"Content-Type": "application/json", "User-Agent": "Mozilla/5.0 (glosso-content-pipeline)"},
    )
    with urllib.request.urlopen(req, timeout=120, context=SSL_CONTEXT) as r:
        d = json.load(r)
    print(f"{voice:10s} score={d.get('score'):3d} acc={d.get('accuracy'):3d} level={d.get('level'):8s} {d.get('transcription')}")
