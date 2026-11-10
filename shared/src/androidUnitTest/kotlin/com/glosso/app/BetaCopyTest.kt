package com.glosso.app

import com.glosso.app.product.formatBetaEndDate
import com.glosso.app.resources.*
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BetaCopyTest {

    private val locales = listOf(
        Locale.forLanguageTag("es"),
        Locale.ENGLISH,
        Locale.FRENCH,
        Locale.GERMAN,
    )

    @Test
    fun `beta banner strings exist and are localized in all supported languages`() {
        for (locale in locales) {
            val strings = LocalizedStrings(locale)

            val title = strings.getString(Res.string.beta_open_title)
            val freeUntil = strings.getString(Res.string.beta_free_until, "31/12/2027")
            val freeDuring = strings.getString(Res.string.beta_free_during)
            val founderNote = strings.getString(Res.string.beta_founder_note)
            val lifetimeFounder = strings.getString(Res.string.entitlement_lifetime_founder)
            val lifetimePro = strings.getString(Res.string.entitlement_lifetime_pro)

            assertNotNull(title)
            assertTrue(title.isNotBlank())
            assertTrue(freeUntil.contains("31/12/2027"))
            assertTrue(freeDuring.isNotBlank())
            assertTrue(founderNote.isNotBlank())
            assertTrue(lifetimeFounder.isNotBlank())
            assertTrue(lifetimePro.isNotBlank())

            // The whole course is not claimed to be free forever (personalized is excluded)
            assertFalse(freeUntil.lowercase().contains("para siempre"))
            assertFalse(freeUntil.lowercase().contains("forever"))
            assertFalse(freeDuring.lowercase().contains("para siempre"))
            assertFalse(freeDuring.lowercase().contains("forever"))
        }
    }

    @Test
    fun `no beta string hardcodes dates`() {
        val dateRegex = Regex("(202[5-9]|diciembre|december|décembre|dezember)", RegexOption.IGNORE_CASE)

        for (locale in locales) {
            val strings = LocalizedStrings(locale)

            val rawFreeUntil = strings.getString(Res.string.beta_free_until)
            val rawFreeDuring = strings.getString(Res.string.beta_free_during)
            val rawFounder = strings.getString(Res.string.beta_founder_note)

            assertFalse("free_until must not hardcode date: $rawFreeUntil", dateRegex.containsMatchIn(rawFreeUntil))
            assertFalse("free_during must not hardcode date: $rawFreeDuring", dateRegex.containsMatchIn(rawFreeDuring))
            assertFalse("founder_note must not hardcode date: $rawFounder", dateRegex.containsMatchIn(rawFounder))
        }
    }

    @Test
    fun `formatBetaEndDate formats correctly and handles missing or invalid dates`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("December 31, 2027", formatBetaEndDate("2027-12-31T23:59:59Z"))
            assertEquals("December 31, 2026", formatBetaEndDate("2026-12-31T23:59:59Z"))
            assertNull(formatBetaEndDate(null))
            assertNull(formatBetaEndDate(""))
            assertNull(formatBetaEndDate("invalid-date"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}
