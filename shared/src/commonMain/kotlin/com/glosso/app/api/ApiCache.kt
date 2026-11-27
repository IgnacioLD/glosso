package com.glosso.app.api

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.appFiles
import com.glosso.app.platform.keyValueStore
import kotlin.concurrent.Volatile

/**
 * Process-wide HTTP cache: a size-bounded, LRU disk store under the cache dir and
 * the persisted content version of each course. Initialised from the application context.
 */
object ApiCache : AssetVersionStore {
    private const val FILE = "glosso_http_cache"
    private const val KEY = "asset_version"
    private const val DIR = "http-cache"

    @Volatile
    private var prefs: KeyValueStore? = null

    @Volatile
    private var storage: LruCacheStorage? = null

    fun init(context: PlatformContext) {
        prefs = keyValueStore(context, FILE)
        val files = appFiles(context)
        storage = LruCacheStorage(files.cacheDir / DIR, files.fileSystem)
    }

    /** The shared disk cache, or null before [init] (tests inject their own). */
    fun storageOrNull(): LruCacheStorage? = storage

    override fun load(language: String): String? {
        val prefs = prefs ?: return null
        // Before versions were per course, one key held the version of the course
        // in use. The first course asked for after the update is that course
        // (the client is built for it), so it inherits the key and an offline
        // start still reaches what is cached.
        prefs.getString(KEY)?.let { legacy ->
            prefs.edit(commit = true) {
                remove(KEY)
                if (!prefs.contains("${KEY}_$language")) putString("${KEY}_$language", legacy)
            }
        }
        return prefs.getString("${KEY}_$language")?.takeIf { it.isNotBlank() }
    }

    override fun save(language: String, version: String) {
        prefs?.edit { putString("${KEY}_$language", version) }
    }

    /** Drops every course's content version and every cached response (a server switch). */
    suspend fun forget() {
        prefs?.edit(commit = true) { clear() }
        storage?.clear()
    }
}
