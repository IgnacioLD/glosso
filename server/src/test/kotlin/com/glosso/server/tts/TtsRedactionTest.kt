package com.glosso.server.tts

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TtsRedactionTest {

    @Test
    fun `scrubs cartesia style keys`() {
        val scrubbed = TtsRedaction.scrub("failed with sk_car_ABCDEF123456 while calling")
        assertFalse(scrubbed.contains("sk_car_ABCDEF123456"))
        assertTrue(scrubbed.contains("***"))
    }

    @Test
    fun `scrubs bearer tokens`() {
        val scrubbed = TtsRedaction.scrub("Authorization: Bearer abcdef0123456789")
        assertFalse(scrubbed.contains("abcdef0123456789"))
    }

    @Test
    fun `scrubs api key assignments`() {
        val scrubbed = TtsRedaction.scrub("xi-api-key=abcdef123456")
        assertFalse(scrubbed.contains("abcdef123456"))
    }

    @Test
    fun `leaves ordinary text untouched`() {
        assertTrue(TtsRedaction.scrub("cartesia server error (503)").contains("503"))
    }

    @Test
    fun `strips newlines so a vendor body cannot forge a log line`() {
        val scrubbed = TtsRedaction.scrub("boom\r\nFAKE 200 GET /admin\r\nlevel=ERROR")
        assertFalse(scrubbed.contains("\n"))
        assertFalse(scrubbed.contains("\r"))
    }
}
