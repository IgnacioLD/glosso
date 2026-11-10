import os
import numpy as np
import soundfile as sf
from kokoro import KPipeline

TEXT = "I think my brother is thirsty."
VOICES = [
    "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore",
    "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky",
    "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael",
    "am_onyx", "am_puck", "am_santa",
    "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
    "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
]

os.makedirs("out/voices_all", exist_ok=True)
pipes = {"a": KPipeline(lang_code="a"), "b": KPipeline(lang_code="b")}

for voice in VOICES:
    pipe = pipes["b" if voice.startswith("b") else "a"]
    chunks = [a for _, _, a in pipe(TEXT, voice=voice)]
    audio = np.concatenate([c.detach().cpu().numpy() if hasattr(c, "detach") else np.asarray(c) for c in chunks])
    sf.write(f"out/voices_all/{voice}.wav", audio, 24000)
    print(f"{voice:12s} {len(audio)/24000:.2f}s")
