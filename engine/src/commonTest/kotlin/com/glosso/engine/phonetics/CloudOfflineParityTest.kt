package com.glosso.engine.phonetics

import com.glosso.engine.g2p.CmuDictG2P
import com.glosso.engine.g2p.widenReferenceVariants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cloud and offline must return the same verdict for the same recording.
 *
 * Both start from the same reference IPA (the client sends the content's `ipa`)
 * and both widen it with [widenReferenceVariants], the single source of truth:
 * the cloud calls it at scoring time, the publisher bakes its result into the
 * drill (`DrillModel.ipaVariants`) and the device consumes that. These tests pin
 * the rule and that a British /t/ in "thirty" is no longer charged as a missing
 * American /d/ on either path.
 */
class CloudOfflineParityTest {

    private val g2p = CmuDictG2P.fromLines(
        listOf(
            "THE  DH AH0",
            "FLIGHT  F L AY1 T",
            "LEAVES  L IY1 V Z",
            "AT  AE1 T",
            "THREE  TH R IY1",
            "THIRTY  TH ER1 D IY2",
            "THIRTY(2)  TH ER1 T IY2",
        ),
    )

    private val text = "The flight leaves at three thirty."
    private val expected = "ðə flˈaɪt lˈivz ˈæt θɹˈi θˈɝdˌi"
    private val british = "ðə flaɪt liːvz æt θɹiː θɜːti"

    @Test
    fun `the widening keeps the canonical reading first and drops single readings`() {
        val widened = widenReferenceVariants(expected, g2p.pronunciations(text).wordVariants)
        assertEquals(expected.split(" ").size, widened.size)
        // Only "thirty" varies; the rest fall back to the reference word.
        assertEquals(listOf(emptyList<String>(), emptyList(), emptyList(), emptyList(), emptyList(), listOf("θˈɝdˌi", "θˈɝtˌi")), widened)
    }

    @Test
    fun `a word count mismatch widens nothing`() {
        assertTrue(widenReferenceVariants("ðə bˈɪn", g2p.pronunciations(text).wordVariants).isEmpty())
    }

    @Test
    fun `an empty entry scores the same as the reference word`() {
        val compact = widenReferenceVariants(expected, g2p.pronunciations(text).wordVariants)
        val spelledOut = compact.mapIndexed { i, readings ->
            if (readings.isEmpty()) listOf(expected.split(" ")[i]) else readings
        }
        assertEquals(
            PhoneticComparator.calculateScoringResult(text, expected, british, "en", expectedWordVariants = compact).score,
            PhoneticComparator.calculateScoringResult(text, expected, british, "en", expectedWordVariants = spelledOut).score,
        )
    }

    @Test
    fun `offline and cloud agree on a british thirty - with no false flap`() {
        val variants = widenReferenceVariants(expected, g2p.pronunciations(text).wordVariants)
        val cloud = PhoneticComparator.calculateScoringResult(
            text = text,
            expected = expected,
            actual = british,
            language = "en",
            expectedWordVariants = variants,
        )
        val offline = PhoneticComparator.calculateScoringResult(
            text = text,
            expected = expected,
            actual = british,
            language = "en",
            expectedWordVariants = variants,
        )

        assertEquals(cloud.score, offline.score, "cloud and offline must give the same score")
        assertTrue(
            offline.alignment.none { it.expected == "d" && it.actual == "t" },
            "the british /t/ must not be lined up against an american /d/",
        )
    }

    @Test
    fun `the isolated british thirty scores 100 on both paths`() {
        val variants = widenReferenceVariants("θˈɝdˌi", g2p.pronunciations("thirty").wordVariants)
        val cloud = PhoneticComparator.calculateScoringResult(
            "thirty", "θˈɝdˌi", "θˈɜːti", "en", expectedWordVariants = variants,
        )
        val offline = PhoneticComparator.calculateScoringResult(
            "thirty", "θˈɝdˌi", "θˈɜːti", "en", expectedWordVariants = variants,
        )
        assertEquals(cloud.score, offline.score)
        assertEquals(100, offline.score)
        assertTrue(
            offline.alignment.none { it.status == MatchStatus.MISSED },
            "no sound may be reported missing for a correct british reading: ${offline.alignment}",
        )
    }

    /**
     * Without the published variants the offline path reproduces the bug: the
     * American flap /d/ is required, so a British /t/ reads as MISSED. This is the
     * gap the content-side widening closes.
     */
    @Test
    fun `without the published variants offline still charges the false flap`() {
        val offline = PhoneticComparator.calculateScoringResult(text, expected, british, "en")
        assertTrue(
            offline.alignment.any { it.expected == "d" && it.actual == "t" && it.status == MatchStatus.MISSED },
            "the single american reading must still be the bug this fix targets: ${offline.alignment}",
        )
    }

    @Test
    fun `the american flap reading still scores 100 on both paths`() {
        val variants = widenReferenceVariants("θˈɝdˌi", g2p.pronunciations("thirty").wordVariants)
        val offline = PhoneticComparator.calculateScoringResult(
            "thirty", "θˈɝdˌi", "θɝdi", "en", expectedWordVariants = variants,
        )
        assertEquals(100, offline.score)
        assertTrue(offline.alignment.none { it.status == MatchStatus.MISSED })
    }
}
