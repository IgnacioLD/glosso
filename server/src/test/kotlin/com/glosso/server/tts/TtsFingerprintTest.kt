package com.glosso.server.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TtsFingerprintTest {

    private fun key(
        text: String = "hello",
        language: String = "en",
        provider: String = "cartesia",
        model: String = "sonic-3.6",
        voice: String = "voice-a",
        speed: Double = 1.0,
        format: AudioFormat = AudioFormat(),
        providerOptions: Map<String, String> = emptyMap(),
    ) = TtsFingerprint.key(text, language, provider, model, voice, speed, format, providerOptions)

    @Test
    fun `normal and slow never collide`() {
        val normal = key(speed = 1.0)
        val slow = key(speed = 0.7)
        assertNotEquals(normal, slow)
    }

    @Test
    fun `same inputs produce the same key`() {
        assertEquals(key(), key())
    }

    @Test
    fun `every audio-affecting parameter changes the key`() {
        val base = key()
        assertNotEquals(base, key(text = "hello!"))
        assertNotEquals(base, key(language = "es"))
        assertNotEquals(base, key(provider = "elevenlabs"))
        assertNotEquals(base, key(model = "sonic-3.5"))
        assertNotEquals(base, key(voice = "voice-b"))
        assertNotEquals(base, key(format = AudioFormat(sampleRate = 24_000)))
        assertNotEquals(base, key(format = AudioFormat(AudioContainer.OGG)))
    }

    @Test
    fun `a provider option changes the key`() {
        val base = key(providerOptions = mapOf("emotion" to "neutral"))
        assertNotEquals(base, key(providerOptions = mapOf("emotion" to "excited")))
        assertNotEquals(base, key(providerOptions = mapOf("stability" to "0.5")))
        assertNotEquals(base, key())
    }

    @Test
    fun `provider options are hashed regardless of map order`() {
        val ordered = linkedMapOf("emotion" to "excited", "stability" to "0.5")
        val reversed = linkedMapOf("stability" to "0.5", "emotion" to "excited")
        assertEquals(key(providerOptions = ordered), key(providerOptions = reversed))
    }

    @Test
    fun `key is a sha256 hex string`() {
        val hash = key()
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun `object key uses the format extension`() {
        assertEquals("tts/abc.mp3", TtsFingerprint.objectKey("tts", "abc", AudioFormat()))
        assertEquals(
            "tts/abc.ogg",
            TtsFingerprint.objectKey("tts", "abc", AudioFormat(AudioContainer.OGG)),
        )
    }
}
