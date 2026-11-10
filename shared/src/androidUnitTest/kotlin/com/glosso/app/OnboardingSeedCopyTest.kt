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
 * The seed copy promised the learner that the phrases cover different sounds,
 * which is now true, so it must read the same in all four locales and never call
 * the sounds by a technical name. A missing translation would fall back to
 * English right at the step that introduces the product.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingSeedCopyTest {

    private val locales = listOf(
        Locale.ENGLISH,
        Locale.forLanguageTag("es"),
        Locale.FRENCH,
        Locale.forLanguageTag("de"),
    )

    private val copy = listOf(
        Res.string.onboarding_seed_title,
        Res.string.onboarding_seed_body,
        Res.string.onboarding_seed_next,
        Res.string.onboarding_seed_need_take,
        Res.string.onboarding_seed_skip,
        Res.string.onboarding_seed_done_title,
        Res.string.onboarding_seed_done_body,
        Res.string.onboarding_seed_error_title,
    )

    @Test
    fun `the seed copy is translated in all supported languages`() {
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
    fun `the progress line keeps both placeholders`() {
        for (locale in locales) {
            val formatted = LocalizedStrings(locale).getString(Res.string.onboarding_seed_progress, 2, 6)
            assertTrue("progress dropped the index for $locale", formatted.contains("2"))
            assertTrue("progress dropped the total for $locale", formatted.contains("6"))
        }
    }

    @Test
    fun `no seed copy uses an em dash`() {
        for (locale in locales) {
            val context = LocalizedStrings(locale)
            for (id in copy) {
                assertFalse("$id uses an em dash", context.getString(id).contains("\u2014"))
            }
        }
    }
}
