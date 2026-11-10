package com.glosso.app

import androidx.compose.material3.Surface
import androidx.compose.ui.window.ComposeUIViewController
import coil3.compose.setSingletonImageLoaderFactory
import com.glosso.app.api.ApiCache
import com.glosso.app.api.AppContentScope
import com.glosso.app.api.GlossoApi
import com.glosso.app.designsystem.GlossoTheme
import com.glosso.app.download.ForegroundDownloader
import com.glosso.app.download.WifiOnlyPreference
import com.glosso.app.platform.IosDebugEnv
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.platformConnectivity
import com.glosso.app.product.ProductConfigRepository
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIViewController

/**
 * What Android reads from `BuildConfig` and the iOS host passes from its build
 * settings. The version comes from the bundle (`CFBundleShortVersionString`,
 * `CFBundleVersion`), as `PlatformInfo` already reads it.
 */
data class IosBuild(
    val apiBaseUrl: String = "https://api.glossostudio.com",
    /** The `/v1/score` key; empty sends no header, as on Android. */
    val apiKey: String = "",
    val debug: Boolean = false,
)

/**
 * The iOS entry point: the whole shared UI in one view controller, wired like
 * `GlossoApplication` + `MainActivity` wire it on Android. The Xcode project
 * (`iosApp/`, docs/IOS-DEV.md) shows it edge to edge; Compose pads itself with
 * the safe area and keyboard insets, and the left-edge swipe is a back event.
 *
 * What is deliberately not wired yet, by phase of `docs/IOS-KMP-PLAN.md`:
 * - phase 2: on-device scoring ([NoOnDeviceScoring]: every take is scored in the cloud);
 * - phase 3: background downloads ([ForegroundDownloader] runs while the app is
 *   open), the daily reminder and crash reports ([CrashReporter] not installed);
 * - phase 5: Sign in with Apple (no Google sign-in on iOS);
 * - phase 6: analytics (not initialised, so nothing is recorded or sent until the
 *   catalogue, the policies and the App Store labels cover iOS).
 */
fun MainViewController(build: IosBuild = IosBuild()): UIViewController {
    val services = IosHost.start(build)
    return ComposeUIViewController {
        setSingletonImageLoaderFactory { glossoImageLoader(PlatformContext) }
        ProvideAppServices(services) {
            GlossoTheme {
                Surface {
                    GlossoApp()
                }
            }
        }
    }
}

/** The process-wide wiring, done once however many controllers the host builds. */
private object IosHost {
    private var services: AppServices? = null

    fun start(build: IosBuild): AppServices = services ?: wire(build).also { services = it }

    @OptIn(ExperimentalForeignApi::class)
    private fun wire(build: IosBuild): AppServices {
        IosDebugEnv.load(build.debug)
        val context = PlatformContext
        // Lesson audio plays with the silent switch on, as media does on Android;
        // the recorder switches to play-and-record while it listens.
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        val newApi = {
            GlossoApi(
                baseUrl = build.apiBaseUrl,
                engine = null,
                contentCache = ApiCache.storageOrNull(),
                versionStore = ApiCache,
                apiKey = build.apiKey,
                deviceIdProvider = { DeviceId.valueOrNull() },
                analyticsEnabled = { false },
                scope = AppContentScope,
            )
        }
        val connectivity = platformConnectivity(context)
        val downloader = ForegroundDownloader(connectivity)

        // The same order as GlossoApplication: the API's dependencies first.
        AppApi.factory = newApi
        ProductConfigRepository.api = { appApi }
        OfflineStore.downloader = downloader
        DeviceId.init(context)
        ApiCache.init(context)
        OfflineStore.init(context)
        ProductConfigRepository.init(context)

        // Then what MainActivity initialises before the UI.
        LanguagePreferences.init(context)
        PhoneticsPreferences.init(context)
        TranslationPreferences.init(context)
        UiLanguage.init(context)
        OnboardingStore.init(context)
        GoogleAccountStore.init(context)
        VoicePreferences.init(context)
        AccentScoringFlag.init(context)
        ProfileStore.get(context).setLanguage(LanguagePreferences.current.code)
        FeedbackSounds.init(context)
        ProgressStore.init(context)
        PhonemeHistory.init(context)
        AttemptHistory.init(context)
        OfflineModelHint.init(context)
        WifiOnlyPreference.init(context)
        MainScope().launch { appApi.ensureAssetVersion() }
        observeLifecycle()

        val bundle = NSBundle.mainBundle
        return AppServices(
            context = context,
            config = AppConfig(
                debug = build.debug,
                versionName = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "",
                versionCode = (bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toIntOrNull() ?: 0,
                usageStatistics = false,
            ),
            connectivity = connectivity,
            onDevice = NoOnDeviceScoring,
            downloader = downloader,
            newApi = newApi,
        )
    }

    /**
     * The app's foreground and background, the hooks MainActivity's onStart and
     * onStop call. Analytics is never initialised on iOS (phase 6), so these
     * record and send nothing: with no store, `track` returns at once and a flush
     * finds an empty queue.
     */
    private fun observeLifecycle() {
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, main) { _ ->
            Analytics.onForeground(PlatformContext)
        }
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, main) { _ ->
            Analytics.onBackground()
        }
        // The launch itself is the first foreground, as onStart is on Android.
        Analytics.onForeground(PlatformContext)
    }
}
