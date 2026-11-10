package com.glosso.app

import com.glosso.app.resources.*
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "change phone" copy exists and is localized in the four supported
 * languages, and it keeps the promises the flow depends on: it works without
 * personal data and only keeps Google's opaque account id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChangePhoneCopyTest {

    private val locales = listOf(
        Locale.forLanguageTag("es"),
        Locale.ENGLISH,
        Locale.FRENCH,
        Locale.GERMAN,
    )

    private val keys = listOf(
        Res.string.settings_account,
        Res.string.settings_recovery,
        Res.string.settings_recovery_subtitle,
        Res.string.recovery_title,
        Res.string.recovery_intro,
        Res.string.google_section,
        Res.string.google_desc,
        Res.string.google_linked,
        Res.string.google_continue,
        Res.string.google_privacy,
        Res.string.google_success,
        Res.string.google_error_no_account,
        Res.string.google_error_failed,
        Res.string.google_error_conflict,
    )

    @Test
    fun `recovery strings exist and are localized in all supported languages`() {
        for (locale in locales) {
            val localized = LocalizedStrings(locale)

            for (key in keys) {
                val value = localized.getString(key)
                assertTrue("$locale: string $key is blank", value.isNotBlank())
                assertFalse("$locale: string $key uses an em dash", value.contains('\u2014'))
            }
            val linked = localized.getString(Res.string.google_linked, "ana@example.com")
            assertTrue("$locale: linked does not name the account", linked.contains("ana@example.com"))
            // The privacy promise names what is never accessed.
            val privacy = localized.getString(Res.string.google_privacy)
            assertTrue("$locale: privacy copy must mention Gmail", privacy.contains("Gmail"))
            assertTrue("$locale: privacy copy must mention Drive", privacy.contains("Drive"))
        }
    }
}
