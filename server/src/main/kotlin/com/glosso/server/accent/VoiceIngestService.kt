package com.glosso.server.accent

import com.glosso.engine.accent.AccentCatalog
import com.glosso.engine.model.VoiceGender
import java.security.MessageDigest
import java.util.Base64

/**
 * Registers a reference voice (UUID + accent + gender) and stores its audio,
 * inline or through the pluggable [VoiceAudioFetcher].
 *
 * [AccentCatalog] stays the reviewed source of truth for which voice belongs to
 * which system: a catalogued voice must be registered with its catalogued accent
 * and gender, and a new voice is stored but only joins a system once it is added
 * to the catalogue (docs/MULTI-ACCENT.md, "Anadir un acento").
 */
class VoiceIngestService(
    val registry: VoiceRegistry,
    val fetcher: VoiceAudioFetcher,
    private val now: () -> Long = System::currentTimeMillis,
) {

    data class Request(
        val voiceId: String,
        val accent: String,
        val gender: String,
        val label: String? = null,
        val audioBase64: String? = null,
    )

    sealed interface Outcome {
        data class Registered(val voice: RegisteredVoice, val catalogued: Boolean) : Outcome
        data class Rejected(val status: Int, val reason: String, val message: String) : Outcome
    }

    suspend fun register(request: Request): Outcome {
        val voiceId = request.voiceId.trim().lowercase()
        if (!UUID.matches(voiceId)) return rejected(400, "invalid_voice_id", "voiceId must be a UUID")
        val accent = AccentCatalog.accentIds.firstOrNull { it.equals(request.accent.trim(), ignoreCase = true) }
            ?: return rejected(
                400,
                "unknown_accent",
                "accent must be one of ${AccentCatalog.accentIds}; a new accent needs its phonology first",
            )
        val gender = VoiceGender.entries.firstOrNull { it.name.equals(request.gender.trim(), ignoreCase = true) }
            ?: return rejected(400, "invalid_gender", "gender must be female or male")

        val catalogued = AccentCatalog.voice(voiceId)
        if (catalogued != null && (catalogued.accent != accent || catalogued.gender != gender)) {
            return rejected(
                409,
                "catalogue_mismatch",
                "$voiceId is catalogued as ${catalogued.accent}/${catalogued.gender.name.lowercase()}",
            )
        }

        val (bytes, source) = if (!request.audioBase64.isNullOrBlank()) {
            if (request.audioBase64.length > MAX_AUDIO_BASE64) {
                return rejected(413, "audio_too_large", "audio must be at most $MAX_AUDIO_BYTES bytes")
            }
            val decoded = runCatching { Base64.getDecoder().decode(request.audioBase64.trim()) }.getOrNull()
                ?: return rejected(400, "invalid_base64", "audioBase64 is not valid base64")
            decoded to "inline"
        } else {
            val fetched = try {
                fetcher.fetch(voiceId)
            } catch (e: VoiceFetchException) {
                return rejected(if (e.reason == "fetcher_not_configured") 503 else 502, e.reason, e.message ?: e.reason)
            }
            fetched.bytes to fetcher.name
        }

        if (bytes.size < MIN_AUDIO_BYTES) return rejected(422, "audio_too_small", "audio is too small to be speech")
        if (bytes.size > MAX_AUDIO_BYTES) return rejected(413, "audio_too_large", "audio must be at most $MAX_AUDIO_BYTES bytes")
        val container = AudioContainer.sniff(bytes)
            ?: return rejected(422, "unsupported_audio", "audio must be WAV, OGG, MP3 or FLAC")

        val voice = RegisteredVoice(
            voiceId = voiceId,
            accent = accent,
            gender = gender,
            label = request.label?.trim()?.take(MAX_LABEL)?.takeIf { it.isNotEmpty() } ?: catalogued?.label,
            audioFile = "$voiceId.${container.extension}",
            sha256 = sha256(bytes),
            sizeBytes = bytes.size.toLong(),
            source = source,
            registeredAt = now(),
        )
        return try {
            Outcome.Registered(registry.put(voice, bytes), catalogued != null)
        } catch (e: VoiceStorageException) {
            rejected(500, e.reason, e.message ?: e.reason)
        }
    }

    private fun rejected(status: Int, reason: String, message: String) = Outcome.Rejected(status, reason, message)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        const val MIN_AUDIO_BYTES = 1024
        const val MAX_AUDIO_BYTES = 5 * 1024 * 1024
        private const val MAX_AUDIO_BASE64 = (MAX_AUDIO_BYTES / 3 + 1) * 4
        private const val MAX_LABEL = 80
    }
}
