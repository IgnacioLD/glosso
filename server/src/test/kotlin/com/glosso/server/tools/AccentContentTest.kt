package com.glosso.server.tools

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccentContentTest {

    private fun content(vararg text: Pair<String, String>): AccentContent {
        val dir = Files.createTempDirectory("accents").toFile().apply { deleteOnExit() }
        val accents = java.io.File(dir, "accents").apply { mkdirs() }
        val entries = text.joinToString(",") { (key, value) -> "\"$key\":\"$value\"" }
        java.io.File(accents, "en-US.json").writeText("""{"version":1,"accent":"en-US","text":{$entries}}""")
        return AccentContent.load(dir)
    }

    @Test
    fun `display fields are picked by the key that holds them and left out when the accent has none`() {
        val accents = content(
            "en.dialogue.family.001#title" to "A phone call with Mom",
            "en.dialogue.family.001#speaker:Mum" to "Mom",
        )

        val fields = accents.fields(
            mapOf(
                "title" to "en.dialogue.family.001#title",
                "speaker0" to "en.dialogue.family.001#speaker:Mum",
                "speaker1" to "en.dialogue.family.001#speaker:Tom",
                "cast0" to "en.dialogue.family.001#speaker:Mum",
            ),
        )

        assertEquals(
            mapOf("en-US" to mapOf("title" to "A phone call with Mom", "speaker0" to "Mom", "cast0" to "Mom")),
            fields,
        )
        assertEquals(emptyMap(), accents.fields(mapOf("title" to "en.dialogue.city.001#title")))
        assertEquals(emptyList(), accents.problems())
    }

    @Test
    fun `a wording key that no item uses stops the publish`() {
        val accents = content("en.exercise.gone.001#prompt" to "Where is the store?")

        accents.fields(mapOf("prompt" to "en.exercise.city.001#prompt"))

        assertTrue(accents.problems().single().contains("en.exercise.gone.001#prompt"))
    }
}
