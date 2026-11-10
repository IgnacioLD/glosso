package com.glosso.engine.phonetics

import com.glosso.engine.model.MasteryLevel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end check of the regenerated reference in `tools/content/data/en_gb_reference.tsv`:
 * the engine scores the reported sentence and the /t/ vs flap controls against the
 * exact IPA that `tools/content/en_gb_reference.py` generates from `content.db`
 * with espeak-ng's en-GB voice.
 *
 * This is the test the owner's decision rests on (F3.2): a British reading of
 * "The bin is near the gate." must reach 100/PERFECT without ever requiring the
 * American coda /ɹ/, and the "three thirty" controls must stay at 100.
 *
 * Skipped unless the generated data file is present.
 */
class EnGbReferenceTest {

    private val reference = HashMap<String, String>()
    private val available: Boolean

    init {
        val file = File("../tools/content/data/en_gb_ipa.tsv")
        available = file.isFile
        if (available) {
            file.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size >= 4 && parts[0] != "key") reference[parts[0]] = parts[3]
            }
        }
    }

    private fun ref(key: String): String {
        assertTrue(available, "missing tools/content/data/en_gb_ipa.tsv (run en_gb_reference.py)")
        return reference.getValue(key)
    }

    @Test
    fun `the reported sentence no longer requires a coda r`() {
        val ipa = ref("en.ih.iy.airport.001")
        assertTrue("ɹ" !in ipa, "the en-GB reference must not carry the American coda r: $ipa")
        assertTrue("nˈiə" in ipa, "near is the en-GB /nɪə/: $ipa")
    }

    @Test
    fun `a british reading of the reported sentence reaches perfect`() {
        val ipa = ref("en.ih.iy.airport.001")
        for (actual in listOf(
            "ðə bɪn ɪz niə ðə ɡeɪt",
            "ðəbɪnɪzniəðəɡeɪt",
        )) {
            val result = PhoneticComparator.calculateScoringResult("The bin is near the gate.", ipa, actual)
            assertEquals(100, result.score, "$actual -> ${result.score}")
            assertEquals(MasteryLevel.PERFECT, result.level, "$actual")
            assertTrue(result.alignment.none { it.status == MatchStatus.MISSED }, "$actual missed a sound")
        }
    }

    @Test
    fun `the american rhotic reading is still accepted as a variant, not required`() {
        // The server fuses the CMUdict reading as an accepted alternate, so US
        // speakers are not punished by the en-GB canonical. This mirrors
        // withG2pVariants: the client word first, the dictionary reading after.
        val ipa = ref("en.ih.iy.airport.001")
        val words = ipa.split(" ")
        val variants = words.map { if (it == "nˈiə") listOf("nˈiə", "nˈɪɹ") else listOf(it) }
        val result = PhoneticComparator.calculateScoringResult(
            text = "The bin is near the gate.",
            expected = ipa,
            actual = "ðə bɪn ɪz nɪɹ ðə ɡeɪt",
            expectedWordVariants = variants,
        )
        assertEquals(100, result.score, "american near -> ${result.score}")
    }

    @Test
    fun `the three thirty controls stay at perfect`() {
        val ipa = ref("en.th.f.airport.001")
        assertTrue("θɹ" in ipa, "three keeps its onset r: $ipa")
        assertTrue("θˈɜːti" in ipa, "thirty is the en-GB /θɜːti/: $ipa")
        val britishT = "ðə flaɪt liːvz at θɹiː θɜːti"
        val americanFlap = "ðə flaɪt liːvz at θɹiː θɜːɾi"
        for (actual in listOf(britishT, americanFlap)) {
            val result = PhoneticComparator.calculateScoringResult(
                "The flight leaves at three thirty.", ipa, actual,
            )
            assertEquals(100, result.score, "$actual -> ${result.score}")
            assertTrue(result.alignment.none { it.status == MatchStatus.MISSED }, "$actual missed a sound")
        }
    }

    @Test
    fun `the flap written with t accepts both the british t and the american flap`() {
        val reference = "θˈɜːti"
        assertEquals(100, PhoneticComparator.calculateScoringResult("thirty", reference, reference).score)
        val flap = PhoneticComparator.calculateScoringResult("thirty", reference, "θɜːɾi")
        assertEquals(100, flap.score)
        assertTrue(flap.alignment.none { it.status == MatchStatus.MISSED }, "${flap.alignment}")
    }
}
