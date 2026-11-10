package com.glosso.server.generation

/**
 * Persistence for the phase-2 content pool and the generation job ledger.
 *
 * It is deliberately one small interface, not a service per table: the repo has
 * no ORM and no microservices, so the consumers depend on this and stay testable
 * without a database. Every write that matters is idempotent by a unique key
 * (`content_variants.variant_hash`, `generation_jobs.job_hash`).
 */
interface GenerationStore : AutoCloseable {

    // ---- Content pool ----------------------------------------------------

    /** Number of existing / effective pieces a Need Class can be served with. */
    fun poolCounts(key: NeedClassKey): ContentCounts

    fun insertContent(piece: ContentPiece)

    fun content(id: String): ContentPiece?

    fun variantsForContent(contentId: String): List<ContentVariant>

    fun variant(id: String): ContentVariant?

    fun variantByHash(variantHash: String): ContentVariant?

    /** Inserts unless the `variant_hash` already exists. */
    fun insertVariant(variant: ContentVariant)

    /**
     * Records the audio keys and flips the variant to [ContentStatus.ACTIVE].
     * Idempotent: writing the same keys again is a no-op. Returns true when the
     * row changed.
     */
    fun recordVariantAudio(
        variantId: String,
        audioKey: String,
        slowAudioKey: String,
        provider: String,
        model: String,
        voice: String,
    ): Boolean

    fun setContentStatus(contentId: String, status: ContentStatus)

    /** Admin listing of the pool, newest first. */
    fun contentPool(language: String? = null, status: ContentStatus? = null, limit: Int = 100): List<ContentPiece>

    // ---- Jobs ------------------------------------------------------------

    /** Inserts unless `job_hash` exists. Returns true when a new row was created. */
    fun insertJobIfAbsent(job: GenerationJob): Boolean

    fun job(id: String): GenerationJob?

    fun jobByHash(jobHash: String): GenerationJob?

    fun updateJob(job: GenerationJob)

    /** Jobs, optionally filtered by status, newest first. */
    fun jobs(status: JobStatus? = null, limit: Int = 100): List<GenerationJob>

    fun jobCounts(): Map<JobStatus, Int>

    override fun close() {}
}
