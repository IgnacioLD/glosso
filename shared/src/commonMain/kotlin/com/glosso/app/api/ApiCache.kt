package com.glosso.app.api

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.appFiles
import com.glosso.app.platform.keyValueStore
import kotlin.concurrent.Volatile

/**
 * Process-wide HTTP cache: a size-bounded, LRU disk store under the cache dir and
 * the persisted content version. Initialised from the application context.
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

    override fun load(): String? = prefs?.getString(KEY)?.takeIf { it.isNotBlank() }

    override fun save(version: String) {
        prefs?.edit { putString(KEY, version) }
    }
}
