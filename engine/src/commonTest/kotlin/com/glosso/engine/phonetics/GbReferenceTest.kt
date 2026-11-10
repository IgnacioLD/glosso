package com.glosso.engine.phonetics

import com.glosso.engine.g2p.CmuDictG2P
import com.glosso.engine.model.MasteryLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The British reference bug: CMUdict is a US dictionary, so "thirty" is
 * `TH ER1 D IY2` (the American flap) and only secondarily `TH ER1 T IY2` (the
 * British /t/). Keeping the primary reading alone made the scorer tell a British
 * speaker their correct /t/ was a missing /d/ (`/d/` at 0 in the feedback).
 *
 * These tests pin both halves: the single-reading scorer reproduces the bug, and
 * the variant-aware scorer accepts the British reading.
 */
class GbReferenceTest {

    private val g2p = CmuDictG2P.fromLines(
        listOf(
            "THE  DH AH0",
            "FLIGHT  F L AY1 T",
            "LEAVES  L IY1 V Z",
            "AT  AE1 T",
            "THREE  TH R IY1",
            "THIRTY  TH ER1 D IY2",
            "THIRTY(2)  TH ER1 T IY2",
            "EITHER  IY1 DH ER0",
            "EITHER(2)  AY1 DH ER0",
            "TOMATO  T AH0 M EY1 T OW2",
            "TOMATO(2)  T AH0 M AA1 T OW2",
            "VASE  V EY1 S",
            "VASE(2)  V AA1 Z",
        ),
    )

    private val phrase = "The flight leaves at three thirty."
    private val britishPhrase = "ðə flaɪt livz æt θɹi θɝti"

    @Test
    fun `single-reading reference charges a british t as a missed american d`() {
        val result = PhoneticComparator.calculateScoringResult(
            text = phrase,
            expected = g2p.phonemize(phrase),
            actual = britishPhrase,
        )
        val thirty = result.alignment.filter { it.expected == "d" || (it.expected == "t" && it.actual == "t") }
        assertTrue(
            result.alignment.any { it.expected == "d" && it.actual == "t" && it.status == MatchStatus.MISSED },
            "the flap d must be the only reading, so the british t reads as MISSED: $thirty",
        )
        assertTrue(result.score < 100)
    }

    @Test
    fun `variant-aware reference accepts the british t in thirty`() {
        val pronunciations = g2p.pronunciations(phrase)
        val result = PhoneticComparator.calculateScoringResult(
            text = phrase,
            expected = pronunciations.canonical,
            actual = britishPhrase,
            expectedWordVariants = pronunciations.wordVariants,
        )
        assertEquals(100, result.score)
        assertTrue(
            result.alignment.none { it.expected == "d" && it.actual == "t" },
            "the british t must not be lined up against an american d",
        )
        assertTrue(result.alignment.none { it.status == MatchStatus.MISSED })
    }

    @Test
    fun `the canonical reading still wins when the learner actually flaps`() {
        val pronunciations = g2p.pronunciations("thirty")
        val result = PhoneticComparator.calculateScoringResult(
            text = "thirty",
            expected = pronunciations.canonical,
            actual = "θɝdi",
            expectedWordVariants = pronunciations.wordVariants,
        )
        assertEquals(100, result.score)
    }

    @Test
    fun `a british reading of either is accepted`() {
        val pronunciations = g2p.pronunciations("either")
        val result = PhoneticComparator.calculateScoringResult(
            text = "either",
            expected = pronunciations.canonical,
            actual = "aɪðɚ",
            expectedWordVariants = pronunciations.wordVariants,
        )
        assertEquals(100, result.score)
    }

    @Test
    fun `the british readings of tomato and vase are not hard errors`() {
        for ((word, british) in listOf("tomato" to "təmɑːtəʊ", "vase" to "vɑːz")) {
            val pronunciations = g2p.pronunciations(word)
            val before = PhoneticComparator.calculateScoringResult(word, pronunciations.canonical, british)
            val after = PhoneticComparator.calculateScoringResult(
                text = word,
                expected = pronunciations.canonical,
                actual = british,
                expectedWordVariants = pronunciations.wordVariants,
            )
            assertTrue(after.score > before.score, "$word should improve: ${before.score} -> ${after.score}")
            assertTrue(
                after.alignment.none { it.status == MatchStatus.MISSED },
                "$word british reading must not contain a missed sound: ${after.alignment}",
            )
        }
    }

    @Test
    fun `an empty variant list keeps the single-reference behaviour`() {
        val single = PhoneticComparator.calculateScoringResult("thirty", "θˈɝdˌi", "θɝti")
        val withEmpty = PhoneticComparator.calculateScoringResult(
            text = "thirty",
            expected = "θˈɝdˌi",
            actual = "θɝti",
            expectedWordVariants = emptyList(),
        )
        assertEquals(single.score, withEmpty.score)
    }

    /**
     * The other flap words in the report. CMUdict spells their American flap with
     * a `T` (`water W AO1 T ER0`), not a `D`, so the single-reading bug never
     * touched them: a British /t/ matches the reference exactly, and the American
     * flap /ɾ/ is already tolerated by the English matrix. They are pinned here so
     * the claim is tested, not asserted in prose.
     */
    @Test
    fun `flap words written with t accept both the british t and the american flap`() {
        val flapWords = mapOf(
            "water" to "wˈɔtɚ",
            "better" to "bˈɛtɚ",
            "city" to "sˈɪti",
            "letter" to "lˈɛtɚ",
            "party" to "pˈɑɹti",
        )
        for ((word, reference) in flapWords) {
            assertTrue("t" in reference, "$word reference should carry the /t/ CMUdict spells")
            val british = PhoneticComparator.calculateScoringResult(word, reference, reference)
            assertEquals(100, british.score, "$word british /t/")

            val americanFlap = PhoneticComparator.calculateScoringResult(word, reference, reference.replace("t", "ɾ"))
            assertEquals(100, americanFlap.score, "$word american flap /ɾ/")
            assertTrue(
                americanFlap.alignment.none { it.status == MatchStatus.MISSED },
                "$word flap must be tolerated: ${americanFlap.alignment}",
            )
        }
    }

    /**
     * The reported production regression: "The bin is near the gate." ships the
     * American CMUdict reference `nˈɪɹ`, and a British (non-rhotic) reading that
     * does not say the `/ɹ/` was charged as MISSED (`/ɹ/` at 0, 92/100). Adding
     * the British reading as one more accepted reference must let it reach
     * 100/PERFECT without ever requiring the American `/ɹ/`.
     */
    private val nearG2p = CmuDictG2P.fromLines(
        listOf(
            "THE  DH AH0",
            "BIN  B IH1 N",
            "IS  IH1 Z",
            "NEAR  N IH1 R",
            "GATE  G EY1 T",
        ),
    )

    private val nearSentence = "The bin is near the gate."
    private val nearReference = "ðə bˈɪn ˈɪz nˈɪɹ ðə ɡˈeɪt"

    @Test
    fun `a british near without the r can reach perfect`() {
        val variants = nearG2p.pronunciations(nearSentence).wordVariants
        for (british in listOf("ðə bɪn ɪz nɪə ðə ɡeɪt", "ðə bɪn ɪz niə ðə ɡeɪt")) {
            val result = PhoneticComparator.calculateScoringResult(
                text = nearSentence,
                expected = nearReference,
                actual = british,
                expectedWordVariants = variants,
            )
            assertEquals(100, result.score, "$british scored ${result.score}")
            assertEquals(MasteryLevel.PERFECT, result.level, "$british")
            assertTrue(
                result.alignment.none { it.status == MatchStatus.MISSED },
                "$british must not miss a sound: ${result.alignment}",
            )
        }
    }

    @Test
    fun `the transcribed british near of the reported sentence is perfect`() {
        // The production en-GB audio actually transcribes as "niə" (no /ɹ/), the
        // exact input that scored 92 before the coda-/ɹ/ widening.
        val variants = nearG2p.pronunciations(nearSentence).wordVariants
        val result = PhoneticComparator.calculateScoringResult(
            text = nearSentence,
            expected = nearReference,
            actual = "ðəbɪnɪzniəðəɡeɪt",
            expectedWordVariants = variants,
        )
        assertEquals(100, result.score)
        assertEquals(MasteryLevel.PERFECT, result.level)
    }

    @Test
    fun `the american rhotic near still passes`() {
        val variants = nearG2p.pronunciations(nearSentence).wordVariants
        val result = PhoneticComparator.calculateScoringResult(
            text = nearSentence,
            expected = nearReference,
            actual = "ðə bɪn ɪz nɪɹ ðə ɡeɪt",
            expectedWordVariants = variants,
        )
        assertEquals(100, result.score)
    }

    @Test
    fun `forgiving a coda r does not forgive a dropped onset r`() {
        // "three" without its onset /ɹ/ is still a real error: the non-rhotic
        // widening only applies to a coda /ɹ/, not to an onset one.
        val expected = g2p.phonemize("three")
        val result = PhoneticComparator.calculateScoringResult("three", expected, "θi")
        assertTrue(result.score < 100, "dropping the onset r must stay an error: ${result.score}")
        assertTrue(
            result.alignment.any { it.expected == "ɹ" && it.status == MatchStatus.MISSED },
            "the onset r must be MISSED: ${result.alignment}",
        )
    }

    @Test
    fun `forgiving a coda r does not forgive an intervocalic r`() {
        // The /ɹ/ of "story" opens the second syllable, so non-rhotic English keeps
        // it. Only a coda /ɹ/ may be silent; a dropped intervocalic /ɹ/ stays a
        // real error instead of being folded away with the preceding vowel.
        for ((word, reference, dropped) in listOf(
            Triple("story", "stˈɔɹi", "stˈɔi"),
            Triple("sorry", "sˈɑɹi", "sˈɑi"),
            Triple("period", "pˈɪɹiəd", "pˈɪiəd"),
        )) {
            val result = PhoneticComparator.calculateScoringResult(word, reference, dropped)
            assertTrue(result.score < 100, "$word dropping the r must stay an error: ${result.score}")
            assertTrue(
                result.alignment.any { it.expected == "ɹ" && it.status == MatchStatus.MISSED },
                "$word intervocalic r must be MISSED: ${result.alignment}",
            )
        }
    }

    @Test
    fun `the non-rhotic widening is english only`() {
        // A reference in another language is not widened: the coda /ɹ/ stays
        // required, so the British reading is not accepted there.
        val english = PhoneticComparator.calculateScoringResult("near", "nˈɪɹ", "nɪə")
        val french = PhoneticComparator.calculateScoringResult("near", "nˈɪɹ", "nɪə", language = "fr")
        assertEquals(100, english.score)
        assertTrue(french.score < english.score, "fr=${french.score} en=${english.score}")
    }

    @Test
    fun `forgiving a coda r does not forgive an intervocalic schwa-r`() {
        // ɚ/ɝ already bundle their /ɹ/. Before another vowel that /ɹ/ is an onset
        // and is pronounced ("camera" kˈæmɚə -> kæmərə), so dropping it is a real
        // error. Only a word-final ɚ folds away.
        for ((word, reference, dropped) in listOf(
            Triple("camera", "kˈæmɚə", "kˈæməə"),
            Triple("favourite", "fˈeɪvɚɪt", "fˈeɪvəɪt"),
            Triple("battery", "bˈætɚi", "bˈætəi"),
            Triple("memory", "mˈɛmɚi", "mˈɛməi"),
            Triple("dangerous", "dˈeɪndʒɚəs", "dˈeɪndʒəəs"),
        )) {
            val result = PhoneticComparator.calculateScoringResult(word, reference, dropped)
            assertTrue(result.score < 100, "$word dropping the r must stay an error: ${result.score}")
            assertTrue(
                result.alignment.any { it.expected == "r" && it.status == MatchStatus.MISSED },
                "$word intervocalic r must be MISSED: ${result.alignment}",
            )
        }
    }

    @Test
    fun `a word-final schwa-r folds away but an intervocalic one is kept`() {
        // The British reading with the r kept is accepted where the r is an onset
        // ("camera" kæmərə) and with the r gone where it is a coda ("water" wɔtə).
        assertEquals(
            100,
            PhoneticComparator.calculateScoringResult("camera", "kˈæmɚə", "kæmərə").score,
        )
        for ((word, reference, british) in listOf(
            Triple("water", "wˈɔtɚ", "wɔtə"),
            Triple("letter", "lˈɛtɚ", "lɛtə"),
        )) {
            assertEquals(
                100,
                PhoneticComparator.calculateScoringResult(word, reference, british).score,
                "$word",
            )
        }
    }
}
