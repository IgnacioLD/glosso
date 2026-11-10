package com.glosso.server.generation

import java.time.Instant

/**
 * Test double for [GenerationStore]. It mirrors the JDBC semantics that matter
 * for the services: content and variants keyed by their hashes, jobs unique by
 * `job_hash`, and an ACTIVE variant is what makes a piece servable.
 */
class InMemoryGenerationStore : GenerationStore {

    val contents = LinkedHashMap<String, ContentPiece>()
    val variants = LinkedHashMap<String, ContentVariant>()
    private val jobsById = LinkedHashMap<String, GenerationJob>()
    private val jobsByHash = HashMap<String, String>()

    override fun poolCounts(key: NeedClassKey): ContentCounts {
        val matching = contents.values.filter { matches(it, key) }
        return ContentCounts(
            existing = matching.size,
            effective = matching.count { it.status == ContentStatus.ACTIVE },
        )
    }

    private fun matches(piece: ContentPiece, key: NeedClassKey): Boolean =
        piece.language == key.language &&
            piece.cefr.orEmpty() == key.cefr.orEmpty() &&
            piece.skill.orEmpty() == key.skill.orEmpty() &&
            piece.targetPhoneme.orEmpty() == key.phoneme.orEmpty() &&
            piece.errorPattern.orEmpty() == key.errorPattern.orEmpty() &&
            piece.replacementPhoneme.orEmpty() == key.replacementPhoneme.orEmpty() &&
            piece.position.orEmpty() == key.position.orEmpty()

    override fun insertContent(piece: ContentPiece) {
        contents[piece.id] = piece
    }

    override fun content(id: String): ContentPiece? = contents[id]

    override fun variantsForContent(contentId: String): List<ContentVariant> =
        variants.values.filter { it.contentId == contentId }.sortedBy { it.createdAt }

    override fun variant(id: String): ContentVariant? = variants[id]

    override fun variantByHash(variantHash: String): ContentVariant? =
        variants.values.firstOrNull { it.variantHash == variantHash }

    override fun insertVariant(variant: ContentVariant) {
        if (variantByHash(variant.variantHash) != null) return
        variants[variant.id] = variant
    }

    override fun recordVariantAudio(
        variantId: String,
        audioKey: String,
        slowAudioKey: String,
        provider: String,
        model: String,
        voice: String,
    ): Boolean {
        val variant = variants[variantId] ?: return false
        variants[variantId] = variant.copy(
            audioKey = audioKey,
            slowAudioKey = slowAudioKey,
            provider = provider,
            model = model,
            voice = voice,
            status = ContentStatus.ACTIVE,
        )
        return true
    }

    override fun setContentStatus(contentId: String, status: ContentStatus) {
        val piece = contents[contentId] ?: return
        contents[contentId] = piece.copy(status = status)
    }

    override fun contentPool(language: String?, status: ContentStatus?, limit: Int): List<ContentPiece> {
        return contents.values
            .filter { language == null || it.language == language }
            .filter { status == null || it.status == status }
            .sortedByDescending { it.createdAt }
            .take(limit)
    }

    override fun insertJobIfAbsent(job: GenerationJob): Boolean {
        if (jobsByHash.containsKey(job.jobHash)) return false
        jobsById[job.id] = job
        jobsByHash[job.jobHash] = job.id
        return true
    }

    override fun job(id: String): GenerationJob? = jobsById[id]

    override fun jobByHash(jobHash: String): GenerationJob? = jobsByHash[jobHash]?.let { jobsById[it] }

    override fun updateJob(job: GenerationJob) {
        jobsById[job.id] = job
        jobsByHash[job.jobHash] = job.id
    }

    override fun jobs(status: JobStatus?, limit: Int): List<GenerationJob> =
        jobsById.values
            .filter { status == null || it.status == status }
            .sortedByDescending { it.requestedAt }
            .take(limit)

    override fun jobCounts(): Map<JobStatus, Int> =
        JobStatus.entries.associateWith { status -> jobsById.values.count { it.status == status } }
            .filterValues { it > 0 }

    fun updateRequestedAt(job: GenerationJob, at: Instant) {
        jobsById[job.id] = job.copy(requestedAt = at)
    }
}
