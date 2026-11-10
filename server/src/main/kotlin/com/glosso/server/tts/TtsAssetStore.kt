package com.glosso.server.tts

import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Metadata persistence for TTS assets and costs. PostgreSQL only; the audio
 * bytes live in object storage. The interface keeps the service testable
 * without a database.
 */
interface TtsAssetStore : AutoCloseable {

    fun findByCacheKey(cacheKey: String): TtsAssetRecord?

    /** Inserts unless the cache key already exists. Returns true when inserted. */
    fun insertIfAbsent(record: TtsAssetRecord): Boolean

    fun recordCost(record: TtsCostRecord)

    /** Sum of estimated costs over all recorded generations. */
    fun totalEstimatedCostUsd(): BigDecimal

    /** Metadata rows, useful for admin and tests. */
    fun count(): Int

    override fun close() {}
}

/** In-memory store: tests and ephemeral runs. */
class InMemoryTtsAssetStore : TtsAssetStore {

    private val assets = ConcurrentHashMap<String, TtsAssetRecord>()
    private val costs = ArrayList<TtsCostRecord>()

    override fun findByCacheKey(cacheKey: String): TtsAssetRecord? = assets[cacheKey]

    override fun insertIfAbsent(record: TtsAssetRecord): Boolean =
        assets.putIfAbsent(record.cacheKey, record) == null

    @Synchronized
    override fun recordCost(record: TtsCostRecord) {
        costs.add(record)
    }

    @Synchronized
    override fun totalEstimatedCostUsd(): BigDecimal =
        costs.fold(BigDecimal.ZERO) { acc, record -> acc.add(record.estimatedCostUsd) }

    override fun count(): Int = assets.size

    @Synchronized
    fun costs(): List<TtsCostRecord> = costs.toList()
}
