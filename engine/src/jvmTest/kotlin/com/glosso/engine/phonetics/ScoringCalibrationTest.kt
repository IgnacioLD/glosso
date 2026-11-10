package com.glosso.engine.phonetics

import com.glosso.engine.model.MasteryLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Deterministic calibration harness for the pronunciation scorer.
 *
 * The bug it guards against: a learner speaking English with a strong Spanish
 * accent used to score 98/100 and be told MASTERED even though whole classes of
 * sounds were wrong (IH said as EE, AA said as Spanish A, UH said as OO). Three
 * scorer-only indulgences caused it, none of which the reference implementation
 * has: confidence scaling of substitution penalties, demoting an unsure miss to
 * CLOSE, and a similarity matrix that counted distinct vowels as perfect because
 * it still mixed two IPA symbol conventions.
 *
 * The cases are synthetic (expected vs. produced IPA), so they are exact and run
 * everywhere; [OnDeviceCalibrationTest] reproduces the same contrast with the real
 * wav2vec2 model and Kokoro speech.
 */
class ScoringCalibrationTest {

    private data class Case(
        val name: String,
        val expected: String,
        val actual: String,
        val language: String = "en",
        val confidence: List<Float> = emptyList(),
        /** Exact score the case must produce, so a regression fails the build. */
        val score: Int,
        /** Exact level the case must produce. */
        val level: MasteryLevel,
    ) {
        val mastered: Boolean
            get() = level == MasteryLevel.PERFECT || level == MasteryLevel.MASTERED
    }

    private fun score(case: Case): ScoringResult =
        PhoneticComparator.calculateScoringResult(
            text = case.name,
            expected = case.expected,
            actual = case.actual,
            language = case.language,
            actualConfidence = case.confidence,
        )

    private val cases = listOf(
        // A native-like take must stay flawless.
        Case("perfect-sentence", "ðə kæt sæt ɒn ðə mæt", "ðə kæt sæt ɒn ðə mæt", score = 100, level = MasteryLevel.PERFECT),

        // Vowel quality: the classic Spanish-speaker substitutions. Each is a real
        // minimal pair, so it must not be counted as the reference sound.
        Case("vowel-IH-as-EE", "sɪt", "sit", score = 90, level = MasteryLevel.ALMOST),
        Case("vowel-AA-as-EH", "bæd", "bɛd", score = 67, level = MasteryLevel.ROUGH),
        Case("vowel-AA-as-A", "bæt", "bat", score = 87, level = MasteryLevel.ALMOST),
        Case("vowel-UH-as-OO", "fʊl", "ful", score = 90, level = MasteryLevel.ALMOST),

        // Consonant substitutions the model is *unsure* about: acoustic confidence
        // must not buy them out of the penalty.
        Case("theta-as-s-unsure", "θɪŋk", "sɪŋk", confidence = listOf(0.15f, 1f, 1f, 1f), score = 75, level = MasteryLevel.ALMOST),
        Case("eth-as-d-unsure", "ðɪs", "dɪs", confidence = listOf(0.15f, 1f, 1f), score = 67, level = MasteryLevel.ROUGH),
        Case("v-as-b-unsure", "væn", "bæn", confidence = listOf(0.15f, 1f, 1f), score = 67, level = MasteryLevel.ROUGH),

        // Alignment errors: a dropped word and an added word must cost.
        Case("dropped-word", "ðə kæt sæt ɒn ðə mæt", "ðə kæt ɒn ðə mæt", score = 80, level = MasteryLevel.ALMOST),
        Case("extra-word", "kæt", "kæt dɒɡ", score = 65, level = MasteryLevel.ROUGH),

        // Spanish epenthesis: an extra vowel before an s+consonant cluster
        // ("eschool" for "school"). This was the reviewer's case: the added vowel
        // lived only in the edit cost, so the word stayed PERFECT and the take
        // MASTERED at 86. Now the insertion is an alignment entry, breaks the word
        // and blocks mastery while the score legitimately stays high.
        Case("epenthesis-vowel", "skuːl", "eskuːl", score = 86, level = MasteryLevel.ALMOST),

        // A split/doubling of a real phone is segmentation, not epenthesis: the
        // native "brother is" take heard an extra linking ɹ beside the existing
        // one. Tolerated, so a native speaker is not punished for the decoder; the
        // doubled ɹ costs nothing, and the word stays MASTERED (not PERFECT: an
        // extra sound was still produced).
        Case("doubled-rhotic", "brʌðər ɪz", "brʌðərɹɪz", score = 100, level = MasteryLevel.MASTERED),

        // The report, reproduced: a mild accent that only shifts underlying vowels
        // (IH->EE, STRUT->schwa) and leaves the consonants mostly right. This used
        // to be a clean 100 PERFECT MASTERED; the score legitimately stays high
        // because most sounds are right, but the level must reflect the two wrong
        // phonemes and refuse mastery.
        Case("accented-mild-sentence", "aɪ θɪŋk maɪ brʌðər ɪz θɜːsti", "aɪ θiŋk maɪ brəðər ɪz θɜːsti", score = 98, level = MasteryLevel.ALMOST),

        // A heavier accented take the model heard poorly, with low confidence on
        // the substituted phones.
        Case(
            "accented-strong-sentence",
            "ˈaɪ θˈɪŋk mˈaɪ bɹˈʌðɚ ˈɪz θˈɝsti",
            "aɪ tɪŋk maɪ bɾadəɾ ɪs tɜːsti",
            confidence = listOf(
                1f, 1f, 0.2f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 0.2f, 0.2f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f,
            ),
            score = 77,
            level = MasteryLevel.ALMOST,
        ),
    )

    @Test
    fun `every calibration case produces the expected score, level and mastery`() {
        for (case in cases) {
            val result = score(case)
            assertEquals(case.score, result.score, "${case.name} score")
            assertEquals(case.level, result.level, "${case.name} level")
            assertEquals(case.mastered, result.isMastery, "${case.name} mastery")
        }
    }

    @Test
    fun `an inserted phone is a visible alignment entry`() {
        // Regression: insertions were dropped by the traceback, so an added vowel
        // could not touch the word result, the accuracy or mastery.
        val result = score(cases.single { it.name == "epenthesis-vowel" })
        assertTrue(
            result.alignment.any { it.expected == "-" && it.actual == "e" },
            "the epenthetic 'e' must appear in the alignment, was ${result.alignment}",
        )
        assertFalse(result.isMastery, "epenthesis must block mastery")
    }

    @Test
    fun `an inserted phone drags accuracy below a clean take`() {
        val result = score(cases.single { it.name == "epenthesis-vowel" })
        assertTrue(result.score < 100, "extra sounds must reduce the score, got ${result.score}")
        assertTrue(result.accuracy < 100, "the added phone must lower accuracy, got ${result.accuracy}")
    }

    @Test
    fun `a documented allophone does not block mastery`() {
        // Regression: a CLOSE match used to be a hard error unconditionally, which
        // would have flagged /ɪ/->/j/, /ʊ/->/w/ and the glottalised /d/ as wrong
        // sounds even though the matrix and comments call them allophones.
        val allophones = listOf(
            "sɪt" to "sjt", // diphthong onset glide
            "fʊl" to "fwl", // /ʊ/ ~ /w/
            "bæd" to "bæʔ", // glottalised /d/, symmetric with /t/
            "bæt" to "bæʔ",
            "bæt" to "bæɾ", // flap
        )
        for ((reference, produced) in allophones) {
            val result = PhoneticComparator.calculateScoringResult("x", reference, produced)
            assertTrue(
                result.isMastery,
                "/$reference/ said as /$produced/ is an allophone and must not block mastery " +
                    "(level ${result.level}, score ${result.score})",
            )
        }
    }

    @Test
    fun `a distinct vowel is never a perfect match`() {
        // The report's core defect: these were PERFECT because the matrix carried
        // symbol-convention values at or above the perfect threshold.
        val pairs = listOf("sɪt" to "sit", "fʊl" to "ful", "bæt" to "bat")
        for ((expected, actual) in pairs) {
            val result = PhoneticComparator.calculateScoringResult("x", expected, actual)
            assertTrue(
                result.alignment.any { it.status != MatchStatus.PERFECT },
                "$expected vs $actual must not be all-perfect",
            )
        }
    }

    @Test
    fun `the same substitution costs the same however unsure the model is`() {
        val sure = PhoneticComparator.calculateScoringResult(
            "think", "θɪŋk", "sɪŋk", actualConfidence = listOf(1f, 1f, 1f, 1f),
        )
        val unsure = PhoneticComparator.calculateScoringResult(
            "think", "θɪŋk", "sɪŋk", actualConfidence = listOf(0f, 1f, 1f, 1f),
        )
        assertEquals(sure.score, unsure.score)
    }

    @Test
    fun `an extra word is not reported as a perfect take`() {
        val result = score(cases.single { it.name == "extra-word" })
        assertFalse(result.isMastery)
        assertFalse(result.level == MasteryLevel.PERFECT)
        assertTrue(result.score < 100, "extra sounds must reduce the score, got ${result.score}")
    }

    @Test
    fun `prints the calibration table`() {
        println("CALIBRATION|case|score|level|mastery|accuracy|completeness|errors")
        for (case in cases) {
            val r = score(case)
            val errors = ErrorRules.analyze(r.alignment, case.language)
                .joinToString(",") { "${it.phoneme}->${it.actual}" }
            println(
                "CALIBRATION|${case.name}|${r.score}|${r.level}|${r.isMastery}|" +
                    "${r.accuracy}|${r.completeness}|$errors",
            )
        }
    }
}
