package com.glosso.app

import com.glosso.app.resources.*
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "read this first" line names the base of a library exercise in all four
 * locales; a missing translation would greet the learner in English right where
 * they were confused. The story and lesson lines keep the title placeholder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExerciseSourceCopyTest {

    private val locales = listOf(
        Locale.ENGLISH,
        Locale.forLanguageTag("es"),
        Locale.FRENCH,
        Locale.forLanguageTag("de"),
    )

    private val copy = listOf(
        Res.string.exercise_source_story,
        Res.string.exercise_source_lesson,
        Res.string.exercise_source_read_first,
    )

    @Test
    fun `the source line is translated in all supported languages`() {
        val english = LocalizedStrings(Locale.ENGLISH)

        for (locale in locales) {
            val context = LocalizedStrings(locale)
            for (id in copy) {
                val value = context.getString(id)
                assertTrue("$id is blank for $locale", value.isNotBlank())
                if (locale != Locale.ENGLISH) {
                    assertNotEquals("$id is not translated for $locale", english.getString(id), value)
                }
            }
        }
    }

    @Test
    fun `the story and lesson lines keep the title`() {
        for (locale in locales) {
            val context = LocalizedStrings(locale)
            for (id in listOf(Res.string.exercise_source_story, Res.string.exercise_source_lesson)) {
                val formatted = context.getString(id, "The Blue Cup")
                assertTrue("$id dropped the title for $locale", formatted.contains("The Blue Cup"))
            }
        }
    }

    @Test
    fun `no source copy uses an em dash`() {
        for (locale in locales) {
            val context = LocalizedStrings(locale)
            for (id in copy) {
                assertFalse("$id uses an em dash", context.getString(id).contains("\u2014"))
            }
        }
    }
}
