package com.glosso.engine.g2p

import com.glosso.engine.phonetics.quality.ModelInventory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Closes the "CMUdict -> IPA mapping is provisional" finding (docs/FINDINGS.md
 * #6): every phone [Arpabet] can emit must be a token the acoustic model actually
 * has, or the reference and the recognised transcription compare in two different
 * alphabets.
 *
 * The vocabulary is the same 392-token golden the quality parity test uses, so
 * this fails if the mapping starts emitting a symbol the model does not produce.
 */
class ArpabetInventoryTest {

    private val vocab: Map<String, Int> by lazy {
        val text = ArpabetInventoryTest::class.java
            .getResourceAsStream("/quality/golden.json")
            ?.readAllBytes()?.decodeToString()
            ?: fail("missing test resource: quality/golden.json")
        Json.parseToJsonElement(text).jsonObject
            .getValue("vocab").jsonObject
            .entries.associate { (token, id) -> token to id.jsonPrimitive.int }
    }

    private val arpabetBases = listOf(
        "AA", "AE", "AH", "AO", "AW", "AY", "B", "CH", "D", "DH", "EH", "ER",
        "EY", "F", "G", "HH", "IH", "IY", "JH", "K", "L", "M", "N", "NG", "OW",
        "OY", "P", "R", "S", "SH", "T", "TH", "UH", "UW", "V", "W", "Y", "Z", "ZH",
    )

    @Test
    fun `every arpabet symbol maps to a model vocabulary token`() {
        for (base in arpabetBases) {
            for (stress in listOf("", "0", "1", "2")) {
                val ipa = Arpabet.phoneme("$base$stress")
                    ?: fail("Arpabet has no IPA for $base$stress")
                val phone = ipa.removePrefix("ˈ").removePrefix("ˌ")
                assertNotNull(
                    ModelInventory.resolve(phone, vocab),
                    "model has no token for $base$stress -> /$ipa/",
                )
            }
        }
    }
}
