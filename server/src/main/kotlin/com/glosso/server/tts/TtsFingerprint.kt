package com.glosso.server.tts

import java.security.MessageDigest
import java.util.Locale

/**
 * Deterministic cache key. It includes every parameter that changes the audio:
 * text, language, provider, model, voice, speed, format and the provider-specific
 * options (emotion, stability, ...). Two variants of the same text (`normal` vs
 * `slow`) therefore never collide, and switching provider, voice or a provider
 * option does not reuse the wrong clip.
 *
 * Provider options are serialized sorted by key, so the hash does not depend on
 * map iteration order.
 *
 * The pipeline in `tools/content/pipeline.py` keeps its own on-disk cache; this
 * fingerprint is the runtime one the brief specifies (SHA-256, extended fields).
 */
object TtsFingerprint {

    /** Bump when the payload layout changes, to invalidate old keys. */
    const val VERSION = "v2"

    private const val SEPARATOR = "\u0000"

    fun key(
        text: String,
        language: String,
        provider: String,
        model: String,
        voice: String,
        speedFactor: Double,
        format: AudioFormat,
        providerOptions: Map<String, String> = emptyMap(),
    ): String {
        val payload = buildString {
            append(VERSION).append(SEPARATOR)
            append(text).append(SEPARATOR)
            append(language).append(SEPARATOR)
            append(provider).append(SEPARATOR)
            append(model).append(SEPARATOR)
            append(voice).append(SEPARATOR)
            append(speedToken(speedFactor)).append(SEPARATOR)
            append(format.token()).append(SEPARATOR)
            appendOptions(providerOptions)
        }
        return sha256Hex(payload.toByteArray(Charsets.UTF_8))
    }

    fun key(candidate: TtsCandidate): String = key(
        text = candidate.request.text,
        language = candidate.request.language,
        provider = candidate.provider.id,
        model = candidate.request.model,
        voice = candidate.request.voice,
        speedFactor = candidate.speedFactor,
        format = candidate.request.format,
        providerOptions = candidate.request.providerOptions,
    )

    /** Stable, order-independent serialization of the provider escape hatch. */
    private fun StringBuilder.appendOptions(options: Map<String, String>) {
        append("options=").append(options.size)
        for ((name, value) in options.entries.sortedBy { it.key }) {
            append(SEPARATOR).append(name).append(SEPARATOR).append(value)
        }
    }

    fun objectKey(prefix: String, cacheKey: String, format: AudioFormat): String =
        "$prefix/$cacheKey.${format.extension}"

    /** Locale-independent so the hash is the same on every machine. */
    fun speedToken(speedFactor: Double): String =
        String.format(Locale.ROOT, "speed=%.3f", speedFactor)

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
