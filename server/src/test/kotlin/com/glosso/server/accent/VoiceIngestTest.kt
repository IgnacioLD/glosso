package com.glosso.server.accent

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class VoiceIngestTest {

    private val id = "8e14933d-ecd7-402b-9505-795130d69b35"
    private val wav = "RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray() + ByteArray(2048)

    private fun wavBase64(): String = Base64.getEncoder().encodeToString(wav)

    @Test
    fun `the fetcher is chosen from the environment and defaults to none`() {
        val dir = Files.createTempDirectory("glosso-voices-env").toFile()
        val env = mutableMapOf<String, String>()
        assertIs<UnconfiguredVoiceFetcher>(VoiceAudioFetchers.fromEnv(env::get))
        env["GLOSSO_VOICE_FETCHER"] = "local"
        assertIs<UnconfiguredVoiceFetcher>(VoiceAudioFetchers.fromEnv(env::get), "local without a dir is off")
        env["GLOSSO_VOICE_LOCAL_DIR"] = dir.path
        assertIs<LocalFileVoiceFetcher>(VoiceAudioFetchers.fromEnv(env::get))
        env["GLOSSO_VOICE_FETCHER"] = "some-provider"
        assertIs<UnconfiguredVoiceFetcher>(VoiceAudioFetchers.fromEnv(env::get), "an unknown provider is never guessed")
    }

    @Test
    fun `multi-accent scoring is off unless the flag is set`() {
        assertEquals(false, MultiAccentConfig.fromEnv { null }.enabled)
        assertEquals(false, MultiAccentConfig.fromEnv { "0" }.enabled)
        assertEquals(true, MultiAccentConfig.fromEnv { "1" }.enabled)
    }

    @Test
    fun `audio containers are recognised by their magic bytes`() {
        fun bytes(prefix: String) = prefix.toByteArray() + ByteArray(16)
        assertEquals(AudioContainer.WAV, AudioContainer.sniff("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()))
        assertEquals(AudioContainer.OGG, AudioContainer.sniff(bytes("OggS")))
        assertEquals(AudioContainer.FLAC, AudioContainer.sniff(bytes("fLaC")))
        assertEquals(AudioContainer.MP3, AudioContainer.sniff(bytes("ID3")))
        assertNull(AudioContainer.sniff(bytes("<html>")))
    }

    @Test
    fun `re-registering a voice replaces its audio and keeps one entry`() = runBlocking {
        val dir = Files.createTempDirectory("glosso-voices-registry").toFile()
        val registry = VoiceRegistry(dir)
        val service = VoiceIngestService(registry, UnconfiguredVoiceFetcher, now = { 1L })
        val ogg = "OggS".toByteArray() + ByteArray(2048)
        val encoder = java.util.Base64.getEncoder()

        assertIs<VoiceIngestService.Outcome.Registered>(
            service.register(VoiceIngestService.Request(id, "en-US-NY", "male", audioBase64 = encoder.encodeToString(wav))),
        )
        assertIs<VoiceIngestService.Outcome.Registered>(
            service.register(VoiceIngestService.Request(id, "en-US-NY", "male", audioBase64 = encoder.encodeToString(ogg))),
        )
        assertEquals(1, registry.all().size)
        assertEquals("$id.ogg", registry.get(id)?.audioFile)
        assertEquals(false, File(dir, "$id.wav").exists())
        assertEquals(true, File(dir, "$id.ogg").isFile)
    }

    @Test
    fun `registering a voice creates a missing registry directory`() = runBlocking {
        val root = Files.createTempDirectory("glosso-voices-missing").toFile()
        val dir = File(root, ".voices")
        assertFalse(dir.exists())
        val registry = VoiceRegistry(dir)
        val service = VoiceIngestService(registry, UnconfiguredVoiceFetcher, now = { 1L })

        assertIs<VoiceIngestService.Outcome.Registered>(
            service.register(VoiceIngestService.Request(id, "en-US-NY", "male", audioBase64 = wavBase64())),
        )

        assertTrue(dir.isDirectory)
        assertTrue(File(dir, "$id.wav").isFile)
        assertTrue(File(dir, "registry.json").isFile)
        assertEquals(1, registry.all().size)
    }

    @Test
    fun `a storage failure is a clear storage error, not a crash`() = runBlocking {
        val blocker = Files.createTempFile("glosso-voices-blocked", ".file").toFile()
        val dir = File(blocker, ".voices")
        val registry = VoiceRegistry(dir)
        val service = VoiceIngestService(registry, UnconfiguredVoiceFetcher, now = { 1L })

        val outcome = service.register(VoiceIngestService.Request(id, "en-US-NY", "male", audioBase64 = wavBase64()))

        val rejected = assertIs<VoiceIngestService.Outcome.Rejected>(outcome)
        assertEquals(500, rejected.status)
        assertEquals("storage_error", rejected.reason)
        assertTrue(rejected.message.contains(dir.path), "message names the failing path: ${rejected.message}")
    }
}
