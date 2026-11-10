"""CMUdict (ARPAbet) -> IPA, mirroring `engine/.../g2p/Arpabet.kt`.

The reference IPA in `content.db` was produced by that Kotlin table, so to map a
probe's reference token back to the word that produced it we have to spell the
IPA exactly the same way. Keep this in sync with Arpabet.kt; the probe remap
reports its coverage so a drift shows up as unmatched tokens instead of silently
changing the measurement.

Only used by the authoring/measurement tooling, never by the product.
"""

BASE_TO_IPA = {
    "AA": "ɑ", "AE": "æ", "AH": "ʌ", "AO": "ɔ", "AW": "aʊ", "AY": "aɪ",
    "B": "b", "CH": "tʃ", "D": "d", "DH": "ð", "EH": "ɛ", "ER": "ɝ",
    "EY": "eɪ", "F": "f", "G": "ɡ", "HH": "h", "IH": "ɪ", "IY": "i",
    "JH": "dʒ", "K": "k", "L": "l", "M": "m", "N": "n", "NG": "ŋ",
    "OW": "oʊ", "OY": "ɔɪ", "P": "p", "R": "ɹ", "S": "s", "SH": "ʃ",
    "T": "t", "TH": "θ", "UH": "ʊ", "UW": "u", "V": "v", "W": "w",
    "Y": "j", "Z": "z", "ZH": "ʒ",
}


def phoneme(token):
    upper = token.upper()
    stress = upper[-1] if upper and upper[-1].isdigit() else None
    base = upper[:-1] if stress else upper
    if base == "AH" and stress == "0":
        ipa = "ə"
    elif base == "ER" and stress == "0":
        ipa = "ɚ"
    else:
        ipa = BASE_TO_IPA.get(base)
        if ipa is None:
            return None
    if stress == "1":
        return "ˈ" + ipa
    if stress == "2":
        return "ˌ" + ipa
    return ipa


def word(arpabet):
    return "".join(p for p in (phoneme(t) for t in arpabet.split()) if p)


def read_entries(path):
    entries = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith(";;;") or not line.strip():
                continue
            body = line.split("#")[0].strip()
            parts = body.split()
            if len(parts) < 2:
                continue
            base = parts[0].split("(")[0].lower()
            entries.setdefault(base, []).append(" ".join(parts[1:]))
    return entries


def primary_ipa_to_word(path, frequent=None):
    """Primary-reading IPA -> the most likely word CMUdict spells it for.

    Homophones are common in CMUdict and the rarest spelling is not the one the
    source text used: `tˈu` also matches "thuy" (espeak reads it /θaɪ/), `fˈild`
    also matches the surname "feild" (espeak reads /feɪld/). Re-phonemizing those
    would inject a wrong sound. When a frequency list is given, words in it win;
    otherwise the shortest, apostrophe-free, alphabetical spelling does.
    """
    entries = read_entries(path)
    candidates = {}
    for w in entries:
        ipa = word(entries[w][0])
        if ipa:
            candidates.setdefault(ipa, []).append(w)

    def rank(words):
        if frequent:
            return min(words, key=lambda w: (w not in frequent, "'" in w, len(w), w))
        return min(words, key=lambda w: ("'" in w, len(w), w))

    return {ipa: rank(words) for ipa, words in candidates.items()}
