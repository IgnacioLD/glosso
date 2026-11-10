package com.glosso.server.tools

import com.glosso.engine.model.ExerciseModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FillBlankWordBankTest {

    private fun gap(options: List<String>) = ExerciseModel(
        id = "ru.exercise.greetings.001",
        language = "ru",
        level = "A1",
        theme = "greetings",
        type = "fill-blank",
        prompt = "Анна, ___ это?",
        options = options,
        answer = "кто",
    )

    @Test
    fun `a gap is typed without options or chosen from a word bank that holds the answer`() {
        assertTrue(ContentValidator.validateExercise(gap(emptyList()), "ru").ok)
        assertTrue(ContentValidator.validateExercise(gap(listOf("там", "кто", "он")), "ru").ok)
    }

    @Test
    fun `a word bank without the answer, with one word or with a repeated word is rejected`() {
        assertFalse(ContentValidator.validateExercise(gap(listOf("там", "он")), "ru").ok)
        assertFalse(ContentValidator.validateExercise(gap(listOf("кто")), "ru").ok)
        assertFalse(ContentValidator.validateExercise(gap(listOf("кто", "кто", "он")), "ru").ok)
    }
}
