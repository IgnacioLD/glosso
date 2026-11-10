import sys
import soundfile as sf
from kokoro import KPipeline

TEXTS = [
    "I think my brother is thirsty.",
    "The cat sat on the mat.",
]
VOICE = "af_heart"

pipeline = KPipeline(lang_code="a")
for n, text in enumerate(TEXTS):
    out = f"out/test{n}.wav"
    chunks = []
    rate = None
    for _, _, audio in pipeline(text, voice=VOICE):
        chunks.append(audio)
        rate = 24000
    import numpy as np
    full = np.concatenate([c.detach().cpu().numpy() if hasattr(c, "detach") else np.asarray(c) for c in chunks])
    sf.write(out, full, rate)
    print(f"{out}: {len(full)/rate:.2f}s  {text}")
