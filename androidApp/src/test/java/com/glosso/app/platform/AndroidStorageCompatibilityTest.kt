package com.glosso.app.platform

import android.content.Context
import com.glosso.app.AccentScoringFlag
import com.glosso.app.Analytics
import com.glosso.app.AttemptHistory
import com.glosso.app.BuildConfig
import com.glosso.app.DailyReminder
import com.glosso.app.DownloadPermission
import com.glosso.app.GoogleAccountStore
import com.glosso.app.OfflineLayout
import com.glosso.app.OnDeviceScorer
import com.glosso.app.PhonemeHistory
import com.glosso.app.ProfileCodec
import com.glosso.app.ProfileStore
import com.glosso.app.product.ProductConfigRepository
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.engine.model.PhonemeModelConfig
import com.glosso.app.DeviceId
import com.glosso.app.LanguagePreferences
import com.glosso.app.OfflineModelHint
import com.glosso.app.OnboardingStore
import com.glosso.app.PendingCrash
import com.glosso.app.PhoneticsPreferences
import com.glosso.app.ProgressStore
import com.glosso.app.TranslationPreferences
import com.glosso.app.UiLanguage
import com.glosso.app.VoicePreferences
import com.glosso.app.api.ApiCache
import com.glosso.app.api.ClientEvent
import com.glosso.app.download.WifiOnlyPreference
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The stores moved to the shared module must read what earlier versions wrote:
 * the same SharedPreferences file and key names and the same files under the
 * same directories, so an update keeps progress, settings and statistics. Each
 * test writes the data the way the Android-only code did and reads it through
 * the shared store (or the other way round).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidStorageCompatibilityTest {

    private val app: Context get() = RuntimeEnvironment.getApplication()

    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    @After
    fun tearDown() = Analytics.resetForTest()

    /**
     * Makes a store that reads its file once per process ([store]'s `prefs`) read
     * it again, as a new process would: Robolectric keeps singletons across tests
     * but gives each test fresh app data.
     */
    private fun asNewProcess(vararg stores: Any) = stores.forEach { store ->
        store.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(store, null)
    }

    @Test
    fun `a key value store is the SharedPreferences file of the same name`() {
        prefs("compat_probe").edit().clear().putString("s", "old").putBoolean("b", true).commit()
        val store = keyValueStore(app, "compat_probe")

        assertEquals("old", store.getString("s"))
        assertTrue(store.getBoolean("b", false))
        assertFalse(store.getBoolean("missing", false))
        assertNull(store.getString("missing"))
        assertTrue(store.contains("s"))

        store.edit(commit = true) {
            putString("s", "new")
            putBoolean("b", false)
        }
        assertEquals("new", prefs("compat_probe").getString("s", null))
        assertFalse(prefs("compat_probe").getBoolean("b", true))

        store.edit { remove("s") }
        assertFalse(prefs("compat_probe").contains("s"))
    }

    @Test
    fun `a clear runs before the other edits of the block, as in SharedPreferences`() {
        prefs("compat_clear").edit().putString("old", "x").commit()
        keyValueStore(app, "compat_clear").edit(commit = true) {
            putString("kept", "y")
            clear()
        }
        assertFalse(prefs("compat_clear").contains("old"))
        assertEquals("y", prefs("compat_clear").getString("kept", null))
    }

    @Test
    fun `app files are the Android app directories`() {
        val files = appFiles(app)
        assertEquals(app.filesDir.toOkioPath(), files.filesDir)
        assertEquals(app.cacheDir.toOkioPath(), files.cacheDir)
        assertEquals(app.noBackupFilesDir.toOkioPath(), files.noBackupDir)
    }

    @Test
    fun `platform info is what app_open sent before`() {
        val info = platformInfo(app)
        assertEquals(PlatformInfo.ANDROID, info.osName)
        assertEquals(android.os.Build.VERSION.SDK_INT, info.osMajorVersion)
        assertEquals(BuildConfig.VERSION_NAME, info.appVersion)
        assertEquals(app.resources.configuration.smallestScreenWidthDp >= 600, info.isTablet)
        assertEquals("en", info.uiLanguage)
    }

    @Test
    fun `last modified times can be set`() {
        val file = File(app.cacheDir, "touch.probe").apply { writeText("x") }
        assertTrue(setLastModified(file.toOkioPath(), 1_000_000L))
        assertEquals(1_000_000L, file.lastModified())
        assertFalse(setLastModified(File(app.cacheDir, "absent").toOkioPath(), 1L))
    }

    @Test
    fun `preferences written by earlier versions are read back`() {
        prefs("glosso_language").edit().putString("target_language", "ru").commit()
        LanguagePreferences.init(app)
        assertEquals("ru", LanguagePreferences.code.value)
        LanguagePreferences.set("en")
        assertEquals("en", prefs("glosso_language").getString("target_language", null))

        prefs("glosso_voice").edit().putString("accent", "en-US").putString("gender", "f").commit()
        VoicePreferences.init(app)
        assertEquals("en-US", VoicePreferences.accent)
        assertFalse("the dropped gender is still cleaned up", prefs("glosso_voice").contains("gender"))
        VoicePreferences.set("en-GB")
        assertEquals("en-GB", prefs("glosso_voice").getString("accent", null))

        prefs("glosso_onboarding").edit().putBoolean("done", false).commit()
        OnboardingStore.init(app)
        assertFalse(OnboardingStore.done.value)
        OnboardingStore.complete()
        assertTrue(prefs("glosso_onboarding").getBoolean("done", false))

        prefs("glosso_accent_scoring").edit().putBoolean("multi_accent", true).commit()
        AccentScoringFlag.init(app)
        assertTrue(AccentScoringFlag.enabled)

        prefs("glosso_device").edit().putString("glosso_device_id", "11111111-2222-4333-8444-555555555555").commit()
        DeviceId.init(app)
        assertEquals("11111111-2222-4333-8444-555555555555", DeviceId.valueOrNull())

        prefs("glosso_http_cache").edit().putString("asset_version", "v42").commit()
        ApiCache.init(app)
        assertEquals("v42", ApiCache.load())
        ApiCache.save("v43")
        assertEquals("v43", prefs("glosso_http_cache").getString("asset_version", null))

        UiLanguage.init(app)
        assertEquals("en", UiLanguage.current.value)
    }

    @Test
    fun `settings switches write the same files and keys as before`() {
        asNewProcess(PhoneticsPreferences, TranslationPreferences, DailyReminder, WifiOnlyPreference, OfflineModelHint)
        PhoneticsPreferences.setVisible(app, false)
        assertFalse(prefs("glosso_display").getBoolean("show_phonetics", true))
        PhoneticsPreferences.setVisible(app, true)

        TranslationPreferences.setVisible(app, true)
        assertTrue(prefs("glosso_display").getBoolean("show_translations", false))
        TranslationPreferences.setVisible(app, false)

        DailyReminder.setEnabled(app, true)
        assertTrue(prefs("glosso_reminder").getBoolean("enabled", false))
        DailyReminder.setEnabled(app, false)

        WifiOnlyPreference.set(app, false)
        assertFalse(prefs("glosso_downloads").getBoolean("wifi_only", true))
        WifiOnlyPreference.set(app, true)

        OfflineModelHint.markSeen(app)
        assertTrue(prefs("glosso_offline").getBoolean("model_hint_dismissed", false))
    }

    @Test
    fun `progress and take history keep their files`() {
        asNewProcess(ProgressStore, AttemptHistory)
        ProgressStore.init(app)
        ProgressStore.recordLesson("compat.lesson.1")
        assertTrue(prefs("glosso_stats").getString("progress", null).orEmpty().contains("compat.lesson.1"))

        AttemptHistory.init(app)
        AttemptHistory.recordTake("compat.item", 91)
        assertTrue(prefs("glosso_takes").getString("last_scores", null).orEmpty().contains("compat.item\":91"))
    }

    @Test
    fun `a crash stored by the previous version is reported`() {
        prefs("glosso_crash").edit().putString("exception", "IllegalStateException")
            .putString("where", "LessonViewModel.stepTo").commit()
        val crash = PendingCrash.take(app)
        assertEquals("IllegalStateException", crash?.exception)
        assertEquals("LessonViewModel.stepTo", crash?.where)
        assertFalse(prefs("glosso_crash").contains("exception"))
    }

    @Test
    fun `the analytics queue and switch written by the previous version are kept and sent`() {
        val event = ClientEvent(
            name = "lesson_started",
            sessionId = "s",
            appVersion = "2.5.4",
            language = "en",
            properties = JsonObject(emptyMap()),
            occurredAt = 1L,
        )
        val queueFile = File(app.filesDir, "analytics_queue.json")
        queueFile.writeText(Json.encodeToString(ListSerializer(ClientEvent.serializer()), listOf(event)))
        prefs("glosso_analytics").edit().putBoolean("enabled", true).commit()

        val sent = mutableListOf<ClientEvent>()
        Analytics.resetForTest()
        Analytics.scope = CoroutineScope(UnconfinedTestDispatcher())
        Analytics.sender = { batch -> sent += batch; 202 }
        Analytics.init(app)
        Analytics.flush()

        assertEquals(listOf("lesson_started"), sent.map { it.name })
        assertFalse("the drained queue is removed", queueFile.exists())

        Analytics.setEnabled(app, false)
        assertFalse(prefs("glosso_analytics").getBoolean("enabled", true))
        Analytics.setEnabled(app, true)
    }

    @Test
    fun `the Google account, product config and notification prompt keep their files`() {
        prefs("glosso_google_account").edit().putString("email", "learner@example.com").commit()
        GoogleAccountStore.init(app)
        assertEquals("learner@example.com", GoogleAccountStore.email.value)
        GoogleAccountStore.reconcile(emptyList())
        assertFalse(prefs("glosso_google_account").contains("email"))
        GoogleAccountStore.signedIn("other@example.com")
        assertEquals("other@example.com", prefs("glosso_google_account").getString("email", null))

        val config = ProductConfigRepository.encodeConfig(ProductConfigRepository.config.value)
        prefs("glosso_product").edit().putString("config", config).putString("account", "{\"plan\":\"PRO\"}").commit()
        ProductConfigRepository.api = { error("offline") }
        ProductConfigRepository.init(app)
        assertEquals("PRO", ProductConfigRepository.account.value?.plan)

        assertFalse(DownloadPermission.wasAsked(app))
        prefs("glosso_downloads").edit().putBoolean("notification_prompted", true).commit()
        assertTrue(DownloadPermission.wasAsked(app))
        prefs("glosso_downloads").edit().clear().commit()
        DownloadPermission.markAsked(app)
        assertTrue(prefs("glosso_downloads").getBoolean("notification_prompted", false))
    }

    @Test
    fun `the phonetic profile and weekly baseline keep their file and keys`() = kotlinx.coroutines.runBlocking {
        val profile = PhoneticProfileModel(stats = listOf(com.glosso.engine.model.PhonemeStatModel(phoneme = "θ", attempts = 10, correct = 6)))
        prefs("glosso_profile").edit().putString("phonetic_profile", ProfileCodec.encode(profile)).commit()
        val store = ProfileStore.get(app)
        store.await()
        assertEquals(10, store.current?.stats?.single()?.attempts)
        store.save(profile.copy(stats = listOf(com.glosso.engine.model.PhonemeStatModel(phoneme = "θ", attempts = 12, correct = 8))))
        assertEquals(12, ProfileCodec.decode(prefs("glosso_profile").getString("phonetic_profile", null))?.stats?.single()?.attempts)

        prefs("glosso_profile").edit().putString("weekly_baseline", "{\"en\":{\"weekStart\":20000,\"overall\":40}}").commit()
        PhonemeHistory.init(app)
        assertEquals(40, PhonemeHistory.baselineFor("en")?.overall)
    }

    @Test
    fun `offline packs and the model pack keep their paths`() {
        val layout = OfflineLayout(appFiles(app).filesDir)
        val offline = File(app.filesDir, "offline")
        assertEquals(File(offline, "index.json").toOkioPath(), layout.index)
        assertEquals(File(offline, "lessons/en.lesson.family.001.json").toOkioPath(), layout.bundle("en.lesson.family.001"))
        assertEquals(
            File(offline, "assets/9b35e1aebbc3eab2/audio/en.th.s.family.001.en-US-male.ogg").toOkioPath(),
            layout.asset("9b35e1aebbc3eab2", "audio/en.th.s.family.001.en-US-male.ogg"),
        )
        assertEquals(File(offline, "pending/l1.json").toOkioPath(), layout.pendingBundle("l1"))
        assertEquals(File(offline, "pending/l1.version").toOkioPath(), layout.pendingVersion("l1"))

        OnDeviceScorer.init(app)
        assertEquals(File(File(app.filesDir, "scoring"), PhonemeModelConfig.MODEL_FILE), OnDeviceScorer.modelPath())
        assertEquals(File(File(app.filesDir, "scoring"), PhonemeModelConfig.VOCAB_FILE), OnDeviceScorer.vocabPath())
    }
}
