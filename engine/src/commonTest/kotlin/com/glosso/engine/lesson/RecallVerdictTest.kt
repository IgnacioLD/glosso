package com.glosso.engine.lesson

import com.glosso.engine.model.MatchStatusModel
import com.glosso.engine.model.PhonemeMatchModel
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.engine.phonetics.FeedbackAssembler
import com.glosso.engine.phonetics.PhoneticComparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecallVerdictTest {

    private fun feedback(perfect: Int, close: Int, missed: Int, extra: Int = 0) = PronunciationFeedback(
        alignment = List(perfect) { PhonemeMatchModel("a", "a", MatchStatusModel.PERFECT) } +
            List(close) { PhonemeMatchModel("a", "ɑ", MatchStatusModel.CLOSE) } +
            List(missed) { PhonemeMatchModel("a", "", MatchStatusModel.MISSED) } +
            List(extra) { PhonemeMatchModel("", "s", MatchStatusModel.MISSED) },
    )

    @Test
    fun `most of the phrase heard is a recalled phrase - close sounds included`() {
        assertEquals(RecallVerdict.RECALLED, RecallVerdict.of(feedback(perfect = 6, close = 2, missed = 2)))
    }

    @Test
    fun `half of the phrase is partial and little of it is another phrase`() {
        assertEquals(RecallVerdict.PARTIAL, RecallVerdict.of(feedback(perfect = 5, close = 0, missed = 5)))
        assertEquals(RecallVerdict.NOT_THAT_PHRASE, RecallVerdict.of(feedback(perfect = 2, close = 1, missed = 7)))
        assertEquals(RecallVerdict.NOT_THAT_PHRASE, RecallVerdict.of(PronunciationFeedback()))
    }

    @Test
    fun `sounds added around the phrase do not turn it into another phrase`() {
        assertEquals(RecallVerdict.RECALLED, RecallVerdict.of(feedback(perfect = 8, close = 0, missed = 0, extra = 6)))
    }

    @Test
    fun `only a phrase that was at least partly said judges pronunciation`() {
        assertTrue(RecallVerdict.RECALLED.judgesPronunciation)
        assertTrue(RecallVerdict.PARTIAL.judgesPronunciation)
        assertFalse(RecallVerdict.NOT_THAT_PHRASE.judgesPronunciation)
    }

    @Test
    fun `saying another line of the dialogue is not that phrase and the line itself is recalled`() {
        val text = "See you at six."
        val expected = "sˈi jˈu ˈæt sˈɪks"
        fun take(actual: String) = FeedbackAssembler.assemble(
            text = text,
            expectedIpa = expected,
            result = PhoneticComparator.calculateScoringResult(text, expected, actual, "en"),
            targetLanguage = "en",
        )

        assertEquals(RecallVerdict.RECALLED, RecallVerdict.of(take("sijuætsɪks")))
        assertEquals(RecallVerdict.NOT_THAT_PHRASE, RecallVerdict.of(take("aɪmfaɪnθæŋks")))
    }
}
