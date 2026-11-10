package com.glosso.server.generation

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemandAggregatorTest {

    private val pool = PoolConfig.of(20, 50, 100)

    private fun catalogReturning(effective: Int): ContentCatalog =
        object : ContentCatalog {
            override fun counts(key: NeedClassKey) = ContentCounts(existing = effective, effective = effective)
        }

    private fun need(
        userId: UUID,
        phoneme: String = "θ",
        errorPattern: String? = "en.th.stopping.t",
        position: String? = "initial",
        cefr: String? = "A1",
        priority: Float = 0.7f,
    ) = UserNeed(
        userId = userId,
        language = "en",
        cefr = cefr,
        skill = SKILL_PRONUNCIATION,
        phoneme = phoneme,
        errorPattern = errorPattern,
        replacementPhoneme = "t",
        position = position,
        priority = priority,
    )

    @Test
    fun `learners are counted once per class`() {
        val aggregator = DemandAggregator(catalogReturning(0), pool)
        val classes = aggregator.aggregate(
            listOf(need(UUID.randomUUID()), need(UUID.randomUUID()), need(UUID.randomUUID())),
        )
        assertEquals(1, classes.size)
        assertEquals(3, classes.single().learnerCount)
        assertEquals("A1/θ/en.th.stopping.t/initial", classes.single().key.label())
    }

    @Test
    fun `the gap follows the configured pool`() {
        val classes = DemandAggregator(catalogReturning(0), pool).aggregate(listOf(need(UUID.randomUUID())))
        val empty = classes.single()
        assertEquals(GapState.BELOW_MIN, empty.gapState)
        assertEquals(50, empty.gap)

        val partial = DemandAggregator(catalogReturning(40), pool)
            .aggregate(listOf(need(UUID.randomUUID()))).single()
        assertEquals(GapState.BELOW_TARGET, partial.gapState)
        assertEquals(10, partial.gap)

        val covered = DemandAggregator(catalogReturning(50), pool)
            .aggregate(listOf(need(UUID.randomUUID()))).single()
        assertEquals(GapState.OK, covered.gapState)
        assertEquals(0, covered.gap)

        val saturated = DemandAggregator(catalogReturning(100), pool)
            .aggregate(listOf(need(UUID.randomUUID()))).single()
        assertEquals(GapState.AT_MAX, saturated.gapState)
        assertEquals(0, saturated.gap)
    }

    @Test
    fun `priority is demand pressure and vanishes when covered`() {
        val uncovered = DemandAggregator(catalogReturning(0), pool)
            .aggregate(listOf(need(UUID.randomUUID()))).single()
        assertTrue(uncovered.priority > 0.0)

        val covered = DemandAggregator(catalogReturning(50), pool)
            .aggregate(listOf(need(UUID.randomUUID()))).single()
        assertEquals(0.0, covered.priority)
    }

    @Test
    fun `more learners behind a class raise its priority`() {
        val aggregator = DemandAggregator(catalogReturning(0), pool)
        val one = aggregator.aggregate(listOf(need(UUID.randomUUID()))).single().priority
        val many = aggregator
            .aggregate((1..5).map { need(UUID.randomUUID()) }).single().priority
        assertTrue(many > one, "one=$one many=$many")
    }

    @Test
    fun `classes are ordered by priority`() {
        val aggregator = DemandAggregator(catalogReturning(0), pool)
        val classes = aggregator.aggregate(
            listOf(
                need(UUID.randomUUID(), phoneme = "s", errorPattern = null, priority = 0.2f),
                need(UUID.randomUUID(), phoneme = "θ", errorPattern = null, priority = 0.9f),
            ),
        )
        assertEquals("θ", classes.first().key.phoneme)
    }

    @Test
    fun `gap calculator never goes negative and respects the cap`() {
        assertEquals(Gap(0, GapState.OK), GapCalculator.compute(50, pool))
        assertEquals(Gap(0, GapState.AT_MAX), GapCalculator.compute(120, pool))
        assertTrue(GapCalculator.isCandidate(GapCalculator.compute(0, pool)))
        assertTrue(!GapCalculator.isCandidate(GapCalculator.compute(50, pool)))
    }
}
