import os
import numpy as np
import soundfile as sf
from kokoro import KPipeline

TEXT = "I think my brother is thirsty."
VOICES = ["af_heart", "af_bella", "af_nicole", "am_michael", "am_adam", "bf_emma", "bm_george"]

os.makedirs("out/voices", exist_ok=True)
pipeline = KPipeline(lang_code="a")
for voice in VOICES:
    chunks = [a for _, _, a in pipeline(TEXT, voice=voice)]
    audio = np.concatenate([c.detach().cpu().numpy() if hasattr(c, "detach") else np.asarray(c) for c in chunks])
    path = f"out/voices/{voice}.wav"
    sf.write(path, audio, 24000)
    print(f"{voice:12s} {len(audio)/24000:.2f}s  {path}")
