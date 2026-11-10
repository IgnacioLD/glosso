package com.glosso.engine.model

import com.glosso.engine.drills.DrillBankLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class LessonNotesTest {

    private val publishJson = Json { prettyPrint = true; encodeDefaults = true }


    /** The shape content authors write, exactly. */
    private val authored = """
        {
          "version": 1,
          "language": "ru",
          "lessons": [{
            "id": "ru.lesson.greetings.001",
            "language": "ru",
            "level": "A1",
            "theme": "greetings",
            "title": "Saludar",
            "objective": "Saludar a alguien.",
            "dialogueId": "ru.dialogue.greetings.001",
            "l10n": {"en": {"title": "Greeting", "objective": "Greet someone."}},
            "notes": [{
              "kind": "REGISTER",
              "title": "Ты o вы",
              "body": "Con desconocidos y mayores se usa вы.",
              "examples": [
                {"text": "Здра́вствуйте!", "translations": {"es": "¡Buenos días!", "en": "Hello!"}},
                {"text": "Приве́т!", "translations": {"es": "¡Hola!", "en": "Hi!"}, "wrong": true}
              ],
              "l10n": {
                "en": {"title": "Ty or vy", "body": "With strangers and elders, use vy."},
                "de": {"title": "Ty oder vy"}
              }
            }]
          }]
        }
    """.trimIndent()

    private val bank = DrillBankLoader.json.decodeFromString(LessonBank.serializer(), authored)
    private val lesson = bank.lessons.single()

    @Test
    fun `the authored notes decode with every field`() {
        val note = lesson.notes.single()
        assertEquals(NoteKind.REGISTER, note.kind)
        assertEquals("Ты o вы", note.title)
        assertEquals(2, note.examples.size)
        assertEquals("Здра́вствуйте!", note.examples[0].text)
        assertEquals(false, note.examples[0].wrong)
        assertEquals(true, note.examples[1].wrong)
        assertEquals("Hi!", note.examples[1].translations["en"])
    }

    @Test
    fun `a lesson with notes survives a publish round trip unchanged`() {
        val published = publishJson.encodeToString(LessonBank.serializer(), bank)

        assertEquals(bank, DrillBankLoader.json.decodeFromString(LessonBank.serializer(), published))
    }

    @Test
    fun `a lesson without notes still decodes`() {
        val plain = DrillBankLoader.json.decodeFromString(
            LessonModel.serializer(),
            """{"id": "en.lesson.x.001", "title": "A Call Home"}""",
        )

        assertTrue(plain.notes.isEmpty())
    }

    @Test
    fun `notes read in the interface language and fall back per field`() {
        assertEquals("Ty or vy", lesson.localized("en").notes.single().title)
        assertEquals("With strangers and elders, use vy.", lesson.localized("en").notes.single().body)

        val german = lesson.localized("de").notes.single()
        assertEquals("Ty oder vy", german.title)
        assertEquals("Con desconocidos y mayores se usa вы.", german.body)

        assertEquals("Ты o вы", lesson.localized("fr").notes.single().title)
    }

    @Test
    fun `notes can be localized again into another language`() {
        val back = lesson.localized("en").localized("es").notes.single()

        assertEquals("Ты o вы", back.title)
        assertEquals("Con desconocidos y mayores se usa вы.", back.body)
        assertEquals("Ty or vy", lesson.localized("de").localized("en").notes.single().title)
    }

    @Test
    fun `notes are localized even when the lesson itself carries no l10n`() {
        val noLessonL10n = lesson.copy(l10n = emptyMap())

        assertEquals("Ty or vy", noLessonL10n.localized("en").notes.single().title)
        assertEquals("Saludar", noLessonL10n.localized("en").title)
    }

    @Test
    fun `an exercise reads its instruction in the interface language and can go back`() {
        val exercise = ExerciseModel(
            id = "ru.exercise.x.001",
            language = "ru",
            type = "multiple-choice",
            prompt = "¿Qué dices al entrar?",
            options = listOf("a", "b", "c"),
            answer = "a",
            instruction = "Elige el saludo adecuado.",
            l10n = mapOf("en" to mapOf("instruction" to "Pick the right greeting.")),
        )

        assertEquals("Pick the right greeting.", exercise.localized("en").instruction)
        assertEquals("Elige el saludo adecuado.", exercise.localized("en").localized("es").instruction)
        assertEquals("Elige el saludo adecuado.", exercise.localized("de").instruction)
    }

    @Test
    fun `an exercise without an instruction keeps none`() {
        val exercise = ExerciseModel(
            id = "ru.exercise.x.002",
            language = "ru",
            type = "true-false",
            prompt = "Привет es formal.",
            options = listOf("true", "false"),
            answer = "false",
            l10n = mapOf("en" to mapOf("prompt" to "Привет is formal.", "instruction" to "Ignored")),
        )

        assertNull(exercise.localized("en").instruction)
    }
}
