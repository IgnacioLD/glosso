package com.glosso.engine.model

import com.glosso.engine.phonetics.ErrorRules
import com.glosso.engine.phonetics.PhoneticComparator
import com.glosso.engine.phonetics.quality.ModelInventory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The non-English counterpart of ArpabetInventoryTest: every phone a lexicon
 * language declares, compares or explains must be a token the acoustic model
 * can emit. A symbol outside the vocabulary can never be heard, so a reference
 * that uses it can never score a perfect match and a rule keyed on it can never
 * fire.
 *
 * The vocabulary is the same 392-token golden the quality parity test uses.
 */
class LanguageInventoryTest {

    private val vocab: Map<String, Int> by lazy {
        val text = LanguageInventoryTest::class.java
            .getResourceAsStream("/quality/golden.json")
            ?.readAllBytes()?.decodeToString()
            ?: fail("missing test resource: quality/golden.json")
        Json.parseToJsonElement(text).jsonObject
            .getValue("vocab").jsonObject
            .entries.associate { (token, id) -> token to id.jsonPrimitive.int }
    }

    private val lexiconPacks = listOf(LanguagePacks.FRENCH, LanguagePacks.GERMAN, LanguagePacks.RUSSIAN)

    private fun unresolved(symbols: Iterable<String>): List<String> =
        symbols.filter { ModelInventory.resolve(it, vocab) == null }

    @Test
    fun `every inventory symbol is a model vocabulary token`() {
        for (pack in lexiconPacks) {
            assertEquals(
                emptyList(),
                unresolved(pack.phonemeInventory),
                "${pack.code}: inventory symbols the model cannot emit",
            )
        }
    }

    @Test
    fun `every german and russian matrix symbol is a model vocabulary token`() {
        for (pack in listOf(LanguagePacks.GERMAN, LanguagePacks.RUSSIAN)) {
            val symbols = PhoneticComparator.similarityMatrix(pack.similarityKey).keys.flatten().toSet()
            assertEquals(emptyList(), unresolved(symbols), "${pack.code}: matrix symbols the model cannot emit")
        }
    }

    @Test
    fun `every german and russian rule symbol is a model vocabulary token`() {
        for (pack in listOf(LanguagePacks.GERMAN, LanguagePacks.RUSSIAN)) {
            val symbols = ErrorRules.symbols(pack.code)
            assertTrue(symbols.isNotEmpty(), "${pack.code} has no error rules")
            assertEquals(emptyList(), unresolved(symbols), "${pack.code}: rule symbols the model cannot emit")
        }
    }

    @Test
    fun `the german short u-umlaut resolves to the token the model has`() {
        // espeak-ng writes ʏ; the vocabulary only carries y. Documented in the pack.
        assertTrue("ʏ" !in vocab)
        assertEquals("y", ModelInventory.resolve("ʏ", vocab))
    }

    @Test
    fun `the russian espeak spellings are model tokens before they are folded`() {
        // What the model emits for Ч, a final РЬ and a fronted У; the scorer folds
        // them (RU_SYMBOL_FOLDS), so they must exist for the fold to be needed.
        for (token in listOf("tʃʲ", "ɪ^", "u\"", "ɭʲ", "ʲ")) {
            assertTrue(token in vocab, "expected the model vocabulary to carry $token")
        }
    }
}
