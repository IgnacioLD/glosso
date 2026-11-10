package com.glosso.server.tts

import java.util.concurrent.Semaphore

/**
 * Picks the provider, applies retries with exponential backoff, respects the
 * concurrency cap and falls back only on transient failures. It records which
 * provider finally produced the audio.
 *
 * Order: the configured primary first, then each configured fallback. A
 * permanent failure (invalid text, unsupported language, bad auth, bad format)
 * stops immediately and is never masked by a fallback.
 */
class TtsProviderRouter(
    private val providers: List<TtsProvider>,
    private val maxRetries: Int,
    private val maxConcurrency: Int,
    private val observability: TtsObservability = TtsObservability.NOOP,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val baseBackoffMs: Long = 500,
    private val maxBackoffMs: Long = 8_000,
    private val voicePalette: VoicePalette = VoicePalette.EMPTY,
) {
    init {
        require(providers.isNotEmpty()) { "TtsProviderRouter needs at least one provider" }
    }

    private val permits = Semaphore(maxConcurrency.coerceAtLeast(1))

    val providerIds: List<String> get() = providers.map { it.id }

    /** The providers in order, with model/voice resolved per provider. */
    fun candidates(request: TtsRequest, speedFactor: Double): List<TtsCandidate> =
        providers.map { provider -> candidate(provider, request, speedFactor) }

    private fun candidate(provider: TtsProvider, request: TtsRequest, speedFactor: Double) =
        TtsCandidate(
            provider = provider,
            request = request.copy(
                model = request.model.ifBlank { provider.defaultModel() },
                voice = request.voice
                    .ifBlank { voicePalette.resolve(request.speakerIndex) }
                    .ifBlank { provider.defaultVoice() },
            ),
            speedFactor = speedFactor,
        )

    fun synthesize(request: TtsRequest, speedFactor: Double, jobId: String? = null): TtsResult {
        var lastError: TtsException? = null
        providers.forEachIndexed { index, provider ->
            val resolved = candidate(provider, request, speedFactor).request
            var attempt = 0
            while (true) {
                attempt++
                try {
                    val result = withPermit { provider.synthesize(resolved, speedFactor) }
                    val fallback = index > 0
                    return result.copy(
                        metadata = result.metadata + mapOf(
                            "fallback" to fallback.toString(),
                            "attempts" to attempt.toString(),
                        ),
                    )
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw TtsException(
                        TtsFailureKind.CONNECTION,
                        "synthesis interrupted for ${provider.id}",
                        cause = e,
                        provider = provider.id,
                    )
                } catch (e: Exception) {
                    // Anything that is not a TtsException is an unknown provider
                    // failure. An IOException is a connection problem and may be
                    // retried/fall back; any other error is a bug and stops here
                    // so it is never masked by another provider.
                    val error = e.toTtsException(provider.id)
                    if (!error.kind.isTransient) throw error
                    lastError = error
                    observability.record(
                        TtsEvent(
                            phase = "provider_retry",
                            jobId = jobId,
                            provider = provider.id,
                            attempt = attempt,
                            errorKind = error.kind.name,
                            message = error.message,
                        ),
                    )
                    if (attempt > maxRetries) break
                    sleeper(backoffMs(attempt, error.retryAfterSeconds))
                }
            }
            if (index < providers.lastIndex) {
                observability.record(
                    TtsEvent(
                        phase = "provider_fallback",
                        jobId = jobId,
                        provider = provider.id,
                        fallback = true,
                        errorKind = lastError?.kind?.name,
                        message = "switching to ${providers[index + 1].id}",
                    ),
                )
            }
        }
        throw lastError ?: TtsException(TtsFailureKind.UNKNOWN, "no TTS provider produced audio")
    }

    private fun Exception.toTtsException(providerId: String): TtsException = when (this) {
        is TtsException -> withProvider(providerId)
        is java.io.IOException -> TtsException(
            TtsFailureKind.CONNECTION,
            "$providerId connection error: ${message ?: javaClass.simpleName}",
            cause = this,
            provider = providerId,
        )
        else -> TtsException(
            TtsFailureKind.UNKNOWN,
            "$providerId unexpected error: ${message ?: javaClass.simpleName}",
            cause = this,
            provider = providerId,
        )
    }

    private fun <T> withPermit(block: () -> T): T {
        permits.acquire()
        return try {
            block()
        } finally {
            permits.release()
        }
    }

    private fun backoffMs(attempt: Int, retryAfterSeconds: Long?): Long {
        if (retryAfterSeconds != null) {
            return (retryAfterSeconds * 1_000).coerceIn(0, maxBackoffMs)
        }
        val exponent = (attempt - 1).coerceIn(0, 6)
        return (baseBackoffMs shl exponent).coerceAtMost(maxBackoffMs)
    }
}
