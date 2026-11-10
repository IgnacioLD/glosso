import base64
import io
import json
import ssl
import urllib.request
import wave

import certifi
import numpy as np
import soundfile as sf
from kokoro import KPipeline

API = "https://api.glossostudio.com/v1/phonemize"
CTX = ssl.create_default_context(cafile=certifi.where())
SENTENCES = ["I think the path goes north.", "My dad had a bad nap.", "The cat sat on the mat."]


def to16(data, sr):
    data = np.asarray(data, dtype=np.float32)
    if data.ndim > 1:
        data = data.mean(axis=1)
    n = int(len(data) * 16000 / sr)
    return np.interp(np.linspace(0, len(data), n, endpoint=False), np.arange(len(data)), data)


def wav_bytes(pcm):
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes((np.clip(pcm, -1, 1) * 32767).astype("<i2").tobytes())
    return buf.getvalue()


def phonemize(pcm):
    body = json.dumps({"audioBase64": base64.b64encode(wav_bytes(pcm)).decode()}).encode()
    req = urllib.request.Request(API, data=body, headers={"Content-Type": "application/json", "User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=120, context=CTX) as r:
        return json.load(r)["ipa"]


pipe = KPipeline(lang_code="a")
for i, s in enumerate(SENTENCES):
    audio = np.concatenate([a.detach().cpu().numpy() if hasattr(a, "detach") else np.asarray(a) for _, _, a in pipe(s, voice="af_kore")])
    sf.write(f"out/diag/kokoro{i}.wav", audio, 24000)
    print(f"\n{s}\n  kokoro -> {phonemize(to16(audio, 24000))}")
