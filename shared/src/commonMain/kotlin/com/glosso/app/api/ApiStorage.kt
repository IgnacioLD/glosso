package com.glosso.app.api

import io.ktor.client.plugins.cache.storage.CacheStorage

/**
 * Remembers the content version across restarts. The version is part of every
 * content URL (`?v=`), so persisting it is what lets a cold, offline start reach
 * the entries already on disk.
 */
interface AssetVersionStore {
    fun load(): String?
    fun save(version: String)

    /** No persistence; used when a caller injects its own cache. */
    object None : AssetVersionStore {
        override fun load(): String? = null
        override fun save(version: String) = Unit
    }
}

/**
 * The HTTP cache for content. Content is immutable per version, so the client
 * empties it when the served content version changes instead of letting the
 * old catalogue linger.
 */
interface ContentCacheStorage : CacheStorage {
    suspend fun clear()
}
