package com.glosso.engine.phonetics

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import com.glosso.engine.model.MasteryLevel
import com.glosso.engine.g2p.Arpabet

class PhoneticComparatorTest {

    // region normalize

    @Test
    fun `normalize lowercases and keeps simple phones`() {
        assertEquals("kæt", PhoneticComparator.normalize("KÆT"))
    }

    @Test
    fun `normalize strips stress but keeps vowel length`() {
        assertEquals("kætiː", PhoneticComparator.normalize("ˈkætˌiː"))
    }

    @Test
    fun `normalize folds near-identical allophones`() {
        assertEquals("əbət", PhoneticComparator.normalize("ɐbət"))
        assertEquals("ər", PhoneticComparator.normalize("ɚ"))
        assertEquals("ɪ", PhoneticComparator.normalize("ᵻ"))
    }

    @Test
    fun `normalize folds the stressed rhotic vowel to the model's token`() {
        // The phonemizer writes ɝ; the acoustic model vocabulary does not have it.
        assertEquals("bɜːd", PhoneticComparator.normalize("bɝd"))
    }

    @Test
    fun `reference phones keep primary stress in the scorer's order`() {
        val ipa = "bəˈnænə"
        val phones = PhoneticComparator.phonesOf(ipa)
        val reference = PhoneticComparator.referencePhones(ipa)

        assertEquals(phones, reference.map { it.phone })
        assertEquals(
            listOf(false, false, false, true, false, false),
            reference.map { it.stressed },
        )
    }

    @Test
    fun `secondary stress is treated as unstressed`() {
        val reference = PhoneticComparator.referencePhones("ˌkæt")
        assertEquals(listOf(false, false, false), reference.map { it.stressed })
    }

    @Test
    fun `reference words keep word boundaries`() {
        val words = PhoneticComparator.referenceWords("kˈæt dˈɒɡ")
        assertEquals(2, words.size)
        assertEquals(listOf("k", "æ", "t"), words[0].map { it.phone })
        assertTrue(words[0][1].stressed)
        assertEquals(listOf("d", "ɒ", "ɡ"), words[1].map { it.phone })
        assertTrue(words[1][1].stressed)
    }

    @Test
    fun `normalize maps ascii g to ipa g`() {
        assertEquals("ɡ", PhoneticComparator.normalize("g"))
    }

    @Test
    fun `normalize keeps affricates and nasals as single phones`() {
        assertEquals(listOf("tʃ"), phoneList("tʃ"))
        assertEquals(listOf("t͡ʃ"), phoneList("t͡ʃ"))
        assertEquals(listOf("dʒ"), phoneList("dʒ"))
        assertEquals(listOf("ɑ̃"), phoneList("ɑ̃"))
    }

    @Test
    fun `normalize splits on word boundaries`() {
        assertEquals(listOf("k", "æ", "t", "d", "ɒ", "ɡ"), phoneList("kæt dɒɡ"))
    }

    @Test
    fun `normalize keeps english ts as two phones across a boundary`() {
        // "cat sat" / "cats": English has no /ts/ phoneme, so the model's single
        // "ts" token must line up with the reference t + s.
        assertEquals(listOf("k", "æ", "t", "s"), phoneList("kæts"))
    }

    private fun phoneList(ipa: String): List<String> {
        val result = PhoneticComparator.calculateScoringResult("x", ipa, ipa)
        return result.alignment.map { it.expected }
    }

    // endregion

    // region calculateScoringResult

    @Test
    fun `identical phoneme strings score 100 with all perfect`() {
        val result = PhoneticComparator.calculateScoringResult("cat", "kæt", "kæt")
        assertEquals(100, result.score)
        assertEquals(3, result.alignment.size)
        assertTrue(result.alignment.all { it.status == MatchStatus.PERFECT })
    }

    @Test
    fun `completely different phones score 0`() {
        val result = PhoneticComparator.calculateScoringResult("cat", "kæt", "ʃuːz")
        assertEquals(0, result.score)
        assertTrue(result.alignment.all { it.status == MatchStatus.MISSED })
    }

    @Test
    fun `empty expected and empty actual score 100`() {
        val result = PhoneticComparator.calculateScoringResult("x", "", "")
        assertEquals(100, result.score)
        assertEquals("", result.normalizedExpected)
    }

    @Test
    fun `empty expected with non-empty actual scores 0`() {
        val result = PhoneticComparator.calculateScoringResult("x", "", "k")
        assertEquals(0, result.score)
    }

    @Test
    fun `known near-identical pair counts as perfect`() {
        // r ↔ ɹ has similarity 0.98 in the EN matrix (>= 0.85 threshold)
        val result = PhoneticComparator.calculateScoringResult("right", "raɪt", "ɹaɪt")
        assertEquals(100, result.score)
        assertEquals(MatchStatus.PERFECT, result.alignment[0].status)
    }

    @Test
    fun `known similar pair earns partial credit`() {
        // l ↔ r has similarity 0.8 in the EN matrix -> CLOSE with 0.2 cost; a, ɪ, t match
        val result = PhoneticComparator.calculateScoringResult("light", "laɪt", "raɪt")
        assertEquals(MatchStatus.CLOSE, result.alignment[0].status)
        assertEquals(95, result.score)
    }

    @Test
    fun `british lot and cloth realisations match the american reference symbols`() {
        // espeak en-GB, the model and the reference TTS, says LOT/CLOTH as [ɒ];
        // CMUdict writes them ɑ/ɔ. Same sound in another convention, so the model's
        // reading of the reference clip must count as the practiced sound. (æ~a is
        // deliberately left CLOSE - a distinct vowel, see the test below.)
        assertEquals(100, PhoneticComparator.calculateScoringResult("cot", "kˈɑt", "kɒt", "en_GB").score)
        assertEquals(100, PhoneticComparator.calculateScoringResult("shop", "ʃˈɑp", "ʃɒp", "en_GB").score)
        assertEquals(100, PhoneticComparator.calculateScoringResult("long", "lˈɔŋ", "lɒŋ", "en_GB").score)
    }

    @Test
    fun `the cot reference clip no longer misses its practiced vowel`() {
        // The real reference TTS of en.aa.ae.home.001, as the model hears it. The
        // practiced sound is /ɑ/ (cot); the model emits the British [ɒ]. This is
        // the deterministic reproduction of the reported bug: before the fix the
        // /ɑ/ came out MISSED and the drill's target sound showed 0.
        val result = PhoneticComparator.calculateScoringResult(
            text = "The cot is in the dark bedroom.",
            expected = "ðə kˈɑt ˈɪz ɪn ðə dˈɑɹk bˈɛdɹˌum",
            actual = "əkɒtɪzɪnðədɑːkbɛdɹuːm",
            language = "en_GB",
        )
        assertTrue(
            result.alignment.none { it.expected == "ɑ" && it.status == MatchStatus.MISSED },
            "the /ɑ/ target was missed: ${result.alignment.filter { it.expected == "ɑ" }}",
        )
    }

    @Test
    fun `unmapped pair earns no credit`() {
        // θ ↔ s has no EN matrix entry; ɪ, ŋ, k match -> 3/4 = 75
        val result = PhoneticComparator.calculateScoringResult("think", "θɪŋk", "sɪŋk")
        assertEquals(MatchStatus.MISSED, result.alignment[0].status)
        assertEquals(75, result.score)
    }

    @Test
    fun `mixed alignment computes weighted score`() {
        // l↔r costs 0.2, ɪ and t match -> 1 - 0.2/3 = 93
        val result = PhoneticComparator.calculateScoringResult("lit", "lɪt", "rɪt")
        assertEquals(93, result.score)
        assertEquals(MatchStatus.CLOSE, result.alignment[0].status)
        assertEquals(MatchStatus.PERFECT, result.alignment[1].status)
    }

    @Test
    fun `language matrices change similarity`() {
        // ʃ ↔ ʒ is 0.8 in FR matrix -> CLOSE there
        val frResult = PhoneticComparator.calculateScoringResult("jour", "ʒuʁ", "ʃuʁ", "fr")
        assertEquals(MatchStatus.CLOSE, frResult.alignment[0].status)
        // but has no EN entry -> MISSED with default matrix
        val enResult = PhoneticComparator.calculateScoringResult("x", "ʒ", "ʃ")
        assertEquals(MatchStatus.MISSED, enResult.alignment[0].status)
    }

    @Test
    fun `unknown language falls back to english matrix`() {
        // e ↔ ɛ is 0.7 in the EN matrix -> CLOSE, not a clean match.
        val result = PhoneticComparator.calculateScoringResult("x", "e", "ɛ", "xx")
        assertEquals(MatchStatus.CLOSE, result.alignment[0].status)
        assertTrue(result.score < 100)
    }

    @Test
    fun `normalized output strips stress but keeps vowel length`() {
        val result = PhoneticComparator.calculateScoringResult("sheep", "ˈʃiːp", "ʃiːp")
        assertEquals("ʃiːp", result.normalizedExpected)
        assertEquals("ʃiːp", result.normalizedActual)
    }

    // region sequence sensitivity (regression: scoring used to be order-insensitive)

    @Test
    fun `word order matters`() {
        // Regression: the old bag-of-phonemes search scored reversed phones 100.
        val result = PhoneticComparator.calculateScoringResult("cat", "kæt", "tæk")
        assertTrue(result.score < 50, "reordering should be penalised, got ${result.score}")
    }

    @Test
    fun `repeating the utterance does not inflate the score`() {
        // Regression: extra produced phonemes used to be completely free.
        val result = PhoneticComparator.calculateScoringResult("cat", "kæt", "kætkæt")
        assertTrue(result.score < 85, "duplication should be penalised, got ${result.score}")
    }

    @Test
    fun `dropping a phoneme is penalised`() {
        // m is word-final but not a plosive, so it keeps the full deletion cost.
        val result = PhoneticComparator.calculateScoringResult("sum", "sʌm", "sʌ")
        assertTrue(result.score < 70, "omission should be penalised, got ${result.score}")
    }

    @Test
    fun `extra phonemes cost something but less than a drop`() {
        val clean = PhoneticComparator.calculateScoringResult("sum", "sʌm", "sʌm")
        val extra = PhoneticComparator.calculateScoringResult("sum", "sʌm", "sʌmə")
        val dropped = PhoneticComparator.calculateScoringResult("sum", "sʌm", "sʌ")
        assertEquals(100, clean.score)
        assertTrue(extra.score < clean.score, "extra phone should cost something")
        assertTrue(extra.score > dropped.score, "an extra phone should be cheaper than a dropped one")
    }

    @Test
    fun `vowel length is meaningful`() {
        // German Stadt /ʃtat/ vs Staat /ʃtaːt/ differ only in vowel length.
        val wrongLength = PhoneticComparator.calculateScoringResult("Staat", "ʃtaːt", "ʃtat", "de")
        assertEquals(MatchStatus.CLOSE, wrongLength.alignment[2].status)
        assertTrue(wrongLength.score < 100, "short vowel for a long one should not be perfect")

        val rightLength = PhoneticComparator.calculateScoringResult("Staat", "ʃtaːt", "ʃtaːt", "de")
        assertEquals(100, rightLength.score)
    }

    // endregion

    @Test
    fun `alignment length matches expected phone count`() {
        val result = PhoneticComparator.calculateScoringResult("cat", "kæt", "ʃuːz")
        assertEquals(3, result.alignment.size)
        assertEquals(listOf("k", "æ", "t"), result.alignment.map { it.expected })
    }

    // endregion

    // region word scores, axes and mastery gates

    @Test
    fun `word scores are reported per reference word`() {
        val result = PhoneticComparator.calculateScoringResult("cat dog", "kæt dɒɡ", "kæt dɒɡ")
        assertEquals(2, result.words.size)
        assertTrue(result.words.all { it.score == 100 })
        assertEquals(3, result.words[0].phoneCount)
        assertEquals(listOf(0, 1), result.words.map { it.index })
    }

    @Test
    fun `accuracy and completeness separate sound quality from skipped words`() {
        // The second word is never attempted: every sound produced was correct,
        // but only half the sentence was said. These must not collapse into one
        // number, or the feedback cannot explain what to fix.
        val result = PhoneticComparator.calculateScoringResult("cat dog", "kæt dɒɡ", "kæt")

        assertEquals(100, result.accuracy)
        assertEquals(50, result.completeness)
        assertEquals(50, result.score)
        assertEquals(100, result.words[0].score)
        assertEquals(0, result.words[1].score)
        assertFalse(result.isMastery)
    }

    @Test
    fun `saying nothing scores zero accuracy and completeness`() {
        val result = PhoneticComparator.calculateScoringResult("cat", "kæt", "")
        assertEquals(0, result.accuracy)
        assertEquals(0, result.completeness)
        assertEquals(0, result.score)
        assertFalse(result.isMastery)
    }

    @Test
    fun `a perfect attempt masters`() {
        val result = PhoneticComparator.calculateScoringResult("cat dog", "kæt dɒɡ", "kæt dɒɡ")
        assertEquals(100, result.accuracy)
        assertEquals(100, result.completeness)
        assertTrue(result.isMastery)
    }

    @Test
    fun `a flawless take is PERFECT at sentence and word level`() {
        val result = PhoneticComparator.calculateScoringResult("cat dog", "kæt dɒɡ", "kæt dɒɡ")
        assertEquals(MasteryLevel.PERFECT, result.level)
        assertTrue(result.words.all { it.level == MasteryLevel.PERFECT })
    }

    @Test
    fun `mastery-band score blocked by a missing word is ALMOST - not MASTERED`() {
        // The level must follow the mastery gate, not the raw band: a great
        // average with one unsaid word is not "Mastered".
        val reference = List(7) { "kæt" }.joinToString(" ")
        val produced = List(6) { "kæt" }.joinToString(" ")
        val result = PhoneticComparator.calculateScoringResult("x", reference, produced)

        assertTrue(result.score >= 85, "expected a mastery-band score, got ${result.score}")
        assertFalse(result.isMastery)
        assertEquals(MasteryLevel.ALMOST, result.level)
        // The unsaid word is somewhere in the sentence, not necessarily last.
        assertTrue(result.words.any { it.level == MasteryLevel.NOT_YET })
    }

    @Test
    fun `one destroyed word blocks mastery even when the average is high`() {
        // Six of seven words perfect, one word never said. The averaged score
        // clears the bar, but the learner did not say the sentence.
        val reference = List(7) { "kæt" }.joinToString(" ")
        val produced = List(6) { "kæt" }.joinToString(" ")
        val result = PhoneticComparator.calculateScoringResult("x", reference, produced)

        assertTrue(result.score >= 85, "expected a mastery-band score, got ${result.score}")
        assertTrue(result.words.minOf { it.score } < 50, "a destroyed word must block mastery")
        assertFalse(result.isMastery, "a destroyed word must block mastery")
    }

    @Test
    fun `low completeness blocks mastery`() {
        val result = PhoneticComparator.calculateScoringResult("x", "kæt dɒɡ mæt", "kæt")
        assertFalse(result.isMastery)
    }

    // endregion

    // region generateLetterFeedback

    @Test
    fun `punctuation is always perfect`() {
        val alignment = listOf(
            PhonemeMatch("h", "h", MatchStatus.PERFECT),
            PhonemeMatch("ɪ", "ɪ", MatchStatus.PERFECT)
        )
        val feedback = PhoneticComparator.generateLetterFeedback("hi.", "hɪ", alignment)
        assertEquals(3, feedback.size)
        assertEquals(MatchStatus.PERFECT, feedback[2].status)
        assertEquals(".", feedback[2].char)
    }

    @Test
    fun `empty expected ipa marks everything perfect`() {
        val feedback = PhoneticComparator.generateLetterFeedback("abc", "", emptyList())
        assertEquals(3, feedback.size)
        assertTrue(feedback.all { it.status == MatchStatus.PERFECT })
    }

    @Test
    fun `missed phoneme propagates to its letters`() {
        val alignment = listOf(PhonemeMatch("æ", "x", MatchStatus.MISSED))
        val feedback = PhoneticComparator.generateLetterFeedback("a", "æ", alignment)
        assertEquals(MatchStatus.MISSED, feedback[0].status)
    }

    @Test
    fun `perfect alignment marks letters perfect`() {
        val alignment = listOf(
            PhonemeMatch("h", "h", MatchStatus.PERFECT),
            PhonemeMatch("ɪ", "ɪ", MatchStatus.PERFECT)
        )
        val feedback = PhoneticComparator.generateLetterFeedback("hi", "hɪ", alignment)
        assertTrue(feedback.all { it.status == MatchStatus.PERFECT })
    }

    // endregion

    // region getMinimalPairDescription

    @Test
    fun `known pair returns localized description`() {
        val en = PhoneticComparator.getMinimalPairDescription("p", "b", "en")
        val de = PhoneticComparator.getMinimalPairDescription("p", "b", "de")
        assertTrue(!en.isNullOrBlank())
        assertTrue(!de.isNullOrBlank())
        assertTrue(en != de)
    }

    @Test
    fun `unknown pair returns null`() {
        assertNull(PhoneticComparator.getMinimalPairDescription("q", "z", "en"))
    }

    @Test
    fun `unknown language falls back to english description`() {
        val fallback = PhoneticComparator.getMinimalPairDescription("p", "b", "xx")
        val en = PhoneticComparator.getMinimalPairDescription("p", "b", "en")
        assertEquals(en, fallback)
    }

    @Test
    fun `shared pair description follows the target language`() {
        // /ɪ/ vs /iː/ is a real contrast in both English (bit/beat) and German
        // (bitten/bieten): the hint must teach the language being practised.
        val en = PhoneticComparator.getMinimalPairDescription("ɪ", "iː", "en", "en_GB")
        val de = PhoneticComparator.getMinimalPairDescription("ɪ", "iː", "en", "de")
        assertTrue(en!!.contains("sheep"), "English hint should use English examples: $en")
        assertTrue(de!!.contains("bitten"), "German hint should use German examples: $de")
        assertTrue(en != de, "the two hints must differ")
    }

    @Test
    fun `shared pair never leaks the wrong target language`() {
        // Regression: the German entry used to shadow the English one because
        // the pair map keyed equal Set values, so every learner got "bitten".
        val en = PhoneticComparator.getMinimalPairDescription("ɪ", "iː", "en", "en_US")!!
        assertFalse(en.contains("bitten"), "English hint must not mention German words: $en")
        assertFalse(en.contains("bieten"), "English hint must not mention German words: $en")
    }

    @Test
    fun `vowel length pair uses the language being practised`() {
        val de = PhoneticComparator.getMinimalPairDescription("a", "aː", "en", "de")!!
        val la = PhoneticComparator.getMinimalPairDescription("a", "aː", "en", "la")!!
        assertTrue(de.contains("Stadt"), "German hint should use German examples: $de")
        assertTrue(la.contains("MĀLUM"), "Latin hint should use Latin examples: $la")
    }

    @Test
    fun `german-only pair is hidden from english learners`() {
        // Regression: /ts/ vs /s/ carries a German example ("Zeit"), but English
        // learners produce /ts/ in plurals ("cats"), so it used to be shown to
        // them. It must only appear for German now.
        assertNull(PhoneticComparator.getMinimalPairDescription("ts", "s", "en", "en_GB"))
        val de = PhoneticComparator.getMinimalPairDescription("ts", "s", "en", "de")
        assertTrue(de!!.contains("Zeit"), "German learner should get the German hint: $de")
    }

    @Test
    fun `shared pair still reaches english learners`() {
        val en = PhoneticComparator.getMinimalPairDescription("ɪ", "iː", "en", "en_GB")
        assertTrue(en!!.contains("sheep"), "a genuinely shared pair must still be shown: $en")
    }

    // endregion

    // region F1: connected-speech allophony

    @Test
    fun `english flap is the same phoneme as t`() {
        // "sat on" -> the t is flapped in fluent speech; not a MISSED sound.
        val result = PhoneticComparator.calculateScoringResult("sat", "sæt", "sæɾ")
        assertEquals(MatchStatus.PERFECT, result.alignment[2].status)
        assertEquals(100, result.score)
    }

    @Test
    fun `english glottal stop counts as t`() {
        val result = PhoneticComparator.calculateScoringResult("but", "bʌt", "bʌʔ")
        assertEquals(MatchStatus.PERFECT, result.alignment[2].status)
    }

    @Test
    fun `word-final unreleased plosive is forgiven but not perfect`() {
        val result = PhoneticComparator.calculateScoringResult("mat", "mæt", "mæ")
        assertEquals(MatchStatus.CLOSE, result.alignment[2].status)
        assertTrue(result.score >= 85, "unreleased final stop should not destroy the word, got ${result.score}")
        assertEquals(MasteryLevel.MASTERED, result.words[0].level)
    }

    @Test
    fun `word-medial plosive deletion is still an error`() {
        val result = PhoneticComparator.calculateScoringResult("open", "oʊpən", "oʊən")
        assertEquals(MatchStatus.MISSED, result.alignment[2].status)
        assertTrue(result.score < 85, "a dropped medial stop must cost, got ${result.score}")
    }

    @Test
    fun `the cat sat on the mat scores as native-like`() {
        // Golden E2E case: reference vs. the real model output for the recorded
        // take (flap t + unreleased final t). It used to score 73.
        val expected = "ðəkætsætɑnðəmæt"
        val actual = "ðəkætsæɾɔnðəmæ"
        val result = PhoneticComparator.calculateScoringResult("The cat sat on the mat.", expected, actual)
        assertTrue(result.score >= 85, "flap + unreleased stop should not tank the score, got ${result.score}")
        assertTrue(
            result.alignment.none { it.expected == "t" && it.actual == "ɾ" && it.status == MatchStatus.MISSED },
            "a flap must never be reported as a missed t",
        )
        // The COT/CAUGHT merge (ɑ heard as ɔ) is a native dialect feature, and the
        // unreleased final t is an allophone: neither may block mastery, or real
        // native speech would be punished for being native.
        assertTrue(result.isMastery, "a native-like take with only allophones must master, got ${result.level}")
    }

    // endregion

    // region F1: confidence-aware scoring

    @Test
    fun `model confidence cannot turn a wrong sound into a near miss`() {
        // Regression: a low-confidence mismatch used to be demoted to CLOSE, so a
        // strong accent the model struggled to decode could not miss at all.
        val unsure = PhoneticComparator.calculateScoringResult(
            "think", "θɪŋk", "sɪŋk", actualConfidence = listOf(0.2f, 1f, 1f, 1f),
        )
        assertEquals(MatchStatus.MISSED, unsure.alignment[0].status)

        val sure = PhoneticComparator.calculateScoringResult(
            "think", "θɪŋk", "sɪŋk", actualConfidence = listOf(0.9f, 1f, 1f, 1f),
        )
        assertEquals(MatchStatus.MISSED, sure.alignment[0].status)
        assertEquals(unsure.score, sure.score, "confidence must not change a substitution's cost")
    }

    @Test
    fun `an unsure insertion costs less than a confident one`() {
        val sure = PhoneticComparator.calculateScoringResult(
            "cat", "kæt", "kætx", actualConfidence = listOf(1f, 1f, 1f, 1f),
        )
        val unsure = PhoneticComparator.calculateScoringResult(
            "cat", "kæt", "kætx", actualConfidence = listOf(1f, 1f, 1f, 0f),
        )
        assertTrue(unsure.score > sure.score, "an unsure extra phone should cost less (${unsure.score} vs ${sure.score})")
    }

    @Test
    fun `confidence of the wrong length is ignored`() {
        // A recognizer that cannot line confidence up with phones must not shift
        // penalties onto the wrong sound.
        val result = PhoneticComparator.calculateScoringResult(
            "cat", "kæt", "kæt", actualConfidence = listOf(0f, 0f),
        )
        assertEquals(100, result.score)
    }

    @Test
    fun `recognized tokens expand to one confidence per scored phone`() {
        // The model emits one token for a diphthong ("aɪ") and one for "ts",
        // which the scorer splits into two phones each.
        val phones = listOf(
            RecognizedPhone("k", 0.9f),
            RecognizedPhone("æ", 0.8f),
            RecognizedPhone("ts", 0.7f),
            RecognizedPhone("aɪ", 0.6f),
        )
        assertEquals(
            listOf(0.9f, 0.8f, 0.7f, 0.7f, 0.6f, 0.6f),
            PhoneticComparator.phoneConfidencesOf(phones),
        )
        val ipa = phones.joinToString("") { it.phone }
        assertEquals(PhoneticComparator.normalize(ipa).length, 6, "sanity: six single-char phones")
    }

    // endregion

    // region model-inventory phones (quality head / forced alignment)

    @Test
    fun `modelPhones keeps the diphthongs the model emits as one token`() {
        assertEquals(listOf("t", "aɪ", "m"), PhoneticComparator.modelPhones("taɪm"))
        assertEquals(listOf("eɪ", "oʊ", "aʊ", "ɔɪ"), PhoneticComparator.modelPhones("eɪ oʊ aʊ ɔɪ"))
        assertEquals(listOf("dʒ", "ʌ", "dʒ"), PhoneticComparator.modelPhones("dʒʌdʒ"))
        assertEquals(listOf("tʃ", "iː", "z"), PhoneticComparator.modelPhones("tʃiːz"))
    }

    @Test
    fun `modelPhones differs from the scorer only on the diphthongs`() {
        // No other a/ɪ tokens, so the only possible difference is the diphthong.
        val reference = "maɪ"
        assertEquals(listOf("m", "a", "ɪ"), PhoneticComparator.phonesOf(reference))
        assertEquals(listOf("m", "aɪ"), PhoneticComparator.modelPhones(reference))
    }

    @Test
    fun `the diphthong split is convention-independent`() {
        // ARPAbet->IPA (CMUdict G2P), authored IPA and espeak-ng all spell the
        // English diphthongs as one IPA symbol, and the scorer split all three
        // the same way - which is why the benchmark (single token) diverged.
        val arpa = Arpabet.word("T AY1 M")  // "tˈaɪm"
        val authored = "taɪm"
        // espeak-ng en-us output, verified on the box: time/day/go/now/boy.
        val espeak = listOf("taɪm", "deɪ", "ɡoʊ", "naʊ", "bɔɪ")
        val cases = listOf(arpa, authored) + espeak
        assertEquals(listOf("t", "a", "ɪ", "m"), PhoneticComparator.phonesOf(arpa))
        assertEquals(listOf("t", "aɪ", "m"), PhoneticComparator.modelPhones(arpa))
        for (reference in cases) {
            assertEquals(
                PhoneticComparator.modelPhones(reference).size + 1,
                PhoneticComparator.phonesOf(reference).size,
                "every diphthong case must differ by exactly one split: $reference",
            )
        }
        assertEquals(listOf("t", "aɪ", "m"), PhoneticComparator.modelPhones("taɪm"))
        assertEquals(listOf("d", "eɪ"), PhoneticComparator.modelPhones("deɪ"))
        assertEquals(listOf("ɡ", "oʊ"), PhoneticComparator.modelPhones("ɡoʊ"))
        assertEquals(listOf("n", "aʊ"), PhoneticComparator.modelPhones("naʊ"))
        assertEquals(listOf("b", "ɔɪ"), PhoneticComparator.modelPhones("bɔɪ"))
    }

    // endregion

    // region phonemeErrorRate

    @Test
    fun `per is zero for an exact match`() {
        assertEquals(0.0, PhoneticComparator.phonemeErrorRate("kæt", "kæt"))
    }

    @Test
    fun `per counts a substitution over the reference length`() {
        // one wrong phone out of three
        assertEquals(1.0 / 3.0, PhoneticComparator.phonemeErrorRate("kæt", "kɑt"), 1e-9)
    }

    @Test
    fun `per counts deletions and insertions`() {
        // deletion: reference has 3, hypothesis 2, distance 1
        assertEquals(1.0 / 3.0, PhoneticComparator.phonemeErrorRate("kæt", "kæ"), 1e-9)
        // insertion: reference has 3, hypothesis 4, distance 1
        assertEquals(1.0 / 3.0, PhoneticComparator.phonemeErrorRate("kæt", "kæts"), 1e-9)
    }

    @Test
    fun `per ignores stress it cannot compare`() {
        assertEquals(0.0, PhoneticComparator.phonemeErrorRate("ˈkæt", "kæt"))
    }

    @Test
    fun `per normalizes the two spellings of g`() {
        assertEquals(0.0, PhoneticComparator.phonemeErrorRate("ɡoʊ", "goʊ"))
    }

    @Test
    fun `per of a non-empty hypothesis against an empty reference is total error`() {
        assertEquals(1.0, PhoneticComparator.phonemeErrorRate("", "kæt"))
        assertEquals(0.0, PhoneticComparator.phonemeErrorRate("", ""))
    }

    // endregion
}
