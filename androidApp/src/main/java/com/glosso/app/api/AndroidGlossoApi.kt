package com.glosso.app.api

import com.glosso.app.Analytics
import com.glosso.app.BuildConfig
import com.glosso.app.DeviceId
import io.ktor.client.engine.HttpClientEngine

/**
 * The Android [GlossoApi]: the shared client with this app's build values,
 * caches and preferences as defaults, so `GlossoApi()` keeps meaning "the
 * production client" and a test overrides only what it injects.
 */
fun GlossoApi(
    baseUrl: String = BuildConfig.API_BASE_URL,
    engine: HttpClientEngine? = null,
    contentCache: LruCacheStorage? = ApiCache.storageOrNull(),
    versionStore: AssetVersionStore = ApiCache,
    apiKey: String = BuildConfig.API_KEY,
    deviceIdProvider: () -> String? = { DeviceId.valueOrNull() },
    analyticsEnabled: () -> Boolean = { Analytics.enabled.value },
): GlossoApi = GlossoApi(
    baseUrl = baseUrl,
    engine = engine,
    contentCache = contentCache,
    versionStore = versionStore,
    apiKey = apiKey,
    deviceIdProvider = deviceIdProvider,
    analyticsEnabled = analyticsEnabled,
    scope = AppContentScope,
)
