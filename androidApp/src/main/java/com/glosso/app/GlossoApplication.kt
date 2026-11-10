package com.glosso.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.glosso.app.api.ApiCache
import com.glosso.app.api.GlossoApi
import com.glosso.app.billing.BillingProvider
import com.glosso.app.download.DownloadManager
import com.glosso.app.platform.platformConnectivity
import com.glosso.app.migration.StudioImport
import com.glosso.app.product.ProductConfigRepository

/**
 * Runs the one-time import of the published glosso-studio state before any pro
 * store is read. It lives in the [Application] rather than in `MainActivity` so
 * it covers every entry point and cannot be skipped by a store that reads first.
 * See `com.glosso.app.migration.StudioImport` and `docs/DATA-MIGRATION.md`.
 *
 * It also provides the single app-wide [ImageLoader], the only place SVG
 * decoding is wired: Coil resolves it through the singleton and every
 * `AsyncImage`/`SubcomposeAsyncImage` reuses it. See [glossoImageLoader].
 * [services] is what the shared ViewModels and screens are built from.
 */
class GlossoApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // Everything the shared GlossoApi depends on is ready before anything can
        // build it. The order matters: ProductConfigRepository refreshes on a
        // background coroutine, which constructs the process-wide appApi, and
        // that client captures ApiCache in its constructor. Initialising the
        // cache and the offline store first is what keeps the HTTP cache enabled
        // instead of racing the refresh into a permanently disabled one. All of
        // these are fail-open, so none can block the launch.
        wireSharedCode()
        DeviceId.init(this)
        Analytics.init(this)
        CrashReporter.install(this)
        ApiCache.init(this)
        DownloadManager.init(this)
        OfflineStore.init(this)
        ProductConfigRepository.init(this)
        StudioImport.runIfNeeded(this)
    }

    /**
     * Hands the shared (multiplatform) code what only the Android app knows: the
     * build's extra languages and the API client and model state Analytics uses.
     * Runs first, before any store resolves a language or Analytics sends.
     */
    private fun wireSharedCode() {
        AppApi.factory = { GlossoApi() }
        OfferedLanguages.configure(BuildConfig.EXTRA_LANGUAGES)
        Analytics.sender = { appApi.sendEvents(it, Analytics.clock()) }
        Analytics.eraser = { appApi.deleteEvents() }
        Analytics.traceSender = { appApi.sendCrashTrace(it) }
        Analytics.onDeviceModel = { OnDeviceScorer.available() }
        ProductConfigRepository.api = { appApi }
        OfflineStore.downloader = DownloadManager
        GoogleSignIn.serverClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = glossoImageLoader(this)

    /** What the shared UI is built from; the Activity provides it around the UI. */
    val services: AppServices by lazy {
        AppServices(
            context = this,
            config = AppConfig(
                debug = BuildConfig.DEBUG,
                storeScreenshots = BuildConfig.STORE_SCREENSHOTS,
                prosodyBonus = BuildConfig.PROSODY_BONUS,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                subscriptionProductId = BuildConfig.SUBSCRIPTION_PRODUCT_ID,
            ),
            connectivity = platformConnectivity(this),
            onDevice = OnDeviceScorer,
            downloader = DownloadManager,
            newApi = { GlossoApi() },
            subscriptions = { BillingProvider.get(this) },
        )
    }
}
