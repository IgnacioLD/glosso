package com.glosso.app

import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import coil3.compose.setSingletonImageLoaderFactory
import com.glosso.app.api.ApiCache
import com.glosso.app.api.AppContentScope
import com.glosso.app.api.GlossoApi
import com.glosso.app.designsystem.GlossoTheme
import com.glosso.app.download.NoDownloader
import com.glosso.app.download.WifiOnlyPreference
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.platformConnectivity
import com.glosso.app.product.ProductConfigRepository
import com.glosso.app.resources.Res
import com.glosso.app.resources.noto_sans_mono_ipa
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont
import org.w3c.dom.events.Event

/**
 * The web entry point (docs/WEB.md): the whole shared UI in the page, wired like
 * `MainViewController` wires it on iOS. Every take is scored in the cloud
 * ([NoOnDeviceScoring]), nothing is downloaded for offline use, there is no
 * sign-in, and analytics are not initialised, so nothing is recorded or sent.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalResourceApi::class)
fun main() {
    val services = wire()
    ComposeViewport(document.body!!) {
        // A browser has no system fonts for Skia to fall back on, so the IPA
        // symbols JetBrains Mono lacks come from this subset; preloaded, it is
        // the fallback for every family. The UI waits for it (about 100 KB) so
        // no transcription is laid out with missing glyphs.
        val ipaFont by preloadFont(Res.font.noto_sans_mono_ipa)
        val fontResolver = LocalFontFamilyResolver.current
        var fontsReady by remember { mutableStateOf(false) }
        LaunchedEffect(ipaFont) {
            ipaFont?.let {
                fontResolver.preload(FontFamily(it))
                fontsReady = true
            }
        }
        if (!fontsReady) return@ComposeViewport
        // index.html shows a splash while the code and the font load.
        LaunchedEffect(Unit) { document.getElementById("splash")?.remove() }
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

private fun wire(): AppServices {
    val context = PlatformContext
    val newApi = {
        GlossoApi(
            serverBase = { ServerPreferences.base.value },
            engine = null,
            contentCache = ApiCache.storageOrNull(),
            versionStore = ApiCache,
            apiKey = WebBuild.API_KEY,
            deviceIdProvider = { DeviceId.valueOrNull() },
            analyticsEnabled = { false },
            scope = AppContentScope,
        )
    }
    val connectivity = platformConnectivity(context)

    // The same order as GlossoApplication: the API's dependencies first.
    AppApi.factory = newApi
    ServerPreferences.init(context, WebBuild.API_BASE)
    ProductConfigRepository.api = { appApi }
    OfflineStore.downloader = NoDownloader
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

    // A hidden tab is the app in the background, as onStop is on Android.
    document.addEventListener("visibilitychange", { _: Event ->
        if (visibilityState() == "hidden") Analytics.onBackground() else Analytics.onForeground(context)
    })
    Analytics.onForeground(context)

    return AppServices(
        context = context,
        config = AppConfig(
            debug = WebBuild.DEBUG,
            versionName = WebBuild.VERSION_NAME,
            versionCode = WebBuild.VERSION_CODE,
            usageStatistics = false,
            offlineDownloads = false,
        ),
        connectivity = connectivity,
        onDevice = NoOnDeviceScoring,
        downloader = NoDownloader,
    )
}

private fun visibilityState(): String = js("document.visibilityState")
