package com.glosso.server.tts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Cartesia provider (Sonic family). Primary TTS vendor.
 *
 * Auth is two-step, and that is the whole point of this class:
 *
 *  1. `POST {base}/access-token` with `Authorization: Bearer <API_KEY>`,
 *     `Cartesia-Version`, and a body carrying the grants plus `expires_in`
 *     returns a short-lived JWT. The API key is used **only** here.
 *  2. `POST {base}/tts/bytes` with `Authorization: Bearer <JWT>` returns audio.
 *     `Cartesia-Version` is mandatory on both calls (without it Cartesia 400s).
 *
 * Sending the API key straight to `/tts/bytes` (or as `X-API-Key`) is rejected
 * with 401 even when the key is valid; only `/voices` accepts it. That is why
 * the key never travels to the synthesis endpoint.
 *
 * The JWT is cached until shortly before it expires (see `tokenExpiresIn` and
 * `tokenRefreshSkewSeconds`), so a single token covers many phrases. A 401 from
 * `/tts/bytes` means the token expired mid-flight: the token is re-minted once
 * and the request retried. A second 401 is a real authentication error and is
 * permanent, so the router will not mask it with a fallback provider. A 401 from
 * `/access-token` is always a configuration error and is never retried.
 *
 * The API key and the JWT are read/generated at runtime only. They are never
 * hardcoded, committed, sent to the Android client or written to a log.
 */
class CartesiaTtsProvider(
    private val config: ProviderConfig,
    private val transport: HttpTransport,
    private val pricing: TtsPricing,
    private val clock: () -> Long = System::currentTimeMillis,
) : TtsProvider {

    override val id: String = ID

    override fun defaultModel(): String = config.model

    override fun defaultVoice(): String = config.defaultVoice

    override val supportedLanguages: Set<String> = LANGUAGE_CODES

    private val version: String = config.extra["version"] ?: DEFAULT_VERSION

    /** Version header sent to the token endpoint (may differ from the TTS one). */
    private val tokenVersion: String = config.extra["tokenVersion"] ?: version

    private val tokenExpiresInSeconds: Int =
        config.extra["tokenExpiresIn"]?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_TOKEN_EXPIRES_SECONDS

    private val tokenRefreshSkewSeconds: Int =
        config.extra["tokenRefreshSkewSeconds"]?.toIntOrNull()?.coerceAtLeast(0)
            ?: DEFAULT_TOKEN_REFRESH_SKEW_SECONDS

    /** Exactly the grants we use. Defaults to `tts` and nothing else. */
    private val tokenGrants: List<String> = (config.extra["tokenGrants"] ?: DEFAULT_TOKEN_GRANTS)
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .ifEmpty { listOf(DEFAULT_TOKEN_GRANT_TTS) }

    private val tokenLock = Any()
    private var cachedToken: String? = null
    private var cachedTokenExpiresAtMillis: Long = 0L

    override fun synthesize(request: TtsRequest, speedFactor: Double): TtsResult {
        val apiKey = config.apiKey?.takeIf { it.isNotBlank() }
            ?: throw TtsException(TtsFailureKind.CONFIGURATION, "CARTESIA_API_KEY is not set")
        val text = request.text
        if (text.isBlank()) throw TtsException(TtsFailureKind.INVALID_TEXT, "Cartesia text is blank")

        val model = request.model.ifBlank { defaultModel() }.ifBlank {
            throw TtsException(TtsFailureKind.CONFIGURATION, "Cartesia model is not configured")
        }
        val voice = request.voice.ifBlank { defaultVoice() }.ifBlank {
            throw TtsException(TtsFailureKind.CONFIGURATION, "Cartesia voice is not configured (CARTESIA_DEFAULT_VOICE)")
        }
        val language = request.language.ifBlank { "en" }
        if (supportedLanguages.isNotEmpty() && language.substringBefore('-').lowercase() !in supportedLanguages) {
            throw TtsException(TtsFailureKind.UNSUPPORTED_LANGUAGE, "Cartesia does not support language '$language'")
        }

        val format = request.format
        val body = buildJsonObject {
            put("model_id", model)
            put("transcript", text)
            put("voice", buildJsonObject { put("id", voice) })
            put("language", language)
            put("output_format", outputFormatJson(format))
            put(
                "generation_config",
                buildJsonObject {
                    put("speed", speedFactor)
                    request.providerOptions["emotion"]?.let { put("emotion", it) }
                },
            )
        }

        var response = synthesizeBytes(accessToken(apiKey), body)
        if (response.status == 401) {
            // The cached token expired between mint and use. Re-mint once and
            // retry; a second 401 below is a genuine auth failure, not transient.
            response = synthesizeBytes(accessToken(apiKey, forceRefresh = true), body)
        }
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
            durationMs = resolveDurationMs(response, format),
            characters = characters,
            credits = response.header("x-cartesia-credits")?.toDoubleOrNull(),
            estimatedCostUsd = pricing.estimate(id, model, characters),
            metadata = mapOf("language" to language, "apiVersion" to version),
        )
    }

    private fun synthesizeBytes(token: String, body: JsonObject): HttpTransport.Response =
        transport.send(
            HttpTransport.Request(
                method = "POST",
                url = "${config.baseUrl.trimEnd('/')}/tts/bytes",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Cartesia-Version" to version,
                    "Authorization" to "Bearer $token",
                ),
                body = body.toString().toByteArray(Charsets.UTF_8),
            ),
        )

    /** Returns a live token, minting and caching a new one only when needed. */
    private fun accessToken(apiKey: String, forceRefresh: Boolean = false): String {
        synchronized(tokenLock) {
            val cached = cachedToken
            if (!forceRefresh && cached != null && clock() < cachedTokenExpiresAtMillis) return cached
            val body = buildJsonObject {
                put("grants", buildJsonObject { for (grant in tokenGrants) put(grant, true) })
                put("expires_in", tokenExpiresInSeconds)
            }
            val response = transport.send(
                HttpTransport.Request(
                    method = "POST",
                    url = "${config.baseUrl.trimEnd('/')}/$ACCESS_TOKEN_PATH",
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Cartesia-Version" to tokenVersion,
                        "Authorization" to "Bearer $apiKey",
                    ),
                    body = body.toString().toByteArray(Charsets.UTF_8),
                ),
            )
            if (response.status !in 200..299) throw TtsHttpErrors.toException(id, response.status, response)
            val token = extractToken(response.bodyText())
                ?: throw TtsException(
                    TtsFailureKind.AUTHENTICATION,
                    "Cartesia access-token response did not contain a token",
                )
            val ttlMs = (tokenExpiresInSeconds - tokenRefreshSkewSeconds).coerceAtLeast(0) * 1_000L
            cachedToken = token
            cachedTokenExpiresAtMillis = clock() + ttlMs
            return token
        }
    }

    private fun extractToken(body: String): String? = try {
        Json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /**
     * Prefers a provider-reported duration. For WAV the header is not trusted:
     * Cartesia streams the bytes with a placeholder RIFF/data length, so the
     * duration is computed from the real payload size instead.
     */
    private fun resolveDurationMs(response: HttpTransport.Response, format: AudioFormat): Long? {
        response.header("x-cartesia-duration-ms")?.toLongOrNull()?.let { return it }
        if (format.container != AudioContainer.WAV) return null
        return wavDurationMs(response.body)
    }

    /**
     * Duration of a PCM WAV from its actual byte size: `bytes / (rate * channels
     * * bits / 8)`. The RIFF and `data` chunk sizes are ignored because Cartesia
     * writes them as placeholders (0x7FFFFFFF) while streaming.
     */
    private fun wavDurationMs(bytes: ByteArray): Long? {
        val info = parseWav(bytes) ?: return null
        val bytesPerSecond = info.sampleRate.toLong() * info.channels * (info.bitsPerSample / 8)
        if (bytesPerSecond <= 0) return null
        return info.dataBytes * 1_000L / bytesPerSecond
    }

    private data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataBytes: Long,
    )

    private fun parseWav(bytes: ByteArray): WavInfo? {
        if (bytes.size < 44) return null
        if (!asciiEquals(bytes, 0, "RIFF") || !asciiEquals(bytes, 8, "WAVE")) return null
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var declaredDataSize = -1L
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = readIntLe(bytes, offset + 4).toLong() and 0xFFFFFFFFL
            val payload = offset + 8
            when (id) {
                "fmt " -> {
                    if (payload + 16 > bytes.size) return null
                    channels = readShortLe(bytes, payload + 2)
                    sampleRate = readIntLe(bytes, payload + 4)
                    bitsPerSample = readShortLe(bytes, payload + 14)
                }
                "data" -> {
                    dataOffset = payload
                    declaredDataSize = size
                    break
                }
            }
            if (size <= 0) break
            offset = (payload + size + (size and 1L)).toInt()
        }
        if (dataOffset < 0 || sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null
        val available = (bytes.size - dataOffset).toLong().coerceAtLeast(0)
        val dataBytes = if (declaredDataSize in 1 until available) declaredDataSize else available
        return WavInfo(sampleRate, channels, bitsPerSample, dataBytes)
    }

    private fun asciiEquals(bytes: ByteArray, offset: Int, value: String): Boolean {
        if (offset + value.length > bytes.size) return false
        for (i in value.indices) if (bytes[offset + i] != value[i].code.toByte()) return false
        return true
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun outputFormatJson(format: AudioFormat): JsonObject = when (format.container) {
        AudioContainer.MP3 -> buildJsonObject {
            put("container", "mp3")
            put("bit_rate", format.bitRate)
            put("sample_rate", format.sampleRate)
        }
        AudioContainer.WAV -> buildJsonObject {
            put("container", "wav")
            put("encoding", "pcm_s16le")
            put("sample_rate", format.sampleRate)
        }
        AudioContainer.OGG -> throw TtsException(
            TtsFailureKind.UNSUPPORTED_FORMAT,
            "Cartesia does not output OGG; configure TTS_OUTPUT_FORMAT=mp3 or wav",
        )
    }

    companion object {
        const val ID = "cartesia"
        const val DEFAULT_MODEL = "sonic-3.6"
        const val DEFAULT_VERSION = "2026-08-14"
        const val DEFAULT_BASE_URL = "https://api.cartesia.ai"

        const val ACCESS_TOKEN_PATH = "access-token"
        const val DEFAULT_TOKEN_GRANTS = "tts"
        const val DEFAULT_TOKEN_GRANT_TTS = "tts"
        const val DEFAULT_TOKEN_EXPIRES_SECONDS = 120
        const val DEFAULT_TOKEN_REFRESH_SKEW_SECONDS = 30

        /** Base language codes Cartesia's Sonic models accept. */
        val LANGUAGE_CODES: Set<String> = setOf(
            "en", "fr", "de", "es", "pt", "zh", "ja", "hi", "it", "ko", "nl", "pl",
            "ru", "sv", "tr", "tl", "bg", "ro", "ar", "cs", "el", "fi", "hr", "ms",
            "sk", "da", "ta", "uk", "hu", "no", "vi", "bn", "th", "he", "ka", "id",
            "te", "gu", "kn", "ml", "mr", "pa", "or", "ur",
        )
    }
}
