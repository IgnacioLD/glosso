package com.glosso.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LessonL10nTest {

    private val lesson = LessonModel(
        id = "ru.lesson.x.001",
        language = "ru",
        title = "En el mercado",
        objective = "Comprar fruta.",
        l10n = mapOf("en" to mapOf("title" to "At the market", "objective" to "Buy fruit.")),
    )

    @Test
    fun `a lesson reads in the interface language it carries`() {
        val english = lesson.localized("en")

        assertEquals("At the market", english.title)
        assertEquals("Buy fruit.", english.objective)
    }

    @Test
    fun `a language it does not carry gets the authored text - also after another language was applied`() {
        assertEquals("En el mercado", lesson.localized("es").title)
        assertEquals("En el mercado", lesson.localized("en").localized("fr").title)
        assertEquals("At the market", lesson.localized("en").localized("es").localized("en").title)
    }

    @Test
    fun `a missing field falls back to the authored one`() {
        val partial = lesson.copy(l10n = mapOf("de" to mapOf("title" to "Auf dem Markt")))

        assertEquals("Auf dem Markt", partial.localized("de").title)
        assertEquals("Comprar fruta.", partial.localized("de").objective)
    }

    @Test
    fun `a lesson without localized fields is left as it is`() {
        val plain = LessonModel(id = "en.lesson.x.001", title = "A Call Home")

        assertSame(plain, plain.localized("es"))
    }

    @Test
    fun `an exercise reads its prompt and options in the interface language and can go back`() {
        val match = ExerciseModel(
            id = "ru.exercise.x.001",
            language = "ru",
            type = "match",
            prompt = "Empareja cada palabra con su significado.",
            options = listOf("рейс=vuelo", "бага́ж=equipaje"),
            l10n = mapOf("en" to mapOf("prompt" to "Match each word with its meaning.", "opt0" to "рейс=flight")),
        )

        val english = match.localized("en")
        assertEquals("Match each word with its meaning.", english.prompt)
        // The option it does not carry stays as authored rather than disappearing.
        assertEquals(listOf("рейс=flight", "бага́ж=equipaje"), english.options)
        assertEquals(match.options, english.localized("es").options)
        assertEquals(match.prompt, english.localized("de").prompt)
    }

    @Test
    fun `a choice answer follows its option into each interface language`() {
        val choice = ExerciseModel(
            id = "ru.exercise.x.001",
            language = "ru",
            type = "multiple-choice",
            prompt = "¿Qué le dices a la cajera?",
            options = listOf("Здра́вствуйте", "Приве́т"),
            answer = "Здра́вствуйте",
            l10n = mapOf(
                "en" to mapOf("prompt" to "What do you say to the cashier?"),
                "de" to mapOf("prompt" to "Was sagst du?"),
            ),
        )
        val spanishChoice = ExerciseModel(
            id = "ru.exercise.x.002",
            language = "ru",
            type = "multiple-choice",
            prompt = "¿Quién diría esto?",
            options = listOf("un amigo", "un cajero"),
            answer = "un amigo",
            l10n = mapOf(
                "en" to mapOf("opt0" to "a friend", "opt1" to "a cashier"),
                "de" to mapOf("opt0" to "ein Freund", "opt1" to "ein Kassierer"),
            ),
        )
        assertEquals("Здра́вствуйте", choice.localized("en").answer)
        val english = spanishChoice.localized("en")
        assertEquals("a friend", english.answer)
        assertEquals("ein Freund", english.localized("de").answer)
        assertEquals("un amigo", english.localized("de").localized("es").answer)
    }
}
