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
 * The offline-model offer must read the same in all four locales and never
 * invent a size: every line carries its own translation, keeps the size
 * placeholder the caller fills with the model's real figure, and avoids an em
 * dash. A missing translation would silently fall back to English right where
 * the learner is deciding whether to spend hundreds of megabytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineModelCopyTest {

    private val locales = listOf(
        Locale.ENGLISH,
        Locale.forLanguageTag("es"),
        Locale.FRENCH,
        Locale.forLanguageTag("de"),
    )

    private val withSize = listOf(
        Res.string.offline_nudge_body,
        Res.string.onboarding_offline_body,
        Res.string.settings_ondevice_desc,
        Res.string.settings_download_model,
        Res.string.scoring_download_model,
    )

    private val plain = listOf(
        Res.string.offline_nudge_cta,
        Res.string.offline_nudge_hide,
        Res.string.offline_model_desc,
        Res.string.offline_model_not_downloaded,
        Res.string.onboarding_offline_title,
    )

    @Test
    fun `the offline model copy is translated in all supported languages`() {
        val english = LocalizedStrings(Locale.ENGLISH)

        for (locale in locales) {
            val context = LocalizedStrings(locale)
            for (id in plain) {
                val value = context.getString(id)
                assertTrue("$id is blank for $locale", value.isNotBlank())
                if (locale != Locale.ENGLISH) {
                    assertNotEquals("$id is not translated for $locale", english.getString(id), value)
                }
            }
        }
    }

    @Test
    fun `the size lines keep the placeholder and carry the real figure`() {
        val size = "999 MB"
        for (locale in locales) {
            val context = LocalizedStrings(locale)
            for (id in withSize) {
                val formatted = context.getString(id, size)
                assertTrue("$id dropped the size for $locale", formatted.contains(size))
            }
        }
    }

    @Test
    fun `no offline model copy hardcodes a size or uses an em dash`() {
        for (locale in locales) {
            val context = LocalizedStrings(locale)
            for (id in withSize + plain) {
                val raw = context.getString(id)
                assertFalse("$id hardcodes a size: $raw", raw.contains("342") || raw.contains("603"))
                assertFalse("$id uses an em dash: $raw", raw.contains("\u2014"))
            }
        }
    }
}
