package com.glosso.engine.accent

import com.glosso.engine.model.MasteryLevel
import com.glosso.engine.model.VoiceGender
import com.glosso.engine.phonetics.PhoneticComparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiAccentScorerTest {

    /**
     * CMUdict (General American) reading of a sentence that touches every seed
     * rule: PRICE (I, time), TH (think, the), THOUGHT (dog, talk), GOAT (go),
     * FACE (gate), pin/pen (ten) and coda r (car, near).
     */
    private val text = "I think the dog can go to the car near the gate at ten to talk"
    private val canonical =
        "ˈaɪ θˈɪŋk ðə dˈɔɡ kˈæn ɡˈoʊ tˈu ðə kˈɑɹ nˈɪɹ ðə ɡˈeɪt ˈæt tˈɛn tˈu tˈɔk"

    private val systems = AccentCatalog.ENGLISH

    /** What a native of the accent says: its own reading, as the model writes it (no stress). */
    private fun spoken(ipa: String) = ipa.replace("ˈ", "").replace("ˌ", "")

    private fun nativeTake(phonology: AccentPhonology) = spoken(phonology.realize(canonical).first())

    @Test
    fun `the catalogue carries every voice the owner gave - with its accent and gender`() {
        val expected = mapOf(
            "en-AU" to (2 to 3),
            "en-US-CA" to (2 to 0),
            "en-US-South" to (2 to 2),
            "en-US-NY" to (0 to 3),
            "en-IE" to (1 to 2),
            "en-US" to (2 to 3),
            "en-GB" to (2 to 2),
            "midwestern-american" to (1 to 2),
            "canadian-english" to (1 to 1),
            "new-zealand" to (2 to 0),
        )
        for ((accent, counts) in expected) {
            val voices = AccentCatalog.PROVIDER_VOICES.filter { it.accent == accent }
            assertEquals(counts.first, voices.count { it.gender == VoiceGender.FEMALE }, "$accent female")
            assertEquals(counts.second, voices.count { it.gender == VoiceGender.MALE }, "$accent male")
        }
        assertEquals(33, AccentCatalog.PROVIDER_VOICES.map { it.voiceId }.toSet().size)
        assertEquals(
            AccentCatalog.voice("d1d9c946-7cfc-4378-85a4-07d09827cb7e")?.accent,
            "en-US-South",
        )
    }

    @Test
    fun `a system exists only for an accent and gender with a reference voice`() {
        assertEquals(
            listOf(
                "en-GB-female", "en-GB-male",
                "en-US-female", "en-US-male",
                "en-US-CA-female",
                "en-US-South-female", "en-US-South-male",
                "en-US-NY-male",
                "en-IE-female", "en-IE-male",
                "en-AU-female", "en-AU-male",
                "midwestern-american-female", "midwestern-american-male",
                "canadian-english-female", "canadian-english-male",
                "new-zealand-female",
            ),
            systems.map { it.id },
        )
        assertTrue(systems.all { it.voices.isNotEmpty() })
        assertTrue(systems.none { it.phonology.calibrated }, "no accent is measured yet")
    }

    @Test
    fun `every catalogued provider voice joins a system`() {
        val byVoice = systems.flatMap { system -> system.voices.map { it.voiceId to system.id } }.toMap()
        for (voice in AccentCatalog.PROVIDER_VOICES) {
            assertEquals(
                AccentSystem.systemId(voice.accent, voice.gender),
                byVoice[voice.voiceId],
                "${voice.voiceId} (${voice.accent}) must join a system",
            )
        }
    }

    @Test
    fun `each phonology rewrites every word of the sentence`() {
        val ie = AccentCatalog.EN_IE.realize("ðə θˈɪŋk ðə θˈɪŋ").single()
        assertEquals("də tˈɪŋk də tˈɪŋ", ie)
        assertEquals("tˈaːm tˈɪn", AccentCatalog.EN_US_SOUTH.realize("tˈaɪm tˈɛn").single())
        assertEquals("kˈɑt kˈɔɹn", AccentCatalog.EN_US_CA.realize("kˈɔt kˈɔɹn").single())
        assertEquals("tˈʊək nˈɔːθ", AccentCatalog.EN_US_NY.realize("tˈɔk nˈɔɹθ").first())
        val gb = AccentCatalog.EN_GB.realize("kˈɑɹ nˈɪɹ")
        assertTrue(gb.all { 'ɹ' !in it }, "$gb")
        assertEquals(listOf("kˈɑː nˈiə", "kˈɑː nˈɪə"), gb)
        // Midwestern keeps the cot/caught merger and raises TRAP before a velar.
        assertEquals("kˈɑt bˈeəɡ", AccentCatalog.EN_US_MIDWEST.realize("kˈɔt bˈæɡ").single())
        // Canadian raising only fires before a voiceless consonant.
        assertEquals("rˈəɪt ʃˈʌʊt", AccentCatalog.EN_CA.realize("rˈaɪt ʃˈaʊt").single())
        assertEquals("rˈaɪd lˈaʊd", AccentCatalog.EN_CA.realize("rˈaɪd lˈaʊd").single())
        // New Zealand is non-rhotic and raises DRESS.
        assertEquals("tˈɪn kˈɑː", AccentCatalog.EN_NZ.realize("tˈɛn kˈɑɹ").single())
    }

    @Test
    fun `a native of each accent scores 100 against their own system`() {
        for (system in systems) {
            val take = nativeTake(system.phonology)
            val result = MultiAccentScorer.score(
                text = text,
                canonicalIpa = canonical,
                actual = take,
                systems = systems,
                target = AccentTarget(system.accent),
            )
            val own = result.candidates.first { it.system.id == system.id }
            assertEquals(100, own.result.score, "${system.id}: $take")
            // Two genders of one accent share a phonology and no voice reference,
            // so they score identically; the winner is a system of that accent.
            assertEquals(system.accent, result.system.accent, "${system.id} must win its own take")
        }
    }

    @Test
    fun `the best system is chosen by the take - not by the mode`() {
        val irish = nativeTake(AccentCatalog.EN_IE)
        val result = MultiAccentScorer.score(
            text = text,
            canonicalIpa = canonical,
            actual = irish,
            systems = systems,
            target = AccentTarget("en-US"),
        )
        assertEquals("en-IE", result.system.accent)
        assertEquals(100, result.result.score)
        val american = result.candidates.first { it.system.accent == "en-US" }
        assertTrue(american.result.score < 100, "the mode does not discriminate: ${american.result.score}")
    }

    @Test
    fun `the mode only breaks an exact tie`() {
        // No THOUGHT vowel, no r, no PRICE/FACE/GOAT/TH/pen: en-US and en-US-CA are
        // the same reading here.
        val result = MultiAccentScorer.score(
            text = "bad cat",
            canonicalIpa = "bˈæd kˈæt",
            actual = "bæd kæt",
            systems = systems,
            target = AccentTarget("en-US-CA"),
        )
        assertEquals(100, result.result.score)
        assertEquals("en-US-CA", result.system.accent)
    }

    @Test
    fun `a take that mixes two accents matches no system fully`() {
        // Southern PRICE and Irish TH in one take.
        val mixedSentence = "I think"
        val mixedCanonical = "ˈaɪ θˈɪŋk"
        val southern = spoken(AccentCatalog.EN_US_SOUTH.realize(mixedCanonical).single())
        val irish = spoken(AccentCatalog.EN_IE.realize(mixedCanonical).single())
        assertEquals(100, MultiAccentScorer.score(mixedSentence, mixedCanonical, southern, systems).result.score)
        assertEquals(100, MultiAccentScorer.score(mixedSentence, mixedCanonical, irish, systems).result.score)

        val mixed = "aː tɪŋk"
        val result = MultiAccentScorer.score(mixedSentence, mixedCanonical, mixed, systems)
        assertTrue(result.candidates.all { it.result.score < 100 }, result.candidates.map { it.system.id to it.result.score }.toString())
        assertFalse(result.result.level == MasteryLevel.PERFECT)

        // A per-word union of the accents (what mixing would be) gives it away.
        val union = mixedCanonical.split(" ").map { word ->
            (listOf(word) + systems.flatMap { s -> s.phonology.realizers().map { it(word) } }).distinct()
        }
        val mixedUnion = PhoneticComparator.calculateScoringResult(
            text = mixedSentence,
            expected = mixedCanonical,
            actual = mixed,
            expectedWordVariants = union,
        )
        assertEquals(100, mixedUnion.score, "the per-word union is exactly the mixing the systems refuse")
        assertTrue(result.result.score < mixedUnion.score)
    }

    @Test
    fun `rhotic in one word and non-rhotic in the next is not rewarded`() {
        val sentence = "the car is near"
        val canonicalR = "ðə kˈɑɹ ˈɪz nˈɪɹ"
        val result = MultiAccentScorer.score(sentence, canonicalR, "ðə kɑɹ ɪz nɪə", systems)
        assertTrue(result.candidates.all { it.result.score < 100 }, result.candidates.map { it.system.id to it.result.score }.toString())
        assertEquals(100, MultiAccentScorer.score(sentence, canonicalR, "ðə kɑɹ ɪz nɪɹ", systems).result.score)
        assertEquals(100, MultiAccentScorer.score(sentence, canonicalR, "ðə kɑː ɪz nɪə", systems).result.score)
    }

    @Test
    fun `an accent reference is scored next to the rule-derived one of that accent only`() {
        // An espeak en-GB transcription writes the unstressed "is" and a NEAR the
        // rules do not produce; the take follows it.
        val gbReference = "ðə kˈɑː ɪz nˈɪə"
        val result = MultiAccentScorer.score(
            text = "the car is near",
            canonicalIpa = "ðə kˈɑɹ ˈɪz nˈɪɹ",
            actual = "ðə kɑː ɪz nɪə",
            systems = systems,
            accentReferences = mapOf("en-GB" to gbReference),
        )
        assertEquals("en-GB", result.system.accent)
        assertEquals(100, result.result.score)
        assertEquals(gbReference, result.best.reference, "the accent's own reference is reported on a tie")
        val american = result.candidates.first { it.system.accent == "en-US" }
        assertEquals("ðə kˈɑɹ ˈɪz nˈɪɹ", american.reference)
    }

    @Test
    fun `a system-specific reference wins over the accent-wide one`() {
        val result = MultiAccentScorer.score(
            text = "cat",
            canonicalIpa = "kˈæt",
            actual = "kɛt",
            systems = systems,
            accentReferences = mapOf("en-AU" to "kˈɪt", "en-AU-male" to "kˈɛt"),
        )
        val female = result.candidates.first { it.system.id == "en-AU-female" }
        val male = result.candidates.first { it.system.id == "en-AU-male" }
        assertEquals("kˈɛt", male.reference)
        assertEquals(100, male.result.score)
        assertTrue(female.result.score < 100, "en-AU-female only has kˈɪt and kˈæt: ${female.result.score}")
        assertEquals("en-AU-male", result.system.id)
    }
}
