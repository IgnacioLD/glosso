package com.glosso.app

import com.glosso.app.api.GlossoApi

/**
 * The process-wide client for one-off fetches (artwork, example words, events)
 * that follow the same host. The host app sets [factory] at start, before
 * anything reads [appApi]: on Android it is the `GlossoApi()` with the build's
 * values (`GlossoApplication`).
 */
object AppApi {
    lateinit var factory: () -> GlossoApi

    internal val client: GlossoApi by lazy {
        factory().also { api ->
            // The content version is learned from the manifest. Propagate it to the
            // offline store so packs from an older catalogue are dropped and fetched
            // again, and seed it with whatever version was persisted from last run.
            api.onContentVersionChanged = { OfflineStore.setContentVersion(it) }
            OfflineStore.setContentVersion(api.contentVersion)
        }
    }
}

/** The process-wide client (see [AppApi]). */
val appApi: GlossoApi get() = AppApi.client
