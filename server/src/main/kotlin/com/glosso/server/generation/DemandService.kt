package com.glosso.server.generation

/** What one aggregation cycle measured. */
data class DemandRun(
    val needClasses: Int,
    val needs: Int,
    val learners: Int,
    val belowMinimum: Int,
)

/**
 * Aggregates open needs into anonymous Need Classes and records the demand
 * snapshot. It measures only: it never creates content, which keeps aggregation
 * off the learner's request path.
 */
class DemandService(
    private val store: ControlPlaneStore,
    private val catalog: ContentCatalog,
    val pool: PoolConfig,
) {

    private val aggregator = DemandAggregator(catalog, pool)

    fun aggregate(language: String? = null): DemandRun {
        val needs = store.openNeeds(language)
        val classes = aggregator.aggregate(needs)
        store.upsertNeedClasses(classes)
        return DemandRun(
            needClasses = classes.size,
            needs = needs.size,
            learners = needs.map { it.userId }.distinct().size,
            belowMinimum = classes.count { it.gapState == GapState.BELOW_MIN },
        )
    }

    fun needClasses(language: String? = null, limit: Int = 100): List<NeedClass> =
        store.needClasses(language, limit)
}
