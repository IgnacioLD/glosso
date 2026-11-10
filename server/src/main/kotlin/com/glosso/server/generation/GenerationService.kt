package com.glosso.server.generation

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What one enqueue cycle did. Counts only; no learner data. */
data class GenerationRun(
    val classesConsidered: Int,
    val jobsCreated: Int,
    val messagesEnqueued: Int,
    val classesWithoutCandidates: Int,
    val alreadyCovered: Int,
)

/** The job spec stored in `generation_jobs.spec`: the Need Class and the text. */
@Serializable
data class JobSpec(
    val needClass: String,
    val language: String,
    val cefr: String,
    val skill: String,
    val phoneme: String? = null,
    val errorPattern: String? = null,
    val replacementPhoneme: String? = null,
    val position: String? = null,
    val kind: String,
    val text: String,
    val candidateSource: String,
)

/**
 * Turns a measured demand gap into generation work: it materializes candidate
 * content and enqueues one TTS job per candidate variant.
 *
 * It never runs inside a user request. The scheduler calls it on its own scope.
 * Every write is idempotent, so running it twice creates no duplicate content,
 * no duplicate job and (with the Redis lock) no duplicate message.
 */
class GenerationService(
    private val controlStore: ControlPlaneStore,
    private val generationStore: GenerationStore,
    private val queue: GenerationQueue,
    private val candidateSource: CandidateSource,
    private val pool: PoolConfig,
    private val config: GenerationConfig,
    private val log: (String) -> Unit = {},
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Enqueues work for every Need Class below `minimum`, enough to reach
     * `target`, never crossing `maximum`. Classes that are already covered, or
     * for which the candidate source has nothing, are left untouched.
     */
    fun enqueueGaps(language: String? = null): GenerationRun {
        val classes = controlStore.needClasses(language, config.maxClassesPerCycle)
        var jobs = 0
        var messages = 0
        var withoutCandidates = 0
        var covered = 0

        for (needClass in classes) {
            if (needClass.gapState != GapState.BELOW_MIN) {
                covered++
                continue
            }
            val want = (pool.target - needClass.availableEffective)
                .coerceAtMost(pool.maximum - needClass.availableEffective)
            if (want <= 0) {
                covered++
                continue
            }
            val candidates = candidateSource.candidates(needClass, want)
            if (candidates.isEmpty()) {
                withoutCandidates++
                continue
            }
            val needClassId = controlStore.needClassId(needClass.key)
            for (candidate in candidates) {
                val outcome = enqueueCandidate(needClass, needClassId, candidate)
                if (outcome.createdJob) jobs++
                if (outcome.enqueued) messages++
            }
        }
        if (jobs > 0 || messages > 0) {
            log("Generation enqueued: jobs=$jobs messages=$messages (classes=${classes.size})")
        }
        return GenerationRun(
            classesConsidered = classes.size,
            jobsCreated = jobs,
            messagesEnqueued = messages,
            classesWithoutCandidates = withoutCandidates,
            alreadyCovered = covered,
        )
    }

    private data class EnqueueOutcome(val createdJob: Boolean, val enqueued: Boolean)

    private fun enqueueCandidate(
        needClass: NeedClass,
        needClassId: Long?,
        candidate: ContentCandidate,
    ): EnqueueOutcome {
        val key = needClass.key
        val variantHash = ContentHashes.variantHash(
            text = candidate.text,
            language = key.language,
            kind = candidate.kind,
            targetPhoneme = key.phoneme,
            errorPattern = key.errorPattern,
            position = key.position,
            voice = candidate.voice,
            sourceVersion = candidateSource.id,
        )
        val jobHash = ContentHashes.jobHash(
            task = "tts",
            key = key,
            variantHash = variantHash,
            provider = config.provider,
            model = config.model,
        )

        // 1. Materialize the content piece and variant if this is new. The
        //    unique `variant_hash` is the guard.
        var variant = generationStore.variantByHash(variantHash)
        if (variant == null) {
            val contentId = UUID.randomUUID().toString()
            val spec = JobSpec(
                needClass = key.label(),
                language = key.language,
                cefr = key.cefr,
                skill = key.skill,
                phoneme = key.phoneme,
                errorPattern = key.errorPattern,
                replacementPhoneme = key.replacementPhoneme,
                position = key.position,
                kind = candidate.kind,
                text = candidate.text,
                candidateSource = candidateSource.id,
            )
            generationStore.insertContent(
                ContentPiece(
                    id = contentId,
                    kind = candidate.kind,
                    language = key.language,
                    cefr = key.cefr,
                    skill = key.skill,
                    objective = candidate.objective,
                    exerciseType = candidate.exerciseType,
                    targetPhoneme = key.phoneme,
                    errorPattern = key.errorPattern,
                    replacementPhoneme = key.replacementPhoneme,
                    position = key.position,
                    status = ContentStatus.APPROVED,
                    createdBy = "generation",
                    provider = candidateSource.id,
                    needClassId = needClassId,
                ),
            )
            generationStore.insertVariant(
                ContentVariant(
                    id = UUID.randomUUID().toString(),
                    contentId = contentId,
                    variantHash = variantHash,
                    specJson = json.encodeToString(JobSpec.serializer(), spec),
                    text = candidate.text,
                    ipa = candidate.ipa,
                    answerJson = candidate.answerJson,
                    voice = candidate.voice,
                    status = ContentStatus.APPROVED,
                ),
            )
            variant = generationStore.variantByHash(variantHash)
        }
        if (variant == null) return EnqueueOutcome(createdJob = false, enqueued = false)

        // 2. Create the job. If it already exists, inspect its state.
        val existingJob = generationStore.jobByHash(jobHash)
        val createdJob: Boolean
        val job: GenerationJob
        if (existingJob == null) {
            val spec = JobSpec(
                needClass = key.label(),
                language = key.language,
                cefr = key.cefr,
                skill = key.skill,
                phoneme = key.phoneme,
                errorPattern = key.errorPattern,
                replacementPhoneme = key.replacementPhoneme,
                position = key.position,
                kind = candidate.kind,
                text = candidate.text,
                candidateSource = candidateSource.id,
            )
            job = GenerationJob(
                id = UUID.randomUUID().toString(),
                jobHash = jobHash,
                needClassId = needClassId,
                contentId = variant.contentId,
                variantId = variant.id,
                task = "tts",
                specJson = json.encodeToString(JobSpec.serializer(), spec),
                targetCount = 1,
                status = JobStatus.QUEUED,
                maxAttempts = config.maxAttempts,
            )
            createdJob = generationStore.insertJobIfAbsent(job)
        } else {
            createdJob = false
            job = existingJob
        }

        // 3. Nothing to do if the asset already exists, or if the job is dead
        //    (an operator decides what to do with a dead job).
        if (variant.hasAudio || job.status == JobStatus.DEAD) {
            return EnqueueOutcome(createdJob, enqueued = false)
        }
        if (job.status == JobStatus.DONE) {
            return EnqueueOutcome(createdJob, enqueued = false)
        }

        // 4. Enqueue under an idempotency lock, so two scheduler cycles do not
        //    push the same work twice. The lock is best-effort; PostgreSQL
        //    remains the source of truth.
        val lockKey = "glosso:job:$jobHash"
        if (!queue.tryLock(lockKey, LOCK_TTL_SECONDS)) {
            return EnqueueOutcome(createdJob, enqueued = false)
        }
        queue.enqueue(GenerationMessage(jobId = job.id, variantId = variant.id, contentId = variant.contentId))
        return EnqueueOutcome(createdJob, enqueued = true)
    }

    /** Job counts per status, for the admin route. */
    fun jobCounts(): Map<JobStatus, Int> = generationStore.jobCounts()

    companion object {
        const val LOCK_TTL_SECONDS = 86_400L
    }
}
