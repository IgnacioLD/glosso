package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.Lock
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import com.glosso.app.platform.todayEpochDay
import com.glosso.engine.model.PhonemeHistoryModel
import com.glosso.engine.model.PhonemeObservationModel
import com.glosso.engine.model.PhonemeMatchModel
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.engine.profile.PhonemeMemory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/** Minimal key/value port, so the store logic is testable without Android. */
interface ProfilePrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/**
 * Serializes the profile with a version envelope and tolerates old or corrupt
 * data by returning null instead of throwing. Callers treat null as "no profile".
 */
object ProfileCodec {

    const val VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_PROFILE = "profile"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Envelope(
        val version: Int = VERSION,
        val profile: PhoneticProfileModel? = null,
    )

    @Serializable
    private data class HistoryEnvelope(
        val version: Int = VERSION,
        val history: PhonemeHistoryModel? = null,
    )

    fun encode(profile: PhoneticProfileModel): String =
        json.encodeToString(Envelope.serializer(), Envelope(VERSION, profile))

    /** Decodes [raw], returning null for empty, corrupt or unknown-version data. */
    fun decode(raw: String?): PhoneticProfileModel? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        // Legacy shape: a bare profile object from before the version envelope.
        val legacy = runCatching { json.decodeFromString(PhoneticProfileModel.serializer(), text) }
            .getOrNull()
        if (legacy != null && legacy.stats.isNotEmpty()) return legacy
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), text) }.getOrNull()
            ?: return null
        if (envelope.version != VERSION) return null
        return envelope.profile?.takeIf { it.stats.isNotEmpty() }
    }

    fun encodeHistory(history: List<PhonemeObservationModel>): String =
        json.encodeToString(HistoryEnvelope.serializer(), HistoryEnvelope(VERSION, PhonemeHistoryModel(history)))

    /** Decodes the dated log, returning an empty list for missing or corrupt data. */
    fun decodeHistory(raw: String?): List<PhonemeObservationModel> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        // Legacy shape: a bare observation list.
        val bare = runCatching { json.decodeFromString(PhonemeHistoryModel.serializer(), text) }
            .getOrNull()
        if (bare != null && bare.observations.isNotEmpty()) return bare.observations
        val envelope = runCatching { json.decodeFromString(HistoryEnvelope.serializer(), text) }
            .getOrNull() ?: return emptyList()
        if (envelope.version != VERSION) return emptyList()
        return envelope.history?.observations.orEmpty()
    }
}

/**
 * The learner's phonetic profile on this device: the running per-sound record
 * the scorer folds attempt by attempt, and the dated observation log behind it.
 *
 * The aggregate is a plain serializable value replayed to the server with each
 * attempt, which keeps scoring stateless. The log never leaves the device: it is
 * what lets the app show a sound's real history (43 -> 51 -> 63 -> 71) and detect
 * patterns over time.
 *
 * One process-wide instance so every engine and screen sees the same value live.
 * The first read happens off the main thread; callers that act on the profile at
 * startup await [await] so a persisted profile is never missed.
 */
class ProfileStore private constructor(
    private val prefs: ProfilePrefs,
    scope: CoroutineScope,
    private val today: () -> Long = { todayEpochDay() },
) {

    private val scope = scope

    /**
     * The language whose profile is active. Sounds differ per language, so an
     * English "θ" record must not plan French drills; each language keeps its own
     * record under its own key. English (the default) keeps the original key.
     */
    @Volatile
    private var language: String = "en"

    private val _profile = MutableStateFlow<PhoneticProfileModel?>(null)
    val profile: StateFlow<PhoneticProfileModel?> = _profile.asStateFlow()

    private val _history = MutableStateFlow<List<PhonemeObservationModel>>(emptyList())

    /** The dated per-sound log for the active language, oldest first. */
    val history: StateFlow<List<PhonemeObservationModel>> = _history.asStateFlow()

    /** The current profile, or null before load completes or the first attempt. */
    val current: PhoneticProfileModel? get() = _profile.value

    private val loaded = CompletableDeferred<Unit>()
    private val scoring = Mutex()

    init {
        scope.launch {
            try {
                _profile.value = read()
                _history.value = readHistory()
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    /** Switches the active language's profile, loading the stored one for it. */
    fun setLanguage(code: String) {
        val normalized = if (code.isBlank()) "en" else code
        if (normalized == language) return
        language = normalized
        _profile.value = null
        _history.value = emptyList()
        scope.launch {
            _profile.value = read()
            _history.value = readHistory()
        }
    }

    private suspend fun read(): PhoneticProfileModel? =
        runCatching { ProfileCodec.decode(prefs.getString(keyFor(language))) }.getOrNull()

    private suspend fun readHistory(): List<PhonemeObservationModel> =
        runCatching { ProfileCodec.decodeHistory(prefs.getString(historyKeyFor(language))) }
            .getOrDefault(emptyList())

    /** Suspends until the stored profile has been read from disk. */
    suspend fun await() = loaded.await()

    /** Persists [profile] and publishes it to every observer. */
    fun save(profile: PhoneticProfileModel) {
        prefs.putString(keyFor(language), ProfileCodec.encode(profile))
        _profile.value = profile
    }

    /**
     * Persists the folded aggregate and appends this attempt's dated per-sound
     * records. The date is the learner's own day, so the log stays correct no
     * matter whether the attempt was scored in the cloud or on device.
     */
    fun record(profile: PhoneticProfileModel, alignment: List<PhonemeMatchModel>) {
        val updated = PhonemeMemory.record(_history.value, alignment, today(), language)
        prefs.putString(keyFor(language), ProfileCodec.encode(profile))
        prefs.putString(historyKeyFor(language), ProfileCodec.encodeHistory(updated))
        _profile.value = profile
        _history.value = updated
    }

    /**
     * Debug-only fixture: replaces both the aggregate and the log with a
     * documented multi-day history, so the profile's trend and "today" views can
     * be exercised without weeks of real recordings. Never called from release
     * code; see `MainActivity` and `docs/PHONEME_PROFILE.md`.
     */
    fun seedDemo(reference: Long = today()) {
        val history = DemoProfile.history(reference)
        prefs.putString(keyFor(language), ProfileCodec.encode(DemoProfile.aggregate(history)))
        prefs.putString(historyKeyFor(language), ProfileCodec.encodeHistory(history))
        _profile.value = DemoProfile.aggregate(history)
        _history.value = history
    }

    /**
     * Serializes work that reads then writes the profile (a scoring round-trip),
     * so two attempts in flight cannot read the same base and clobber each other's
     * folded result: the second waits and reads the first's update.
     */
    suspend fun <T> locked(block: suspend () -> T): T = scoring.withLock { block() }

    companion object {
        private const val PREFS = "glosso_profile"
        const val KEY = "phonetic_profile"
        const val HISTORY_KEY = "phoneme_history"

        /** The storage key for a language: the original key for English. */
        fun keyFor(code: String): String = if (code == "en") KEY else "${KEY}_$code"

        fun historyKeyFor(code: String): String =
            if (code == "en") HISTORY_KEY else "${HISTORY_KEY}_$code"

        @Volatile
        private var instance: ProfileStore? = null

        private val lock = Lock()

        fun get(context: PlatformContext): ProfileStore =
            instance ?: lock.withLock {
                instance ?: fromPrefs(StoreProfilePrefs(keyValueStore(context, PREFS))).also { instance = it }
            }

        fun fromPrefs(
            prefs: ProfilePrefs,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            today: () -> Long = { todayEpochDay() },
        ): ProfileStore = ProfileStore(prefs, scope, today)
    }
}

/** The profile's keys in the `glosso_profile` store (the SharedPreferences file on Android). */
private class StoreProfilePrefs(private val prefs: KeyValueStore) : ProfilePrefs {

    override fun getString(key: String): String? = prefs.getString(key)

    override fun putString(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }
}
