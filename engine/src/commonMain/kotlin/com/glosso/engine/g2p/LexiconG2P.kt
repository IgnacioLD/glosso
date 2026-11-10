package com.glosso.engine.g2p

/**
 * G2P backed by a per-language word -> IPA lexicon (`word<whitespace>ipa`), one
 * IPA token per word, built at publish time (see `tools/content/phonemize.py`).
 *
 * Unlike [CmuDictG2P] the IPA is stored directly, not ARPAbet, so it works for
 * any language whose reference IPA is produced by an offline phonemizer. Words
 * that are not in the lexicon pass through unchanged, so the output always keeps
 * one token per source word and word alignment in the scorer stays intact.
 */
class LexiconG2P(
    private val entries: Map<String, String>,
) : G2P {

    override fun phonemize(text: String): String = text
        .trim()
        .split(Regex("\\s+"))
        // A token with no letter or digit (a spaced dash) is punctuation, not a word.
        .filter { token -> token.any { it.isLetterOrDigit() } }
        .joinToString(" ") { rawWord ->
            val word = normalize(rawWord)
            val ipa = if (word.isEmpty()) "" else entries[word]
            ipa ?: word
        }

    /**
     * Lowercases and strips surrounding punctuation, keeping internal marks. The
     * combining acute (U+0301) is dropped: Russian content writes the stress with
     * it as a reading aid, and the lexicon is keyed by the plain word.
     */
    private fun normalize(rawWord: String): String {
        val word = rawWord.lowercase().replace('\u2019', '\'').replace(STRESS_MARK, "")
        return word.trim { !it.isLetter() && it != '\'' && it != '-' }
    }

    companion object {
        private const val STRESS_MARK = "\u0301"

        /** Parses `word ipa` lines, skipping comments and malformed rows. */
        fun fromLines(lines: Iterable<String>): LexiconG2P {
            val entries = HashMap<String, String>()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";;;")) continue
                val separator = trimmed.indexOfFirst { it.isWhitespace() }
                if (separator <= 0) continue
                val word = trimmed.substring(0, separator).lowercase()
                val ipa = trimmed.substring(separator + 1).trim()
                if (word.isNotEmpty() && ipa.isNotEmpty()) entries.getOrPut(word) { ipa }
            }
            return LexiconG2P(entries)
        }
    }
}
