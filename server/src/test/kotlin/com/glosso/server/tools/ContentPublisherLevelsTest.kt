package com.glosso.server.tools

import com.glosso.engine.drills.DrillBankLoader
import com.glosso.engine.model.ContentManifest
import com.glosso.engine.model.DialogueBank
import com.glosso.engine.model.ExerciseBank
import com.glosso.engine.model.LessonBank
import com.glosso.engine.model.NoteKind
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Runs the publisher on a tiny Russian course to pin two things end to end: a
 * lesson's culture notes reach the published bank intact, and `--levels` leaves
 * out every item of another level plus any lesson that would use one.
 */
class ContentPublisherLevelsTest {

    private val root = createTempDirectory("publisher-levels").toFile()
    private val content = File(root, "ru").apply { mkdirs() }
    private val out = File(root, "dist")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun write(path: String, text: String) {
        File(content, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun dialogue(id: String, level: String) = """
        {"id": "$id", "language": "ru", "level": "$level", "theme": "greetings", "title": "Привет",
         "cast": [{"name": "Анна", "gender": "FEMALE"}],
         "turns": [{"speaker": "Анна", "text": "Привет!", "ipa": "prʲivʲet"}]}
    """

    private fun exercise(id: String, level: String) = """
        {"id": "$id", "language": "ru", "level": "$level", "theme": "greetings", "type": "true-false",
         "prompt": "Привет es formal.", "options": ["true", "false"], "answer": "false"}
    """

    private fun lesson(id: String, level: String, dialogue: String, exercises: List<String>, notes: String = "[]") = """
        {"id": "$id", "language": "ru", "level": "$level", "theme": "greetings", "title": "Saludar",
         "objective": "Saludar.", "dialogueId": "$dialogue",
         "exerciseIds": [${exercises.joinToString { "\"$it\"" }}], "notes": $notes}
    """

    private val notes = """
        [{"kind": "ETIQUETTE", "title": "Ты o вы", "body": "Con desconocidos se usa вы.",
          "examples": [{"text": "Здра́вствуйте!", "translations": {"es": "¡Buenos días!"}},
                       {"text": "Приве́т!", "translations": {"es": "¡Hola!"}, "wrong": true}]}]
    """

    private fun publish(vararg extra: String) {
        write("ru.lexicon", "привет prʲivʲet\n")
        write(
            "dialogues/greetings.json",
            """{"version": 1, "language": "ru", "dialogues": [${dialogue("d.a1", "A1")}, ${dialogue("d.a2", "A2")}]}""",
        )
        write(
            "exercises/greetings.json",
            """{"version": 1, "language": "ru", "exercises": [
                ${exercise("e.a1.1", "A1")}, ${exercise("e.a1.2", "A1")},
                ${exercise("e.a2.1", "A2")}, ${exercise("e.a2.2", "A2")}]}""",
        )
        write(
            "lessons/greetings.json",
            """{"version": 1, "language": "ru", "lessons": [
                ${lesson("l.a1", "A1", "d.a1", listOf("e.a1.1", "e.a1.2"), notes)},
                ${lesson("l.a1.uses.a2", "A1", "d.a1", listOf("e.a1.1", "e.a2.1"))},
                ${lesson("l.a2", "A2", "d.a2", listOf("e.a2.1", "e.a2.2"))}]}""",
        )
        write("manifest.json", """{"language": "ru", "levels": ["A1", "A2"]}""")
        main(arrayOf("--content", content.path, "--language", "ru", "--out", out.path) + extra)
    }

    private fun lessons() = DrillBankLoader.json.decodeFromString(LessonBank.serializer(), File(out, "lessons/greetings.json").readText()).lessons

    @Test
    fun `notes reach the published bank intact`() {
        publish()

        val note = lessons().first { it.id == "l.a1" }.notes.single()
        assertEquals(NoteKind.ETIQUETTE, note.kind)
        assertEquals("Здра́вствуйте!", note.examples[0].text)
        assertEquals(true, note.examples[1].wrong)
        assertEquals(listOf("l.a1", "l.a1.uses.a2", "l.a2"), lessons().map { it.id })
    }

    @Test
    fun `only the published levels ship, and no lesson that needs another level`() {
        publish("--levels", "A1")

        assertEquals(listOf("l.a1"), lessons().map { it.id })
        val dialogues = DrillBankLoader.json.decodeFromString(DialogueBank.serializer(), File(out, "dialogues/greetings.json").readText())
        assertEquals(listOf("d.a1"), dialogues.dialogues.map { it.id })
        val exercises = DrillBankLoader.json.decodeFromString(ExerciseBank.serializer(), File(out, "exercises/greetings.json").readText())
        assertEquals(listOf("e.a1.1", "e.a1.2"), exercises.exercises.map { it.id })
        val manifest = DrillBankLoader.json.decodeFromString(ContentManifest.serializer(), File(out, "manifest.json").readText())
        assertEquals(listOf("A1"), manifest.levels)
        assertEquals(setOf(listOf("A1")), manifest.banks.map { it.levels }.toSet())
    }

    @Test
    fun `levels are parsed leniently and an unknown one is refused`() {
        assertNull(parseLevels(null))
        assertNull(parseLevels(" "))
        assertEquals(setOf("A1", "A2"), parseLevels("a1, A2"))
        assertFailsWith<IllegalArgumentException> { parseLevels("A1,Z9") }
    }
}
