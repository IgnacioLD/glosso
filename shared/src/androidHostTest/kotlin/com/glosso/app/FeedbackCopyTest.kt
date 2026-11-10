package com.glosso.app

import com.glosso.app.resources.*
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The feedback must never fall back to another language or leave two bare IPA
 * symbols unexplained: every sound guide and the contrast frame exist in all four
 * locales, and the guide lookup actually resolves for the curated sounds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedbackCopyTest {

    private val locales = listOf(
        Locale.ENGLISH,
        Locale.forLanguageTag("es"),
        Locale.FRENCH,
        Locale.forLanguageTag("de"),
    )

    @Test
    fun `every sound guide is translated in all supported languages`() {
        val guides = Res.allStringResources.filterKeys { it.startsWith("sound_guide_") }
        assertTrue("no sound_guide_* resources found", guides.isNotEmpty())

        val english = LocalizedStrings(Locale.ENGLISH)
        for (locale in locales) {
            val context = LocalizedStrings(locale)
            for ((name, id) in guides) {
                val value = context.getString(id)
                assertTrue("$name is blank for $locale", value.isNotBlank())
                if (locale != Locale.ENGLISH) {
                    assertNotEquals(
                        "$name is not translated for $locale",
                        english.getString(id),
                        value,
                    )
                }
            }
        }
    }

    @Test
    fun `the contrast frame is translated and keeps its word placeholder`() {
        val english = LocalizedStrings(Locale.ENGLISH)
        for (locale in locales) {
            val context = LocalizedStrings(locale)
            val inWord = context.getString(Res.string.practice_error_in_word, "think")
            assertTrue("in-word line dropped the word for $locale", inWord.contains("think"))
            for (id in listOf(Res.string.practice_example_wrong, Res.string.practice_example_right)) {
                val value = context.getString(id)
                assertTrue("$id is blank for $locale", value.isNotBlank())
                if (locale != Locale.ENGLISH) {
                    assertNotEquals(
                        "contrast frame is not translated for $locale",
                        english.getString(id),
                        value,
                    )
                }
            }
        }
    }

    @Test
    fun `the verdict labels are translated in all supported languages`() {
        val english = LocalizedStrings(Locale.ENGLISH)
        val labels = listOf(
            Res.string.level_perfect,
            Res.string.level_mastered,
            Res.string.level_almost,
            Res.string.level_rough,
            Res.string.level_not_yet,
        )
        for (locale in locales) {
            val context = LocalizedStrings(locale)
            for (id in labels) {
                val value = context.getString(id)
                assertTrue("$id is blank for $locale", value.isNotBlank())
                if (locale != Locale.ENGLISH) {
                    assertNotEquals("$id is not translated for $locale", english.getString(id), value)
                }
            }
        }
    }

    @Test
    fun `curated sounds resolve to a guide and unknown ones do not`() {
        for (phoneme in listOf("θ", "ð", "s", "z", "ʃ", "ɹ", "ŋ", "ɪ", "i", "æ", "ɑ̃", "ɔ̃", "y", "ʁ")) {
            assertNotNull("no guide for /$phoneme/", SoundGuide.guideFor(phoneme))
        }
        // Long and short forms share one guide.
        assertTrue(SoundGuide.guideFor("i") == SoundGuide.guideFor("iː"))
        assertTrue("unknown sound should have no guide", SoundGuide.guideFor("ʔ") == null)
    }

    @Test
    fun `the contrast marks exactly the letters that differ`() {
        // The shared letters stay shared: the doubled "l" of pull is not the
        // contrast, and the insertion of "o" in roue still lights up rue.
        assertEquals(setOf(1, 2), differingIndices("pull", "pool"))
        assertEquals(setOf(1, 2), differingIndices("pool", "pull"))
        assertEquals(setOf(0, 2, 3), differingIndices("right", "white"))
        assertEquals(setOf(0, 2, 4), differingIndices("white", "right"))
        assertEquals(setOf(1), differingIndices("rue", "roue"))
        assertEquals(setOf(1), differingIndices("roue", "rue"))
        assertEquals(setOf(0, 1), differingIndices("think", "sink"))
        assertEquals(setOf(0), differingIndices("sink", "think"))
        assertEquals(setOf(0, 1, 2), differingIndices("they", "day"))
        assertEquals(setOf(0), differingIndices("right", "light"))
        assertTrue(differingIndices("same", "same").isEmpty())
    }
}
