package com.glosso.engine.g2p

/**
 * English G2P backed by a CMUdict-format pronunciation dictionary
 * (`word  P R O N U N C I A T I O N`).
 *
 * The dictionary file is **not** bundled in git (size); it is hosted on the VPS
 * alongside the model and injected at runtime with [fromLines]. CMUdict is
 * BSD-2-Clause, which is permissive with attribution.
 *
 * CMUdict is a US dictionary, but it lists alternate pronunciations for many
 * words, some of which are the British form: `thirty` is `TH ER1 D IY2` (the
 * American flap) with `thirty(2) TH ER1 T IY2` (the British /t/), `tomato` has
 * the British `AA1`. Keeping only the primary pronunciation ([phonemize]) would
 * make the scorer tell a British speaker their correct /t/ is a missing /d/.
 * [pronunciations] therefore keeps every variant so the scorer can accept any of
 * them; see [com.glosso.engine.phonetics.PhoneticComparator.calculateScoringResult].
 *
 * Unknown words are passed through unchanged rather than dropped, so the IPA keeps
 * one token per source word and word alignment in the scorer stays intact.
 */
class CmuDictG2P(
    private val entries: Map<String, List<String>>,
) : G2P {

    override fun phonemize(text: String): String = pronunciations(text).canonical

    override fun pronunciations(text: String): Pronunciations {
        val source = text
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val canonical = ArrayList<String>(source.size)
        val variants = ArrayList<List<String>>(source.size)
        for (rawWord in source) {
            val word = normalize(rawWord)
            val arpabet = if (word.isEmpty()) emptyList() else entries[word].orEmpty()
            if (arpabet.isEmpty()) {
                canonical.add(word)
                variants.add(listOf(word))
                continue
            }
            canonical.add(Arpabet.word(arpabet.first()))
            // Distinct IPA only: CMUdict sometimes repeats the same reading.
            variants.add(arpabet.map { Arpabet.word(it) }.distinct())
        }
        return Pronunciations(canonical.joinToString(" "), variants)
    }

    /** Lowercases and strips surrounding punctuation, keeping internal apostrophes. */
    private fun normalize(rawWord: String): String =
        rawWord.lowercase().trim { !it.isLetter() && it != '\'' }

    companion object {
        /** Parses CMUdict lines, skipping comments and malformed rows. */
        fun fromLines(lines: Iterable<String>): CmuDictG2P {
            val entries = HashMap<String, MutableList<String>>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith(";;;")) continue
                val hash = line.indexOf('#')
                val body = if (hash >= 0) line.substring(0, hash) else line
                val parts = body.trim().split(Regex("\\s+"))
                if (parts.size < 2) continue
                val word = parts[0]
                // Alternate pronunciations are written "word(2)"; index the base word.
                val base = word.substringBefore('(').lowercase()
                val arpabet = parts.drop(1).joinToString(" ")
                val list = entries.getOrPut(base) { mutableListOf() }
                if (arpabet !in list) list.add(arpabet)
            }
            return CmuDictG2P(entries)
        }
    }
}
