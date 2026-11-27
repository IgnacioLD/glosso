package com.glosso.app.api

/**
 * Remembers the content version across restarts. The version is part of every
 * content URL (`?v=`), so persisting it is what lets a cold, offline start reach
 * the entries already on disk. Each course has its own catalogue, so the version
 * is kept per target language.
 */
interface AssetVersionStore {
    fun load(language: String): String?
    fun save(language: String, version: String)

    /** No persistence; used when a caller injects its own cache. */
    object None : AssetVersionStore {
        override fun load(language: String): String? = null
        override fun save(language: String, version: String) = Unit
    }
}
