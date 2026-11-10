package com.glosso.server.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioValidatorTest {

    private val validator = AudioValidator(maxDurationMs = 60_000)

    @Test
    fun `accepts a valid mp3`() {
        val result = validator.validate(validAudioBytes(), AudioFormat(), declaredDurationMs = 1_000)
        assertTrue(result.valid)
        assertEquals(64, result.checksum.length)
        assertTrue(result.sizeBytes > 0)
    }

    @Test
    fun `rejects empty and tiny payloads`() {
        assertEquals("empty_audio", validator.validate(ByteArray(0), AudioFormat(), null).reason)
        assertEquals("too_small", validator.validate(ByteArray(10), AudioFormat(), null).reason)
    }

    @Test
    fun `rejects a json error body served as audio`() {
        val body = """{"error":"boom"}""".toByteArray()
        val result = validator.validate(body, AudioFormat(), null)
        assertFalse(result.valid)
        // It is also below min size, which is fine; either reason blocks publish.
        assertTrue(result.reason == "corrupt_response" || result.reason == "too_small")
    }

    @Test
    fun `rejects the wrong container`() {
        val ogg = ByteArray(256) { 0x00 }
        ogg[0] = 'O'.code.toByte()
        ogg[1] = 'g'.code.toByte()
        ogg[2] = 'g'.code.toByte()
        ogg[3] = 'S'.code.toByte()
        assertEquals("wrong_format", validator.validate(ogg, AudioFormat(), null).reason)
    }

    @Test
    fun `rejects an implausible duration`() {
        assertEquals(
            "duration_too_long",
            validator.validate(validAudioBytes(), AudioFormat(), declaredDurationMs = 120_000).reason,
        )
        assertEquals(
            "non_positive_duration",
            validator.validate(validAudioBytes(), AudioFormat(), declaredDurationMs = 0).reason,
        )
    }

    @Test
    fun `checksum is deterministic`() {
        val bytes = validAudioBytes()
        assertEquals(
            validator.validate(bytes, AudioFormat(), null).checksum,
            validator.validate(bytes, AudioFormat(), null).checksum,
        )
    }
}
