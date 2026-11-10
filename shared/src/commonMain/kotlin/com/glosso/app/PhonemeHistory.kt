package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import com.glosso.app.platform.todayEpochDay
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.engine.phonetics.PhoneticLoop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The snapshot a weekly improvement is measured against. [sounds] maps each sound
 * to its accuracy when the week started, so "43 -> 58" is a real change and not a
 * lifetime number dressed up as progress.
 */
@Serializable
data class WeeklyBaseline(
    val weekStart: Long = 0,
    val overall: Int = 0,
    val sounds: Map<String, Int> = emptyMap(),
)

/**
 * Keeps, per language, the phonetic profile as it stood at the start of the
 * current week. It is what turns a cumulative profile into the one question the
 * product is really about: am I pronouncing better since I started this week?
 *
 * The baseline is captured from the first scored take of the week (post-fold),
 * so the very first attempt of a new week reads as "no change yet" instead of a
 * fake jump. Everything here is local; nothing is uploaded.
 */
object PhonemeHistory {

    private const val PREFS = "glosso_profile"
    private const val KEY = "weekly_baseline"
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var prefs: KeyValueStore

    private val _baselines = MutableStateFlow<Map<String, WeeklyBaseline>>(emptyMap())
    val baselines: StateFlow<Map<String, WeeklyBaseline>> = _baselines.asStateFlow()

    fun init(context: PlatformContext) {
        if (::prefs.isInitialized) return
        prefs = keyValueStore(context, PREFS)
        _baselines.value = read()
    }

    /** The baseline for a language, for the progress screen. */
    fun baselineFor(language: String): WeeklyBaseline? = _baselines.value[language]

    /**
     * Captures this week's baseline from [profile] the first time the week is
     * seen. Called after a take is folded in, so the week starts where the learner
     * actually stands and later attempts show only what changed since.
     */
    fun observe(profile: PhoneticProfileModel?) {
        if (!::prefs.isInitialized) return
        if (profile == null || profile.stats.isEmpty()) return
        val language = LanguagePreferences.current.code
        val weekStart = weekStartOf(todayEpochDay())
        val fresh = capture(_baselines.value[language], weekStart, profile) ?: return
        val next = _baselines.value + (language to fresh)
        persist(next)
        _baselines.value = next
    }

    private fun read(): Map<String, WeeklyBaseline> =
        runCatching {
            prefs.getString(KEY)
                ?.let { json.decodeFromString<Map<String, WeeklyBaseline>>(it) }
                .orEmpty()
        }.getOrDefault(emptyMap())

    private fun persist(value: Map<String, WeeklyBaseline>) {
        val serializer = MapSerializer(String.serializer(), WeeklyBaseline.serializer())
        prefs.edit { putString(KEY, json.encodeToString(serializer, value)) }
    }

    /**
     * The Monday that starts the week holding [epochDay], as an epoch day.
     * 1970-01-01 (epoch day 0) was a Thursday, hence the +3 offset.
     */
    fun weekStartOf(epochDay: Long): Long = epochDay - (epochDay + 3).mod(7L)

    /**
     * A new baseline when the stored one is for another week, else null. Pure so
     * the week rollover can be tested without a clock or storage.
     */
    fun capture(
        existing: WeeklyBaseline?,
        weekStart: Long,
        profile: PhoneticProfileModel?,
    ): WeeklyBaseline? {
        if (existing != null && existing.weekStart == weekStart) return null
        val stats = profile?.stats.orEmpty()
        if (stats.isEmpty()) return null
        return WeeklyBaseline(
            weekStart = weekStart,
            overall = PhoneticLoop.overall(profile),
            sounds = stats.associate { it.phoneme to it.accuracy },
        )
    }

    /** The baseline accuracy for [phoneme], tolerating length-only spellings. */
    fun baselineSound(baseline: WeeklyBaseline?, phoneme: String): Int? {
        val sounds = baseline?.sounds ?: return null
        return sounds[phoneme]
            ?: sounds.entries.firstOrNull { PhoneticLoop.samePhone(it.key, phoneme) }?.value
    }

    /** Current accuracy for [phoneme], tolerating length-only spellings. */
    fun currentSound(profile: PhoneticProfileModel?, phoneme: String): Int? =
        profile?.stats
            ?.firstOrNull { PhoneticLoop.samePhone(it.phoneme, phoneme) }
            ?.accuracy
}
