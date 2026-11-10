package com.glosso.server.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Content Gap -> Generation Queue step, without a database or Redis: the
 * in-memory stores keep the same idempotency contract as the JDBC ones.
 */
class GenerationServiceTest {

    private val control = InMemoryControlPlaneStore()
    private val store = InMemoryGenerationStore()
    private val queue = InMemoryGenerationQueue()
    private val pool = PoolConfig.of(2, 3, 5)
    private val config = GenerationConfig(provider = "fake", model = "fake-model")

    private val key = NeedClassKey(
        language = "en",
        cefr = "A1",
        skill = SKILL_PRONUNCIATION,
        phoneme = "θ",
        errorPattern = "en.th.stopping.t",
        replacementPhoneme = "t",
        position = "initial",
    )

    private fun service(source: CandidateSource = TemplateCandidateSource()): GenerationService =
        GenerationService(control, store, queue, source, pool, config)

    private fun seedNeedClass(
        effective: Int = 0,
        gapState: GapState = GapState.BELOW_MIN,
    ) {
        control.upsertNeedClasses(
            listOf(
                NeedClass(
                    key = key,
                    learnerCount = 10,
                    availableExisting = effective,
                    availableEffective = effective,
                    gap = (pool.target - effective).coerceAtLeast(0),
                    gapState = gapState,
                    priority = 1.0,
                ),
            ),
        )
    }

    @Test
    fun `a below minimum gap is materialized and enqueued up to target`() {
        seedNeedClass()

        val run = service().enqueueGaps()

        assertEquals(3, run.jobsCreated)
        assertEquals(3, run.messagesEnqueued)
        assertEquals(3, store.contents.size)
        assertEquals(3, store.variants.size)
        assertEquals(3, queue.depth())
        assertTrue(store.contents.values.all { it.status == ContentStatus.APPROVED })
        assertTrue(store.jobs().all { it.status == JobStatus.QUEUED })
        assertTrue(store.contents.values.all { it.needClassId != null })
    }

    @Test
    fun `a covered class is never enqueued`() {
        seedNeedClass(effective = 3, gapState = GapState.BELOW_TARGET)

        val run = service().enqueueGaps()

        assertEquals(0, run.jobsCreated)
        assertEquals(0, run.messagesEnqueued)
        assertEquals(0, queue.depth())
    }

    @Test
    fun `a class at the maximum is never enqueued`() {
        seedNeedClass(effective = 5, gapState = GapState.BELOW_MIN)

        val run = service().enqueueGaps()

        assertEquals(0, run.jobsCreated)
        assertTrue(queue.depth() == 0L)
    }

    @Test
    fun `running the cycle twice creates no duplicate content, job or message`() {
        seedNeedClass()
        val first = service().enqueueGaps()
        val second = service().enqueueGaps()

        assertEquals(3, first.jobsCreated)
        assertEquals(0, second.jobsCreated)
        assertEquals(0, second.messagesEnqueued)
        assertEquals(3, store.contents.size)
        assertEquals(3, store.variants.size)
        assertEquals(3, store.jobs().size)
        assertEquals(3, queue.depth())
    }

    @Test
    fun `a class the candidate source cannot serve is skipped`() {
        seedNeedClass()
        val emptySource = TemplateCandidateSource(phrasesByPhoneme = emptyMap(), fallbackPhrases = emptyList())

        val run = service(emptySource).enqueueGaps()

        assertEquals(0, run.jobsCreated)
        assertEquals(1, run.classesWithoutCandidates)
        assertEquals(0, queue.depth())
    }

    @Test
    fun `candidates are deterministic so the pool is stable`() {
        seedNeedClass()
        val source = TemplateCandidateSource()
        val first = source.candidates(
            NeedClass(key = key, learnerCount = 1),
            3,
        )
        val second = source.candidates(
            NeedClass(key = key, learnerCount = 1),
            3,
        )
        assertEquals(first.map { it.text }, second.map { it.text })
        assertEquals(3, first.map { it.text }.distinct().size)
    }
}
