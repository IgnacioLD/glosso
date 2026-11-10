package com.glosso.server.tts

/**
 * Worker entry point and the explicit connection point for phase 2/3.
 *
 * There is no queue or worker in this branch: the adaptive content engine's
 * phase 2 (Content Pool + Content Gap + Generation Queue) and phase 3 (text
 * generation + validation) are not implemented yet, and this layer does not
 * invent a parallel one.
 *
 * When the phase-2 Redis Streams worker exists, it consumes `glosso:gen:tts`
 * and, per message, does exactly this:
 *
 *     val result = ttsJobProcessor.process(
 *         TtsJob(jobId = message.jobId, request = requestForVariant(...), contentId = message.contentId),
 *     )
 *     // then, in the worker, write back to the content pool:
 *     //   content_variants.audio_key / slow_audio_key = result.asset?.objectKey
 *     //   generation_jobs.status = PUBLISHED / FAILED, error = result.errorMessage
 *
 * Everything the worker needs is behind [TtsService]; the queue is the only
 * missing piece.
 */
class TtsJobProcessor(private val service: TtsService) {

    /** Processes one job. Never throws: failures come back as FAILED results. */
    fun process(job: TtsJob): TtsJobResult = service.synthesize(job)

    /** Processes a phrase into its `normal` and `slow` assets. */
    fun processPair(job: TtsJob): List<TtsJobResult> = service.synthesizePair(job)
}
