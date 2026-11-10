package com.glosso.server.generation

/**
 * Collapses per-learner needs into anonymous Need Classes. This is the only step
 * where the user_id is dropped: everything after it works on counts and labels,
 * never on a person.
 *
 * It only measures and prioritizes. Enqueuing generation is phase 2.
 */
class DemandAggregator(
    private val catalog: ContentCatalog,
    private val pool: PoolConfig,
) {

    fun aggregate(needs: List<UserNeed>): List<NeedClass> =
        needs.groupBy { it.needClassKey() }
            .map { (key, group) -> snapshot(key, group) }
            .sortedWith(compareByDescending<NeedClass> { it.priority }.thenBy { it.key.label() })

    private fun snapshot(key: NeedClassKey, group: List<UserNeed>): NeedClass {
        val learners = group.map { it.userId }.distinct().size
        val counts = catalog.counts(key)
        val gap = GapCalculator.compute(counts.effective, pool)
        return NeedClass(
            key = key,
            learnerCount = learners,
            availableExisting = counts.existing,
            availableEffective = counts.effective,
            gap = gap.deficit,
            gapState = gap.state,
            priority = priorityOf(learners, group, gap),
        )
    }

    /**
     * Demand pressure of a class: how many learners are behind it times how
     * uncovered it is, weighted by how urgent those learners' own needs are. A
     * class that is fully covered, or has no learners, gets zero.
     */
    private fun priorityOf(learners: Int, group: List<UserNeed>, gap: Gap): Double {
        if (learners <= 0 || pool.target <= 0) return 0.0
        val uncovered = gap.deficit.toDouble() / pool.target
        val avgUrgency = group.map { it.priority.toDouble() }.average()
        return learners * uncovered * (0.5 + 0.5 * avgUrgency)
    }
}
