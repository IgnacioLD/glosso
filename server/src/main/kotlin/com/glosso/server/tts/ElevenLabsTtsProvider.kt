package com.glosso.server.tts

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ElevenLabs provider. Kept ready as a secondary/fallback and alternative but
 * not activated by default: it only runs when `TTS_PROVIDER=elevenlabs` or it is
 * listed in `TTS_FALLBACK_PROVIDERS`.
 *
 * Contract: `POST {base}/v1/text-to-speech/{voice_id}?output_format=...` with
 * header `xi-api-key`, a JSON body `{ text, model_id, voice_settings }` and raw
 * audio bytes back on 200. The key is read from `ELEVENLABS_API_KEY` only.
 */
class ElevenLabsTtsProvider(
    private val config: ProviderConfig,
    private val transport: HttpTransport,
    private val pricing: TtsPricing,
) : TtsProvider {

    override val id: String = ID

    override fun defaultModel(): String = config.model

    override fun defaultVoice(): String = config.defaultVoice

    override val supportedLanguages: Set<String> = LANGUAGE_CODES

    override fun synthesize(request: TtsRequest, speedFactor: Double): TtsResult {
        val apiKey = config.apiKey?.takeIf { it.isNotBlank() }
            ?: throw TtsException(TtsFailureKind.CONFIGURATION, "ELEVENLABS_API_KEY is not set")
        val text = request.text
        if (text.isBlank()) throw TtsException(TtsFailureKind.INVALID_TEXT, "ElevenLabs text is blank")

        val model = request.model.ifBlank { defaultModel() }.ifBlank {
            throw TtsException(TtsFailureKind.CONFIGURATION, "ElevenLabs model is not configured")
        }
        val voice = request.voice.ifBlank { defaultVoice() }.ifBlank {
            throw TtsException(TtsFailureKind.CONFIGURATION, "ElevenLabs voice is not configured (ELEVENLABS_DEFAULT_VOICE)")
        }
        // The voice id is a path segment. Validate it even though today it comes
        // only from configuration: a future route that accepts a voice must not be
        // able to inject `../`, `?` or `#` into the vendor URL (A03/A10).
        if (!VOICE_ID.matches(voice)) {
            throw TtsException(TtsFailureKind.UNSUPPORTED_VOICE, "ElevenLabs voice id is malformed")
        }
        val language = request.language.ifBlank { "en" }
        if (supportedLanguages.isNotEmpty() && language.substringBefore('-').lowercase() !in supportedLanguages) {
            throw TtsException(TtsFailureKind.UNSUPPORTED_LANGUAGE, "ElevenLabs does not support language '$language'")
        }

        val format = request.format
        if (format.container != AudioContainer.MP3) {
            throw TtsException(
                TtsFailureKind.UNSUPPORTED_FORMAT,
                "ElevenLabs provider is wired for MP3; configure TTS_OUTPUT_FORMAT=mp3",
            )
        }
        val outputFormatParam = when (format.sampleRate) {
            8_000 -> "mp3_8000"
            16_000 -> "mp3_16000"
            22_050 -> "mp3_22050"
            24_000 -> "mp3_24000"
            44_100 -> "mp3_44100_${format.bitRate / 1_000}"
            else -> "mp3_44100_128"
        }

        // ElevenLabs accepts speed in [0.7, 1.2]; clamp to stay in range.
        val speed = speedFactor.coerceIn(0.7, 1.2)
        val body = buildJsonObject {
            put("text", text)
            put("model_id", model)
            put(
                "voice_settings",
                buildJsonObject {
                    put("speed", speed)
                    request.providerOptions["stability"]?.toDoubleOrNull()?.let { put("stability", it) }
                    request.providerOptions["similarity_boost"]?.toDoubleOrNull()?.let { put("similarity_boost", it) }
                },
            )
        }

        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "audio/mpeg",
            "xi-api-key" to apiKey,
        )
        val response = transport.send(
            HttpTransport.Request(
                method = "POST",
                url = "${config.baseUrl.trimEnd('/')}/v1/text-to-speech/$voice?output_format=$outputFormatParam",
                headers = headers,
                body = body.toString().toByteArray(Charsets.UTF_8),
            ),
        )
        if (response.status !in 200..299) throw TtsHttpErrors.toException(id, response.status, response)

        val characters = text.length
        return TtsResult(
            audio = response.body,
            format = format,
            provider = id,
            model = model,
            voice = voice,
            speed = request.speed,
            speedFactor = speedFactor,
            durationMs = response.header("x-audio-duration-ms")?.toLongOrNull(),
            characters = characters,
            credits = response.header("x-character-count")?.toDoubleOrNull(),
            estimatedCostUsd = pricing.estimate(id, model, characters),
            metadata = mapOf("language" to language, "outputFormat" to outputFormatParam),
        )
    }

    companion object {
        const val ID = "elevenlabs"
        const val DEFAULT_MODEL = "eleven_multilingual_v2"
        const val DEFAULT_BASE_URL = "https://api.elevenlabs.io"

        /** Only URL-safe voice ids; never a path separator or query delimiter. */
        private val VOICE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        /** Languages `eleven_multilingual_v2` covers. */
        val LANGUAGE_CODES: Set<String> = setOf(
            "en", "ja", "zh", "de", "hi", "fr", "ko", "pt", "it", "es", "id", "nl",
            "tr", "fil", "pl", "sv", "bg", "ro", "ar", "cs", "el", "fi", "hr", "ms",
            "sk", "da", "ta", "uk", "ru", "hu", "no", "vi",
        )
    }
}
