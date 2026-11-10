package com.glosso.app

import com.glosso.engine.model.WordModel

/**
 * The vocabulary a story carries, and the rule that decides which words of a
 * paragraph are that vocabulary. It reuses the WORD bank of the story's theme
 * (the same `WordModel` the practice step shows) instead of duplicating glosses
 * into the story, so a word that is tappable in a reading is tappable in a
 * lesson too, with the same IPA, meaning and audio.
 *
 * Pure on purpose: the reader only draws what this returns, so the matching can
 * be driven from a plain unit test.
 */
object StoryVocabulary {

    /** A run of letters/digits with inner apostrophes or hyphens, no outer punctuation. */
    private val WORD = Regex("[\\p{L}\\p{N}]+(?:['\u2019\\-][\\p{L}\\p{N}]+)*")

    /**
     * Indexes the story's theme vocabulary by lemma, so a token resolves in one
     * lookup. Blank lemmas are dropped.
     */
    fun index(words: List<WordModel>): Map<String, WordModel> =
        words.associateBy { normalize(it.lemma) }.filterKeys { it.isNotEmpty() }

    /**
     * The vocabulary word a text token names, or null when the token is not part
     * of the story's vocabulary. Inflections match their lemma ("candles" ->
     * "candle", "borrowed" -> "borrow") so the text does not have to be
     * lemmatised to be read.
     */
    fun match(token: String, vocabulary: Map<String, WordModel>): WordModel? {
        if (vocabulary.isEmpty()) return null
        for (candidate in candidates(normalize(token))) {
            vocabulary[candidate]?.let { return it }
        }
        return null
    }

    /**
     * Every vocabulary word of [paragraph], in reading order, with the character
     * range of the token it came from. The reader links exactly these ranges.
     */
    fun spans(paragraph: String, vocabulary: Map<String, WordModel>): List<WordSpan> {
        if (vocabulary.isEmpty()) return emptyList()
        return WORD.findAll(paragraph).mapNotNull { match ->
            val word = match(match.value, vocabulary) ?: return@mapNotNull null
            WordSpan(match.range.first, match.range.last + 1, match.value, word)
        }.toList()
    }

    /** Lowercased, stripped of surrounding punctuation, inner `'` and `-` kept. */
    fun normalize(raw: String): String =
        raw.lowercase()
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .trim { !it.isLetterOrDigit() }

    /**
     * The token plus its conservative de-inflections, so a match is only ever a
     * real lemma. Order matters: the exact token, then the shorter stems.
     */
    private fun candidates(token: String): List<String> {
        val out = LinkedHashSet<String>()
        out += token
        if (token.length > 3) {
            if (token.endsWith("ies")) out += token.dropLast(3) + "y"
            if (token.endsWith("es")) out += token.dropLast(2)
            if (token.endsWith("s")) out += token.dropLast(1)
            if (token.endsWith("ing")) {
                out += token.dropLast(3)
                out += token.dropLast(3) + "e"
            }
            if (token.endsWith("ed")) {
                out += token.dropLast(2)
                out += token.dropLast(2) + "e"
                out += token.dropLast(1)
            }
            if (token.endsWith("ly")) out += token.dropLast(2)
        }
        return out.toList()
    }
}

/** A vocabulary token inside a paragraph: its range, its surface form and its word. */
data class WordSpan(val start: Int, val end: Int, val text: String, val word: WordModel)
