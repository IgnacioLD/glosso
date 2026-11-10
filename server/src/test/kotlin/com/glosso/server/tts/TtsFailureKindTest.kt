package com.glosso.server.tts

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TtsFailureKindTest {

    @Test
    fun `only timeout, rate limit, 5xx and connection are transient`() {
        val transient = TtsFailureKind.entries.filter { it.isTransient }.toSet()
        assertTrue(
            transient == setOf(
                TtsFailureKind.TIMEOUT,
                TtsFailureKind.RATE_LIMITED,
                TtsFailureKind.SERVER_ERROR,
                TtsFailureKind.CONNECTION,
            ),
            "unexpected transient set: $transient",
        )
    }

    @Test
    fun `content and configuration errors are permanent`() {
        assertFalse(TtsFailureKind.INVALID_TEXT.isTransient)
        assertFalse(TtsFailureKind.UNSUPPORTED_LANGUAGE.isTransient)
        assertFalse(TtsFailureKind.UNSUPPORTED_VOICE.isTransient)
        assertFalse(TtsFailureKind.UNSUPPORTED_FORMAT.isTransient)
        assertFalse(TtsFailureKind.BAD_REQUEST.isTransient)
        assertFalse(TtsFailureKind.AUTHENTICATION.isTransient)
        assertFalse(TtsFailureKind.CONFIGURATION.isTransient)
        assertFalse(TtsFailureKind.AUDIO_INVALID.isTransient)
    }
}
