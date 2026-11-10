package com.glosso.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import com.glosso.app.debugtools.AndroidDebugTools
import com.glosso.app.designsystem.GlossoTheme
import com.glosso.app.download.DownloadManager
import com.glosso.app.download.WifiOnlyPreference
import com.glosso.app.platform.platformConnectivity
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private companion object {
        const val EXTRA_SEED_PROFILE_DEMO = "seed_profile_demo"
        const val EXTRA_DOWNLOAD_MODEL = "download_model"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alignDefaultLocales(this)
        LanguagePreferences.init(applicationContext)
        PhoneticsPreferences.init(applicationContext)
        TranslationPreferences.init(applicationContext)
        UiLanguage.init(applicationContext)
        OnboardingStore.init(applicationContext)
        GoogleAccountStore.init(applicationContext)
        VoicePreferences.init(applicationContext)
        AccentScoringFlag.init(applicationContext)
        ProfileStore.get(applicationContext).setLanguage(LanguagePreferences.current.code)
        FeedbackSounds.init(applicationContext)
        ProgressStore.init(applicationContext)
        PhonemeHistory.init(applicationContext)
        AttemptHistory.init(applicationContext)
        platformConnectivity(applicationContext)
        OfflineModelHint.init(applicationContext)
        OnDeviceScorer.init(applicationContext)
        // From the foreground Activity the download manager may raise its dataSync
        // service, which is what keeps a download alive once the app is left.
        DownloadManager.enableForeground()
        WifiOnlyPreference.init(applicationContext)
        // Revalidate the served content version on start, so a rebuilt catalogue
        // invalidates Cloudflare and the offline packs together.
        lifecycleScope.launch { appApi.ensureAssetVersion() }
        // Debug fixture: `adb shell am start -n me.shirobyte42.glosso/com.glosso.app.MainActivity
        // --ez seed_profile_demo true` loads a documented multi-day history so the
        // profile's trend and "today" views can be inspected. Never in release.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_SEED_PROFILE_DEMO, false) == true) {
            ProfileStore.get(applicationContext).seedDemo()
            ProgressStore.seedDemo()
        }
        // Debug fixture: starts the on-device model download from the launch
        // intent so the background-service flow can be exercised on a device:
        // `adb shell am start -n me.shirobyte42.glosso/com.glosso.app.MainActivity
        //  --ez download_model true`. Never in release.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_DOWNLOAD_MODEL, false) == true) {
            lifecycleScope.launch { OnDeviceScorer.download() }
        }
        // Follow the system light/dark setting so the status and navigation bar
        // icons stay legible in both the ink and the paper theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val services = (application as GlossoApplication).services
        setContent {
            ProvideAppServices(services) {
                CompositionLocalProvider(LocalDebugTools provides AndroidDebugTools) {
                    GlossoTheme {
                        Surface {
                            GlossoApp()
                        }
                    }
                }
            }
        }
    }

    // The app is this one Activity, so its start and stop are the app's
    // foreground and background. A rotation is not a session boundary.
    override fun onStart() {
        super.onStart()
        Analytics.onForeground(this)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) Analytics.onBackground()
    }
}
