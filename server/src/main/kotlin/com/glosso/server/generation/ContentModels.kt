package com.glosso.server.generation

import com.glosso.server.tts.TtsFingerprint
import java.math.BigDecimal
import java.time.Instant

/**
 * Domain model of the phase-2 content pool and generation queue.
 *
 * The brief's Content entity is split in two, as the architecture document
 * prescribes: [ContentPiece] is the logical piece and [ContentVariant] is a
 * servable instance of it (one voice, one length, one regeneration). The pieces
 * the brief lists (text, ipa, answer, audio, slow audio, image) live on the
 * variant; the piece carries the identity and the Need Class it serves. That
 * split is what makes the pool reusable and the generation idempotent.
 */

/** The lifecycle states of the brief. A piece is servable only when ACTIVE. */
enum class ContentStatus {
    DRAFT,
    GENERATING,
    VALIDATING,
    APPROVED,
    ACTIVE,
    DEPRECATED,
    REJECTED,
}

/** A logical content piece. `id` is a UUID string; specs are stored as JSON. */
data class ContentPiece(
    val id: String,
    val kind: String = "DRILL",
    val language: String = "en",
    val cefr: String? = null,
    val skill: String? = null,
    val objective: String? = null,
    val exerciseType: String? = null,
    val targetPhoneme: String? = null,
    val errorPattern: String? = null,
    val replacementPhoneme: String? = null,
    val position: String? = null,
    val difficulty: Double? = null,
    val status: ContentStatus = ContentStatus.DRAFT,
    val qualityScore: Double? = null,
    val createdBy: String? = null,
    val provider: String? = null,
    /** The Need Class this piece was generated for, when it was generated. */
    val needClassId: Long? = null,
    val createdAt: Instant = Instant.now(),
)

/** A servable instance. `variantHash` is the idempotency key of the pool. */
data class ContentVariant(
    val id: String,
    val contentId: String,
    val variantHash: String,
    val specJson: String = "{}",
    val text: String,
    val ipa: String? = null,
    val answerJson: String? = null,
    val audioKey: String? = null,
    val slowAudioKey: String? = null,
    val imageKey: String? = null,
    val voice: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val status: ContentStatus = ContentStatus.DRAFT,
    val qualityScore: Double? = null,
    val createdAt: Instant = Instant.now(),
) {
    /** A piece is servable once both audio variants have been stored. */
    val hasAudio: Boolean get() = !audioKey.isNullOrBlank() && !slowAudioKey.isNullOrBlank()
}

/**
 * One unit of generation work. `jobHash` is unique, so the same work is never
 * enqueued twice; `provider`/`error` record which vendor failed.
 */
data class GenerationJob(
    val id: String,
    val jobHash: String,
    val needClassId: Long? = null,
    val contentId: String? = null,
    val variantId: String? = null,
    val task: String = "tts",
    val specJson: String = "{}",
    val targetCount: Int = 1,
    val status: JobStatus = JobStatus.QUEUED,
    val attempts: Int = 0,
    val maxAttempts: Int = 3,
    val provider: String? = null,
    val model: String? = null,
    val maxCostPerJob: BigDecimal? = null,
    val requestedAt: Instant = Instant.now(),
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val error: String? = null,
)

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, DEAD }

/** A canonical, deterministic hash of a variant: the pool's idempotency unit. */
object ContentHashes {

    /** Bump when the variant layout changes, so old variants are not reused. */
    const val SCHEMA_VERSION = "v1"

    private const val SEPARATOR = "\u0000"

    fun variantHash(
        text: String,
        language: String,
        kind: String,
        targetPhoneme: String?,
        errorPattern: String?,
        position: String?,
        voice: String,
        sourceVersion: String,
    ): String = sha256(
        listOf(
            SCHEMA_VERSION,
            text.trim(),
            language,
            kind,
            targetPhoneme.orEmpty(),
            errorPattern.orEmpty(),
            position.orEmpty(),
            voice,
            sourceVersion,
        ).joinToString(SEPARATOR),
    )

    /**
     * The job idempotency key: task, the Need Class identity, the variant, the
     * provider chain and the schema version. The same work hashes the same, so
     * a second scheduler run creates no duplicate job.
     */
    fun jobHash(task: String, key: NeedClassKey, variantHash: String, provider: String, model: String): String =
        sha256(
            listOf(
                SCHEMA_VERSION,
                task,
                key.language,
                key.cefr,
                key.skill,
                key.phoneme.orEmpty(),
                key.errorPattern.orEmpty(),
                key.replacementPhoneme.orEmpty(),
                key.position.orEmpty(),
                variantHash,
                provider,
                model,
            ).joinToString(SEPARATOR),
        )

    fun sha256(value: String): String = TtsFingerprint.sha256Hex(value.toByteArray(Charsets.UTF_8))
}
