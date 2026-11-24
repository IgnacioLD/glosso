package com.glosso.app

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import com.glosso.app.platform.currentUiLanguage
import com.glosso.app.resources.noto_sans_arabic
import com.glosso.app.resources.noto_sans_cyrillic
import com.glosso.app.resources.noto_sans_devanagari
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource
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
 * ([NoOnDeviceScoring]), nothing is downloaded for offline use and there is no
 * sign-in. Usage statistics are anonymous and sent only after the learner accepts them.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalResourceApi::class)
fun main() {
    val services = wire()
    // index.html sizes #app to the safe area (notch, home indicator).
    ComposeViewport(document.getElementById("app")!!) {
        // A browser has no system fonts for Skia to fall back on, so the IPA
        // symbols JetBrains Mono lacks come from this subset; preloaded, it is
        // the fallback for every family. The UI waits for it (about 100 KB) so
        // no transcription is laid out with missing glyphs. The bundled faces
        // are Latin, so the script of the interface language is a fallback too,
        // fetched only in that language.
        val uiLanguage = currentUiLanguage()
        val ipaFont by preloadFont(Res.font.noto_sans_mono_ipa)
        val scriptFonts = scriptFallback(uiLanguage)
        val fontResolver = LocalFontFamilyResolver.current
        var fontsReady by remember { mutableStateOf(false) }
        LaunchedEffect(ipaFont, scriptFonts) {
            val ipa = ipaFont ?: return@LaunchedEffect
            if (scriptFonts != null && scriptFonts.isEmpty()) return@LaunchedEffect
            fontResolver.preload(FontFamily(ipa))
            scriptFonts?.let { fontResolver.preload(FontFamily(it)) }
            fontsReady = true
        }
        if (!fontsReady) return@ComposeViewport
        // index.html shows a splash while the code and the font load.
        LaunchedEffect(Unit) { document.getElementById("splash")?.remove() }
        setSingletonImageLoaderFactory { glossoImageLoader(PlatformContext) }
        // Compose takes no direction from the page, so Arabic sets it here.
        val direction = if (uiLanguage == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            ProvideAppServices(services) {
                GlossoTheme {
                    Surface {
                        GlossoApp()
                    }
                }
            }
        }
    }
}

/**
 * The regular and bold instances of the fallback face for the script of
 * [uiLanguage]: null when the bundled faces cover it, empty while it loads.
 */
@OptIn(ExperimentalResourceApi::class, ExperimentalTextApi::class)
@Composable
private fun scriptFallback(uiLanguage: String): List<Font>? {
    val resource = when (uiLanguage) {
        "ru" -> Res.font.noto_sans_cyrillic
        "hi" -> Res.font.noto_sans_devanagari
        "ar" -> Res.font.noto_sans_arabic
        else -> return null
    }
    val loaded by preloadFont(resource)
    if (loaded == null) return emptyList()
    val regular = instance(resource, FontWeight.Normal)
    val bold = instance(resource, FontWeight.Bold)
    return remember(regular, bold) { listOf(regular, bold) }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun instance(resource: FontResource, weight: FontWeight): Font = Font(
    resource,
    weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

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
            analyticsEnabled = { Analytics.enabled.value },
            anonymousAnalytics = true,
            scope = AppContentScope,
        )
    }
    val connectivity = platformConnectivity(context)

    // The same order as GlossoApplication: the API's dependencies first.
    AppApi.factory = newApi
    Analytics.sender = { appApi.sendEvents(it, Analytics.clock()) }
    Analytics.eraser = { appApi.deleteEvents() }
    // The web has no crash reports: wasm gives no stack frames and the server refuses the platform.
    Analytics.traceSender = { 204 }
    ServerPreferences.init(context, WebBuild.API_BASE)
    ProductConfigRepository.api = { appApi }
    OfflineStore.downloader = NoDownloader
    DeviceId.init(context)
    Analytics.defaultEnabled = false
    Analytics.askConsent = true
    Analytics.anonymous = true
    Analytics.init(context)
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
            offlineDownloads = false,
        ),
        connectivity = connectivity,
        onDevice = NoOnDeviceScoring,
        downloader = NoDownloader,
    )
}

private fun visibilityState(): String = js("document.visibilityState")
