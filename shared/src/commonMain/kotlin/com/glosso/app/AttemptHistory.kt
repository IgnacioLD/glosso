package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** How this take reads against the previous one on the same item. */
enum class TakeTrend { UP, SAME, DOWN }

/**
 * Compares two takes of the same item. There is no comparison for the first one.
 */
fun takeTrend(current: Int, previous: Int): TakeTrend = when {
    current > previous -> TakeTrend.UP
    current < previous -> TakeTrend.DOWN
    else -> TakeTrend.SAME
}

/**
 * The last score the learner got on each item, kept on the device so the result
 * can say whether this take improved on the previous one. Keyed by target
 * language and item id: the same sentence shows up in free practice, the lesson
 * player and the onboarding seed, and the learner experiences it as one item.
 *
 * Local only, like the rest of the progress stores; nothing is uploaded.
 */
object AttemptHistory {

    private const val PREFS = "glosso_takes"
    private const val KEY = "last_scores"

    /** Soft cap so the map cannot grow without bound over a long history. */
    private const val MAX_ITEMS = 500

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: KeyValueStore
    private var scores: Map<String, Int> = emptyMap()

    fun init(context: PlatformContext) {
        if (::prefs.isInitialized) return
        prefs = keyValueStore(context, PREFS)
        scores = read()
    }

    /** The stable key of one item: its id scoped by the target language. */
    fun key(itemId: String, language: String = LanguagePreferences.current.code): String =
        "$language:$itemId"

    /**
     * How many items of [language] were last taken at mastery, the phrase seal's
     * count. An item that slipped below mastery stops counting: it is the latest
     * take that stands, not the best one on record.
     */
    fun masteredCount(language: String = LanguagePreferences.current.code): Int {
        if (!::prefs.isInitialized) return 0
        val prefix = "$language:"
        return scores.count { it.key.startsWith(prefix) && it.value >= SoundProgress.MASTERED }
    }

    /**
     * Stores [score] as the latest take on [itemId] and returns what was there
     * before, or null on the first take. An item without a stable id is never
     * compared: recording it under a shared key would mix different phrases.
     */
    fun recordTake(itemId: String?, score: Int): Int? {
        val id = itemId?.takeIf { it.isNotBlank() } ?: return null
        if (!::prefs.isInitialized) return null
        val key = key(id)
        val before = scores[key]
        val next = prune(scores + (key to score))
        persist(next)
        scores = next
        return before
    }

    private fun prune(scores: Map<String, Int>): Map<String, Int> =
        if (scores.size <= MAX_ITEMS) {
            scores
        } else {
            scores.entries.drop(scores.size - MAX_ITEMS).associate { it.toPair() }
        }

    private fun read(): Map<String, Int> =
        runCatching {
            prefs.getString(KEY)
                ?.let { json.decodeFromString<Map<String, Int>>(it) }
                .orEmpty()
        }.getOrDefault(emptyMap())

    private fun persist(value: Map<String, Int>) {
        val serializer = MapSerializer(String.serializer(), Int.serializer())
        prefs.edit { putString(KEY, json.encodeToString(serializer, value)) }
    }
}
