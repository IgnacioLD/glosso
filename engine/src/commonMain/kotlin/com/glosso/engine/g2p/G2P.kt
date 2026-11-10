package com.glosso.engine.g2p

/**
 * Grapheme-to-phoneme conversion: text in, word-separated IPA out.
 *
 * The generated IPA must use the same phoneme inventory the acoustic model emits,
 * so the reference and the recognized transcription can be compared. Mappings are
 * provisional until validated against the model vocabulary (F1).
 */
interface G2P {
    /** Returns space-separated IPA with one token per input word. */
    fun phonemize(text: String): String

    /**
     * Like [phonemize] but also returns every acceptable pronunciation per source
     * word. A dictionary can list a word under more than one accent (CMUdict has
     * both "thirty TH ER1 D IY2" and "thirty(2) TH ER1 T IY2"), and a scorer that
     * only keeps the first penalises the other dialect for pronouncing correctly.
     *
     * [Pronunciations.canonical] is exactly [phonemize]; [wordVariants] is parallel
     * to the space-separated source words, the first variant being the canonical
     * one. Sources without alternatives return an empty list, so callers that do
     * not care about variants can keep using [phonemize].
     */
    fun pronunciations(text: String): Pronunciations =
        Pronunciations(phonemize(text), emptyList())
}

/** Reference IPA plus, per source word, the pronunciations that count as correct. */
data class Pronunciations(
    val canonical: String,
    val wordVariants: List<List<String>>,
)

/**
 * Widens a client-provided reference with the alternate readings of a dictionary:
 * each provided word stays first and every word with more than one dictionary
 * reading gains the extras, so a scorer accepts either. Words with a single
 * reading are left empty and the scorer falls back to the provided word for them,
 * which keeps the published form small. A word count mismatch means the two
 * references are not aligned, so the provided one is kept alone (an empty result
 * means "no widening").
 *
 * This is the single source of truth for the rule: the cloud scores with it, the
 * content publisher bakes its result into the drill, and the device scores against
 * that; the CMUdict it widens from does not ship to the phone.
 */
fun widenReferenceVariants(
    providedIpa: String,
    dictionaryVariants: List<List<String>>,
): List<List<String>> {
    val words = providedIpa.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.size != dictionaryVariants.size) return emptyList()
    return words.indices.map { i ->
        val alternates = dictionaryVariants[i]
        if (alternates.size > 1) (listOf(words[i]) + alternates).distinct() else emptyList()
    }
}
