package com.glosso.server

import com.glosso.engine.g2p.CmuDictG2P
import com.glosso.engine.g2p.LexiconG2P
import com.glosso.engine.model.DrillModel
import com.glosso.engine.phonetics.PhoneticComparator
import com.glosso.server.tools.drillVariants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what `ContentPublisher` publishes in `DrillModel.ipaVariants`, the shape
 * the offline scorer consumes. The parity tests elsewhere compare against
 * `widenReferenceVariants`; this fixes that `drillVariants` produces exactly that
 * shape (empty entries for words that do not vary), so the device and the cloud
 * cannot drift apart through the published form.
 */
class ContentPublisherVariantsTest {

    private val g2p = CmuDictG2P.fromLines(
        listOf(
            "THE  DH AH0",
            "FLIGHT  F L AY1 T",
            "LEAVES  L IY1 V Z",
            "AT  AE1 T",
            "THREE  TH R IY1",
            "THIRTY  TH ER1 D IY2",
            "THIRTY(2)  TH ER1 T IY2",
            "CAT  K AE1 T",
            "DOG  D AO1 G",
        ),
    )

    private fun drill(id: String, text: String, ipa: String) = DrillModel(
        id = id,
        target = "θ",
        text = text,
        ipa = ipa,
    )

    @Test
    fun `publishes the widened readings only for the words that vary`() {
        val d = drill("d.thirty", "The flight leaves at three thirty.", "ðə flˈaɪt lˈivz ˈæt θɹˈi θˈɝdˌi")
        assertEquals(
            listOf(emptyList<String>(), emptyList(), emptyList(), emptyList(), emptyList(), listOf("θˈɝdˌi", "θˈɝtˌi")),
            drillVariants(g2p, d),
        )
    }

    @Test
    fun `a drill with a single reading per word publishes nothing`() {
        val d = drill("d.cat", "cat dog", "kˈæt dˈɔɡ")
        assertTrue(drillVariants(g2p, d).isEmpty())
    }

    @Test
    fun `a lexicon g2p (no variants) publishes nothing`() {
        // French uses LexiconG2P, which overrides no `wordVariants`: nothing to
        // widen, and no mismatch warning either.
        val lexicon = LexiconG2P.fromLines(listOf("bonjour bɔ̃ʒuʁ"))
        val d = drill("d.fr", "bonjour", "bɔ̃ʒuʁ")
        assertTrue(drillVariants(lexicon, d).isEmpty())
    }

    @Test
    fun `an ipa that does not line up with the text publishes nothing`() {
        val d = drill("d.mismatch", "The flight leaves at three thirty.", "ðə flˈaɪt")
        assertTrue(drillVariants(g2p, d).isEmpty())
    }

    @Test
    fun `the published shape scores the same as the reference word fallback would`() {
        val text = "The flight leaves at three thirty."
        val expected = "ðə flˈaɪt lˈivz ˈæt θɹˈi θˈɝdˌi"
        val british = "ðə flaɪt liːvz æt θɹiː θɜːti"
        val published = drillVariants(g2p, drill("d.thirty", text, expected))
        val spelledOut = published.mapIndexed { i, readings ->
            if (readings.isEmpty()) listOf(expected.split(" ")[i]) else readings
        }
        assertEquals(
            PhoneticComparator.calculateScoringResult(text, expected, british, "en", expectedWordVariants = spelledOut).score,
            PhoneticComparator.calculateScoringResult(text, expected, british, "en", expectedWordVariants = published).score,
        )
    }
}
