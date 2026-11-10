package com.glosso.server.tools

import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.LessonNote
import com.glosso.engine.model.NoteExample
import com.glosso.engine.model.NoteKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LessonNotesValidationTest {

    private val translations = mapOf("es" to "¡Buenos días!", "en" to "Hello!")

    private fun note(
        title: String = "Ты o вы",
        body: String = "Con desconocidos y mayores se usa вы.",
        examples: List<NoteExample> = listOf(NoteExample("Здра́вствуйте!", translations)),
        l10n: Map<String, Map<String, String>> = mapOf("en" to mapOf("title" to "Ty or vy", "body" to "Use vy with strangers.")),
    ) = LessonNote(NoteKind.REGISTER, title, body, examples, l10n)

    private fun lesson(notes: List<LessonNote>) = LessonModel(
        id = "ru.lesson.greetings.001",
        language = "ru",
        level = "A1",
        theme = "greetings",
        title = "Saludar",
        objective = "Saludar a alguien.",
        dialogueId = "d1",
        exerciseIds = listOf("e1", "e2"),
        l10n = mapOf("en" to mapOf("title" to "Greeting", "objective" to "Greet someone.")),
        notes = notes,
    )

    private fun problems(vararg notes: LessonNote) =
        ContentValidator.validateLesson(lesson(notes.toList()), setOf("d1"), emptySet(), emptySet(), setOf("e1", "e2"), emptySet(), "ru")
            .problems.filter { it.code == "notes" }.map { it.detail }

    @Test
    fun `a complete note passes, and so does a lesson without notes`() {
        assertEquals(emptyList(), problems(note()))
        assertEquals(emptyList(), problems())
    }

    @Test
    fun `a lesson opens with at most four notes`() {
        assertEquals(emptyList(), problems(note(), note(), note(), note()))
        assertTrue(problems(note(), note(), note(), note(), note()).single().startsWith("5 notes"))
    }

    @Test
    fun `title and body must be written`() {
        assertEquals(listOf("note 0 blank title"), problems(note(title = " ")))
        assertTrue("note 0 blank body" in problems(note(body = "")))
    }

    @Test
    fun `a body stays short`() {
        assertEquals(emptyList(), problems(note(body = List(90) { "palabra" }.joinToString(" "))))
        assertEquals(1, problems(note(body = List(91) { "palabra" }.joinToString(" "))).size)
        val longFrench = mapOf("en" to mapOf("title" to "Ty or vy", "body" to List(110) { "word" }.joinToString(" ")))
        assertEquals(emptyList(), problems(note(l10n = longFrench)))
        val tooLongEnglish = mapOf("en" to mapOf("title" to "Ty or vy", "body" to List(111) { "word" }.joinToString(" ")))
        assertEquals(listOf("note 0 en body over 110 words"), problems(note(l10n = tooLongEnglish)))
    }

    @Test
    fun `every example has its phrase`() {
        assertEquals(listOf("note 0 example 0 blank text"), problems(note(examples = listOf(NoteExample(" ", translations)))))
    }

    @Test
    fun `a note is translated into every interface language the lesson is`() {
        assertEquals(listOf("note 0 has no en title and body"), problems(note(l10n = emptyMap())))
        assertEquals(
            listOf("note 0 has no en title and body"),
            problems(note(l10n = mapOf("en" to mapOf("title" to "Ty or vy")))),
        )
    }

    @Test
    fun `an example carries its meaning in Spanish and in every language of the lesson`() {
        val noEnglish = listOf(NoteExample("Приве́т!", mapOf("es" to "¡Hola!")))
        assertEquals(listOf("note 0 example 0 has no en translation"), problems(note(examples = noEnglish)))
        val wrong = listOf(NoteExample("Приве́т!", mapOf("es" to "¡Hola!", "en" to "Hi!"), wrong = true))
        assertEquals(emptyList(), problems(note(examples = wrong)))
    }

    @Test
    fun `a lesson without l10n needs only the Spanish meaning`() {
        val plain = lesson(listOf(note(l10n = emptyMap(), examples = listOf(NoteExample("Привет!", mapOf("es" to "¡Hola!"))))))
            .copy(l10n = emptyMap())
        assertFalse(ContentValidator.validateLesson(plain, setOf("d1"), emptySet(), emptySet(), setOf("e1", "e2"), emptySet(), "ru").problems.any { it.code == "notes" })
    }
}
