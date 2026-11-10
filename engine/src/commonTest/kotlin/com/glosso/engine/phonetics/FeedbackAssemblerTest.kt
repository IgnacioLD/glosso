package com.glosso.engine.phonetics

import com.glosso.engine.model.MatchStatusModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackAssemblerTest {

    private fun assemble(text: String, expected: String, actual: String) =
        FeedbackAssembler.assemble(
            text = text,
            expectedIpa = expected,
            result = PhoneticComparator.calculateScoringResult(text, expected, actual, "en"),
        )

    @Test
    fun `an error points at the word it was heard in`() {
        val feedback = assemble(
            text = "I think it is the third thing",
            expected = "aɪ θɪŋk ɪt ɪz ðə θɜːrd θɪŋ",
            actual = "aɪ sɪŋk ɪt ɪz ðə tɜːrd θɪŋ",
        )

        val byWord = feedback.errors.associateBy { it.wordIndex }
        assertTrue(byWord.containsKey(1), "the TH in think is word 1, was ${feedback.errors}")
        assertTrue(byWord.containsKey(5), "the TH in third is word 5, was ${feedback.errors}")
    }

    @Test
    fun `each word carries the status of its own characters`() {
        val feedback = assemble(
            text = "think it",
            expected = "θɪŋk ɪt",
            actual = "sɪŋk ɪt",
        )

        val think = feedback.words.first { it.text == "think" }
        val wordIt = feedback.words.first { it.text == "it" }
        assertEquals(5, think.letters.size)
        assertEquals(2, wordIt.letters.size)
        assertTrue(
            think.letters.any { it.status != MatchStatusModel.PERFECT },
            "the slipped TH must be marked inside think, was ${think.letters}",
        )
        assertTrue(
            wordIt.letters.all { it.status == MatchStatusModel.PERFECT },
            "an untouched word stays clean, was ${wordIt.letters}",
        )
    }
}
