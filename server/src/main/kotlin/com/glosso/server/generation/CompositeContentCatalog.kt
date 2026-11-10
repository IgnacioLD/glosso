package com.glosso.server.generation

/**
 * Counts a Need Class against every content pool that can serve it: the
 * published `content.db` (what the app already has) and the generated PostgreSQL
 * pool (what phase 2 adds). The Demand Aggregator sees the sum, so once a piece
 * becomes ACTIVE the measured gap shrinks and the scheduler stops refilling it.
 */
class CompositeContentCatalog(private val catalogs: List<ContentCatalog>) : ContentCatalog {

    /** Postgres-backed pool is the phase-2 addition; it needs the store. */
    class PoolCatalog(private val store: GenerationStore) : ContentCatalog {
        override fun counts(key: NeedClassKey): ContentCounts = store.poolCounts(key)
    }

    override fun counts(key: NeedClassKey): ContentCounts =
        catalogs.fold(ContentCounts(0, 0)) { acc, catalog ->
            val counts = catalog.counts(key)
            ContentCounts(existing = acc.existing + counts.existing, effective = acc.effective + counts.effective)
        }

    companion object {
        fun of(published: ContentCatalog, pool: ContentCatalog): ContentCatalog =
            if (published === EmptyContentCatalog) pool else CompositeContentCatalog(listOf(published, pool))
    }
}
