package com.glosso.app

import com.glosso.engine.model.ContentManifest
import com.glosso.engine.model.DialogueModel
import com.glosso.engine.model.DialogueTurn
import com.glosso.engine.model.DrillModel
import com.glosso.engine.model.ExerciseModel
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.StoryModel
import com.glosso.engine.model.ThemeModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class ContentDrivenTest {

    @Test
    fun `manifest theme labels are resolved per ui language`() {
        val manifest = ContentManifest(
            levels = listOf("A1", "A2"),
            themes = listOf(ThemeModel("family", mapOf("en" to "Family", "es" to "Familia"))),
        )

        assertEquals(mapOf("family" to "Family"), manifest.themeLabels("en", "en-GB"))
        assertEquals(mapOf("family" to "Familia"), manifest.themeLabels("es", "en-GB"))
        assertEquals("Family", themeLabelFrom("family", manifest.themeLabels("en", "en-GB")))
    }

    @Test
    fun `a theme label published for the accent wins only when the ui is in that language`() {
        val manifest = ContentManifest(
            themes = listOf(ThemeModel("holidays", mapOf("en" to "Holidays", "en-US" to "Vacation", "es" to "Vacaciones"))),
        )

        assertEquals(mapOf("holidays" to "Vacation"), manifest.themeLabels("en", "en-US"))
        assertEquals(mapOf("holidays" to "Holidays"), manifest.themeLabels("en", "en-GB"))
        assertEquals(mapOf("holidays" to "Vacaciones"), manifest.themeLabels("es", "en-US"))
    }

    @Test
    fun `an unlabelled theme falls back to a readable id`() {
        assertNull(themeLabelFrom("travel", mapOf("family" to "Family")))
        assertEquals("Culture", readableThemeId("news-culture"))
        assertEquals("Study Plan", readableThemeId("study-plan"))
    }

    @Test
    fun `every published theme has a local fallback name`() {
        // The manifest ships empty labels for these (they come from the catalog
        // DB). Without a local fallback the Spanish UI would show the English id,
        // e.g. "APOLOGIES" between Spanish labels.
        val themesWithoutManifestLabels = listOf(
            "apologies", "complaints", "email", "gaming", "job-interview",
            "movies", "music", "news-entertainment", "news-history", "news-scifi",
            "news-technology", "opinions", "phone-calls", "plans", "reading",
            "small-talk", "storytelling", "university",
        )
        themesWithoutManifestLabels.forEach { id ->
            val fallback = themeFallbackRes[id] ?: themeFallbackRes[id.substringAfterLast("-")]
            assertTrue(fallback != null, "no local fallback name for theme '$id'")
        }
    }

    @Test
    fun `unknown exercise types are not claimed as supported`() {
        assertTrue("match" in SUPPORTED_EXERCISE_TYPES)
        assertTrue("multiple-choice" in SUPPORTED_EXERCISE_TYPES)
        assertFalse("drag-drop" in SUPPORTED_EXERCISE_TYPES)
    }

    @Test
    fun `a bundle lists every asset it needs without duplicates`() {
        val bundle = OfflineBundle(
            lesson = LessonModel(id = "l1", title = "L"),
            dialogue = DialogueModel(
                id = "d1",
                title = "D",
                image = "images/d1.png",
                turns = listOf(
                    DialogueTurn("A", "hi", audio = mapOf("en-US-female" to "audio/d1.t0.ogg")),
                    DialogueTurn("B", "hey", audio = mapOf("en-US-female" to "audio/d1.t0.ogg")),
                ),
            ),
            story = StoryModel(
                id = "s1",
                title = "S",
                image = "images/s1.png",
                paragraphAudio = listOf(mapOf("en-US-female" to "audio/s1.p0.ogg")),
            ),
            exercises = listOf(ExerciseModel(id = "e1", type = "x", prompt = "p", image = "images/e1.png")),
            drills = listOf(DrillModel(id = "dr1", target = "θ", text = "think", audio = mapOf("en-US-female" to "audio/dr1.ogg"))),
        )

        assertEquals(
            listOf(
                "images/d1.png",
                "audio/d1.t0.ogg",
                "images/s1.png",
                "audio/s1.p0.ogg",
                "images/e1.png",
                "audio/dr1.ogg",
            ),
            bundle.assetPaths(),
        )
    }

    @Test
    fun `answer comparison ignores punctuation anywhere`() {
        assertEquals(normalize("Come home at six, please."), normalize("Come home at six please"))
        assertEquals(normalize("Station."), normalize("station"))
        assertEquals(normalize("  Well  "), normalize("well"))
    }

    @Test
    fun `sizes read in sensible units`() {
        assertEquals("512 B", formatBytes(512, "en-US"))
        assertEquals("2 KB", formatBytes(2048, "en-US"))
        assertEquals("1.5 MB", formatBytes(1024L * 1024L + 512L * 1024L, "en-US"))
    }

    @Test
    fun `sizes follow the locale's number and unit style`() {
        val oneAndAHalfMb = 1024L * 1024L + 512L * 1024L
        assertEquals("1,5 MB", formatBytes(oneAndAHalfMb, "de"))
        assertEquals("1,5 Mo", formatBytes(oneAndAHalfMb, "fr"))
    }
}
