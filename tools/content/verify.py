import base64
import json
import ssl
import urllib.request

import certifi
import numpy as np
import soundfile as sf

API = "https://api.glossostudio.com/v1/score"
CTX = ssl.create_default_context(cafile=certifi.where())
DB = "out/local/content.db"
CASES = ["en.th.s.family.001", "en.r.w.work.001", "en.ae.eh.family.001", "en.th.s.travel.001", "en.ih.iy.travel.001"]


def to16(path):
    data, sr = sf.read(path, dtype="float32")
    if data.ndim > 1:
        data = data.mean(axis=1)
    n = int(len(data) * 16000 / sr)
    return np.interp(np.linspace(0, len(data), n, endpoint=False), np.arange(len(data)), data)


def wav_bytes(pcm):
    import io, wave
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
        w.writeframes((np.clip(pcm, -1, 1) * 32767).astype("<i2").tobytes())
    return buf.getvalue()


import sqlite3
con = sqlite3.connect(DB)
for cid in CASES:
    row = con.execute("select text, audio from content where id=?", (cid,)).fetchone()
    text, audio = row
    b64 = base64.b64encode(wav_bytes(to16("out/local/" + audio))).decode()
    body = json.dumps({"text": text, "audioBase64": b64}).encode()
    req = urllib.request.Request(API, data=body, headers={"Content-Type": "application/json", "User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=120, context=CTX) as r:
        d = json.load(r)
    print(f"{cid:26s} score={d.get('score'):3d} acc={d.get('accuracy'):3d} level={d.get('level'):8s} :: {d.get('transcription')}")
