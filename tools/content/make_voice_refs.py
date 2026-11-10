"""Build the reference clips that the Chatterbox backend clones.

Every clip is rendered by Kokoro (Apache-2.0) with text in the VOICE'S OWN
language, so a reference is never generated from the wrong language. The output
is fully synthetic: no real person's voice is used.

Run (needs kokoro + soundfile + numpy):
    uv run --python 3.11 --with kokoro --with soundfile --with numpy \
        python tools/content/make_voice_refs.py
"""

import numpy as np, soundfile as sf, os
from kokoro import KPipeline

# One paragraph per voice language, long enough (~18 s) to clone from.
TEXTS = {
    "en": ("I usually walk to the office, but today the rain caught me halfway. "
           "Do you want to share a taxi? Honestly, I would rather wait under this awning and watch the street. "
           "What a morning! The city sounds different when it rains, softer somehow, "
           "and the lights on the pavement look almost golden. Let me check the time. "
           "Right, we still have twenty minutes before the meeting starts."),
    # French is not offered in the app (LanguagePacks.offered is English only),
    # so voices/ff_siwis.wav is not committed. It used to be generated from the
    # English paragraph above, which cloned a French voice reading English; if
    # French is ever enabled, regenerate the reference with this French text
    # first (the loop below already picks the text by the voice's language).
    "f": ("Je vais d'habitude au bureau à pied, mais aujourd'hui la pluie m'a surpris à mi-chemin. "
          "Tu veux partager un taxi ? Franchement, je préfère attendre sous cet auvent et regarder la rue. "
          "Quelle matinée ! La ville sonne autrement quand il pleut, plus doucement en quelque sorte, "
          "et les lumières sur le trottoir paraissent presque dorées. Laisse-moi vérifier l'heure. "
          "Bon, nous avons encore vingt minutes avant le début de la réunion."),
}

VOICES = {"en-US-female": "af_kore", "en-US-male": "am_puck",
          "en-GB-female": "bf_lily", "en-GB-male": "bm_fable",
          "fr-FR-female": "ff_siwis"}

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "voices")
os.makedirs(OUT, exist_ok=True)

pipes = {}
for key, vid in VOICES.items():
    lang = vid[0]
    if lang not in TEXTS:
        raise SystemExit(f"no reference text for voice '{vid}' (language '{lang}')")
    if lang not in pipes:
        pipes[lang] = KPipeline(lang_code=lang)
    chunks = [a.detach().cpu().numpy() if hasattr(a, "detach") else np.asarray(a)
              for _, _, a in pipes[lang](TEXTS[lang], voice=vid)]
    audio = np.concatenate(chunks)
    out = f"{OUT}/{vid}.wav"
    sf.write(out, audio, 24000)
    print(f"{key:14s} {vid:10s} {len(audio)/24000:5.1f}s  {out}")
