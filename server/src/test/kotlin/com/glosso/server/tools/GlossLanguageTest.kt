package com.glosso.server.tools

import com.glosso.engine.model.WordModel
import kotlin.test.Test
import kotlin.test.assertEquals

class GlossLanguageTest {

    private fun word(language: String, translations: Map<String, String>) = WordModel(
        id = "$language.word.greetings.001",
        language = language,
        theme = "greetings",
        lemma = "hola",
        translations = translations,
        examples = listOf("Hola."),
    )

    private fun problems(language: String, translations: Map<String, String>) =
        ContentValidator.validateWord(word(language, translations), language).problems.map { it.detail }

    @Test
    fun `courses are glossed in spanish - the spanish course in english`() {
        assertEquals(emptyList(), problems("ru", mapOf("es" to "hola")))
        assertEquals(listOf("missing es translation"), problems("ru", mapOf("en" to "hello")))
        assertEquals(emptyList(), problems("es", mapOf("en" to "hello")))
        assertEquals(listOf("missing en translation"), problems("es", mapOf("es" to "hola")))
    }
}
