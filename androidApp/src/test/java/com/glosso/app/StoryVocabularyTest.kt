package com.glosso.app

import com.glosso.engine.model.WordModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides which words of a reading are vocabulary. It reuses the
 * WORD bank, so this is where "the story's words are the theme's words" is
 * pinned down without a device.
 */
class StoryVocabularyTest {

    private fun word(lemma: String, id: String = lemma) = WordModel(
        id = id,
        lemma = lemma,
        ipa = "ipa",
        translations = mapOf("es" to "significado"),
    )

    private val vocabulary = StoryVocabulary.index(
        listOf(word("cup"), word("candle"), word("borrow"), word("make"), word("dish"), word("sorry")),
    )

    @Test
    fun `index keys by the normalized lemma`() {
        assertEquals(setOf("cup", "candle", "borrow", "make", "dish", "sorry"), vocabulary.keys)
    }

    @Test
    fun `a token matches its lemma ignoring case and punctuation`() {
        assertEquals("cup", StoryVocabulary.match("Cup,", vocabulary)?.lemma)
        assertEquals("sorry", StoryVocabulary.match("\u2018sorry\u2019", vocabulary)?.lemma)
        assertNull(StoryVocabulary.match("table", vocabulary))
    }

    @Test
    fun `common inflections resolve to their lemma`() {
        assertEquals("cup", StoryVocabulary.match("cups", vocabulary)?.lemma)
        assertEquals("candle", StoryVocabulary.match("candles", vocabulary)?.lemma)
        assertEquals("borrow", StoryVocabulary.match("borrowed", vocabulary)?.lemma)
        assertEquals("make", StoryVocabulary.match("making", vocabulary)?.lemma)
        assertEquals("dish", StoryVocabulary.match("dishes", vocabulary)?.lemma)
    }

    @Test
    fun `an unknown token never matches`() {
        assertNull(StoryVocabulary.match("running", vocabulary))
        assertNull(StoryVocabulary.match("", vocabulary))
    }

    @Test
    fun `no vocabulary yields no spans`() {
        assertTrue(StoryVocabulary.spans("A cup and a table.", emptyMap()).isEmpty())
    }

    @Test
    fun `spans point at the exact token in the paragraph`() {
        val paragraph = "The cups are on the table, and one cup fell."
        val spans = StoryVocabulary.spans(paragraph, vocabulary)

        assertEquals(listOf("cups", "cup"), spans.map { it.text })
        spans.forEach { span ->
            assertEquals(span.text, paragraph.substring(span.start, span.end))
        }
        assertEquals("cup", spans.first().word.lemma)
    }
}
