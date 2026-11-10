package com.glosso.server.tts

import java.math.BigDecimal
import java.time.Instant

/**
 * Orchestrates one TTS job end to end. This is the seam the worker calls:
 *
 *     val result = ttsService.synthesize(TtsJob(jobId, request, contentId))
 *
 * Flow: fingerprint -> cache lookup (metadata + object) -> generate via the
 * router (retries/fallback) -> validate the audio -> upload to object storage ->
 * persist metadata and cost. It never publishes an invalid asset, and re-running
 * a job never duplicates one.
 *
 * No queue, worker or scheduler lives here. Phase 2/3 defines those; the worker
 * just invokes this method per job.
 */
class TtsService(
    private val router: TtsProviderRouter,
    private val objectStore: ObjectStore,
    private val assetStore: TtsAssetStore,
    private val validator: AudioValidator,
    private val pricing: TtsPricing,
    private val config: TtsConfig,
    private val observability: TtsObservability = TtsObservability.NOOP,
    private val clock: () -> Instant = Instant::now,
) {

    /** Synthesizes one request. Never throws for provider/validation failures. */
    fun synthesize(job: TtsJob): TtsJobResult {
        val startedAt = System.nanoTime()
        val request = job.request
        val speedFactor = config.speedFactor(request.speed)
        observability.record(
            TtsEvent(
                phase = "job_start",
                jobId = job.jobId,
                contentId = job.contentId,
                characters = request.text.length,
            ),
        )

        try {
            val candidates = router.candidates(request, speedFactor)

            for (candidate in candidates) {
                val cacheKey = TtsFingerprint.key(candidate)
                val existing = assetStore.findByCacheKey(cacheKey)
                if (existing != null && objectStore.exists(existing.objectKey)) {
                    return reuse(job, existing, startedAt)
                }

                // Generated but not marked (worker died before persisting): the
                // object is there, so recover it instead of paying twice.
                val objectKey = TtsFingerprint.objectKey(config.objectKeyPrefix, cacheKey, candidate.request.format)
                if (objectStore.exists(objectKey)) {
                    val bytes = objectStore.get(objectKey)
                    if (bytes != null) {
                        val validation = validator.validate(bytes, candidate.request.format, null)
                        if (validation.valid) {
                            val recovered = assetFrom(
                                job = job,
                                candidate = candidate,
                                cacheKey = cacheKey,
                                objectKey = objectKey,
                                validation = validation,
                                durationMs = null,
                                cost = BigDecimal.ZERO,
                                characters = 0,
                                credits = null,
                                model = candidate.request.model,
                                voice = candidate.request.voice,
                            )
                            assetStore.insertIfAbsent(recovered)
                            observability.record(
                                TtsEvent(
                                    phase = "cache_recovered",
                                    jobId = job.jobId,
                                    provider = candidate.provider.id,
                                    cacheHit = true,
                                ),
                            )
                            return reuse(job, recovered, startedAt)
                        }
                    }
                }
            }

            val result = router.synthesize(request, speedFactor, job.jobId)
            val validation = validator.validate(result.audio, result.format, result.durationMs)
            if (!validation.valid) {
                return fail(
                    job = job,
                    kind = TtsFailureKind.AUDIO_INVALID,
                    message = "invalid audio from ${result.provider}: ${validation.reason}",
                    provider = result.provider,
                    model = result.model,
                    voice = result.voice,
                    startedAt = startedAt,
                    cost = result.estimatedCostUsd,
                )
            }

            val cacheKey = TtsFingerprint.key(
                text = request.text,
                language = request.language,
                provider = result.provider,
                model = result.model,
                voice = result.voice,
                speedFactor = speedFactor,
                format = result.format,
                providerOptions = request.providerOptions,
            )
            val objectKey = TtsFingerprint.objectKey(config.objectKeyPrefix, cacheKey, result.format)
            objectStore.put(objectKey, result.audio, result.format.mimeType)

            val record = assetFrom(
                job = job,
                candidate = null,
                cacheKey = cacheKey,
                objectKey = objectKey,
                validation = validation,
                durationMs = result.durationMs,
                cost = result.estimatedCostUsd,
                characters = result.characters,
                credits = result.credits,
                model = result.model,
                voice = result.voice,
                provider = result.provider,
                speed = result.speed,
                speedFactor = result.speedFactor,
                format = result.format,
            )
            val inserted = assetStore.insertIfAbsent(record)
            assetStore.recordCost(
                TtsCostRecord(
                    jobId = job.jobId,
                    contentId = job.contentId,
                    provider = result.provider,
                    model = result.model,
                    voice = result.voice,
                    characters = result.characters,
                    credits = result.credits,
                    estimatedCostUsd = result.estimatedCostUsd,
                    createdAt = clock(),
                ),
            )
            val attempts = result.metadata["attempts"]?.toIntOrNull() ?: 1
            val fallback = result.metadata["fallback"]?.toBooleanStrictOrNull() ?: false
            observability.record(
                TtsEvent(
                    phase = "published",
                    jobId = job.jobId,
                    contentId = job.contentId,
                    provider = result.provider,
                    model = result.model,
                    voice = result.voice,
                    attempt = attempts,
                    fallback = fallback,
                    characters = result.characters,
                    estimatedCostUsd = result.estimatedCostUsd,
                    message = "object=$objectKey size=${validation.sizeBytes}",
                ),
            )
            return TtsJobResult(
                jobId = job.jobId,
                status = if (inserted) TtsJobStatus.PUBLISHED else TtsJobStatus.ALREADY_PUBLISHED,
                asset = record,
                provider = result.provider,
                model = result.model,
                voice = result.voice,
                cacheHit = false,
                fallbackUsed = fallback,
                attempts = attempts,
                requestDurationMs = elapsedMs(startedAt),
                estimatedCostUsd = result.estimatedCostUsd,
            )
        } catch (e: TtsException) {
            return fail(
                job = job,
                kind = e.kind,
                message = e.message ?: e.kind.name,
                provider = e.provider,
                model = null,
                voice = null,
                startedAt = startedAt,
                cost = BigDecimal.ZERO,
            )
        } catch (e: Exception) {
            return fail(
                job = job,
                kind = TtsFailureKind.UNKNOWN,
                message = e.message ?: e.javaClass.simpleName,
                provider = null,
                model = null,
                voice = null,
                startedAt = startedAt,
                cost = BigDecimal.ZERO,
            )
        }
    }

    /**
     * Generates both variants of a phrase as separate assets. The speed is part
     * of the fingerprint, so `normal` and `slow` never collide.
     */
    fun synthesizePair(base: TtsJob): List<TtsJobResult> = listOf(
        synthesize(base.copy(request = base.request.copy(speed = TtsSpeed.NORMAL))),
        synthesize(base.copy(jobId = "${base.jobId}-slow", request = base.request.copy(speed = TtsSpeed.SLOW))),
    )

    private fun reuse(job: TtsJob, asset: TtsAssetRecord, startedAt: Long): TtsJobResult {
        observability.record(
            TtsEvent(
                phase = "cache_hit",
                jobId = job.jobId,
                contentId = job.contentId,
                provider = asset.provider,
                model = asset.model,
                voice = asset.voice,
                cacheHit = true,
            ),
        )
        return TtsJobResult(
            jobId = job.jobId,
            status = TtsJobStatus.ALREADY_PUBLISHED,
            asset = asset,
            provider = asset.provider,
            model = asset.model,
            voice = asset.voice,
            cacheHit = true,
            attempts = 0,
            requestDurationMs = elapsedMs(startedAt),
            estimatedCostUsd = BigDecimal.ZERO,
        )
    }

    private fun fail(
        job: TtsJob,
        kind: TtsFailureKind,
        message: String,
        provider: String?,
        model: String?,
        voice: String?,
        startedAt: Long,
        cost: BigDecimal,
    ): TtsJobResult {
        // Keep the provider in the recorded reason so a failed job can be
        // diagnosed without guessing which vendor was tried.
        val scrubbed = TtsRedaction.scrub(message)
        val safeMessage = if (provider != null && provider !in scrubbed) "$provider: $scrubbed" else scrubbed
        observability.record(
            TtsEvent(
                phase = "failed",
                jobId = job.jobId,
                contentId = job.contentId,
                provider = provider,
                model = model,
                voice = voice,
                errorKind = kind.name,
                estimatedCostUsd = cost,
                message = safeMessage,
            ),
        )
        return TtsJobResult(
            jobId = job.jobId,
            status = TtsJobStatus.FAILED,
            provider = provider,
            model = model,
            voice = voice,
            requestDurationMs = elapsedMs(startedAt),
            errorKind = kind,
            errorMessage = safeMessage,
            estimatedCostUsd = cost,
        )
    }

    private fun assetFrom(
        job: TtsJob,
        candidate: TtsCandidate?,
        cacheKey: String,
        objectKey: String,
        validation: AudioValidator.Validation,
        durationMs: Long?,
        cost: BigDecimal,
        characters: Int,
        credits: Double?,
        model: String,
        voice: String,
        provider: String = candidate?.provider?.id ?: error("provider required"),
        speed: TtsSpeed = candidate?.request?.speed ?: TtsSpeed.NORMAL,
        speedFactor: Double = candidate?.speedFactor ?: 1.0,
        format: AudioFormat = candidate?.request?.format ?: config.outputFormat,
    ): TtsAssetRecord = TtsAssetRecord(
        cacheKey = cacheKey,
        contentId = job.contentId,
        text = job.request.text,
        language = job.request.language,
        provider = provider,
        model = model,
        voice = voice,
        speed = speed.label,
        speedFactor = speedFactor,
        format = format.token(),
        objectKey = objectKey,
        durationMs = durationMs,
        sizeBytes = validation.sizeBytes,
        checksum = validation.checksum,
        estimatedCostUsd = cost,
        characters = characters,
        credits = credits,
        jobId = job.jobId,
        createdAt = clock(),
    )

    private fun elapsedMs(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000
}
