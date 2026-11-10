package com.glosso.app

import com.glosso.engine.lesson.RecallCard
import com.glosso.engine.lesson.RecallScheduler
import com.glosso.engine.lesson.RecallVerdict
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import com.glosso.app.platform.todayEpochDay

/** Minimal key/value port, so the store logic is testable without Android. */
interface RecallPrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** Versioned JSON of the recall cards; old or corrupt data reads as no cards. */
object RecallCodec {

    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Envelope(val version: Int = VERSION, val cards: List<RecallCard> = emptyList())

    fun encode(cards: List<RecallCard>): String = json.encodeToString(Envelope.serializer(), Envelope(VERSION, cards))

    fun decode(raw: String?): List<RecallCard> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val envelope = runCatching { json.decodeFromString(Envelope.serializer(), text) }.getOrNull() ?: return emptyList()
        return if (envelope.version == VERSION) envelope.cards else emptyList()
    }
}

/**
 * The phrases the learner has said from memory and when each comes back, per
 * target language. Everything stays on the device: the cards are a snapshot of
 * content, and the schedule is the learner's own history.
 */
class RecallStore(
    private val prefs: RecallPrefs,
    /** Enables [debugMakeDue] (`BuildConfig.DEBUG` on Android). Off in release. */
    private val debugTools: Boolean = false,
    private val today: () -> Long = ::todayEpochDay,
) {

    fun cards(language: String): List<RecallCard> = RecallCodec.decode(prefs.getString(key(language)))

    /** The phrases to ask back now that can be prompted in [uiLanguage], most overdue first. */
    fun due(language: String, uiLanguage: String): List<RecallCard> =
        RecallScheduler.due(cards(language).filter { !it.cues[uiLanguage].isNullOrBlank() }, today())

    /**
     * Stores the outcome of saying [card]. A phrase met again keeps the rung it
     * had reached; its text, clips and prompts are refreshed from [card].
     */
    fun record(language: String, card: RecallCard, verdict: RecallVerdict?): RecallCard {
        val stored = cards(language)
        val known = stored.firstOrNull { it.ref == card.ref }
        val base = if (known != null) card.copy(step = known.step, lapses = known.lapses, dueDay = known.dueDay) else card
        // Saying it again before it is due (the lesson redone the same day) is
        // practice: the interval has not passed, so it proves nothing new.
        val early = known != null && known.dueDay > today() && verdict == RecallVerdict.RECALLED
        val reviewed = if (early) base else RecallScheduler.review(base, verdict, today())
        // Recalled past the last rung, a phrase is known and leaves the store,
        // which keeps it to the phrases still being learned.
        val retired = !early && verdict == RecallVerdict.RECALLED && base.step >= RecallScheduler.LADDER.size
        val next = if (retired) stored.filter { it.ref != card.ref } else RecallScheduler.merge(stored, reviewed)
        prefs.putString(key(language), RecallCodec.encode(next))
        return reviewed
    }

    /** Debug-only: makes every phrase due now, to see a review without waiting days. */
    fun debugMakeDue(language: String) {
        if (!debugTools) return
        prefs.putString(key(language), RecallCodec.encode(cards(language).map { it.copy(dueDay = today()) }))
    }

    private fun key(language: String) = "cards_$language"

    companion object {
        private const val FILE = "glosso_recall"

        fun of(context: PlatformContext, debugTools: Boolean = false): RecallStore {
            val shared = keyValueStore(context, FILE)
            return RecallStore(
                object : RecallPrefs {
                    override fun getString(key: String): String? = shared.getString(key)
                    override fun putString(key: String, value: String) = shared.edit { putString(key, value) }
                },
                debugTools = debugTools,
            )
        }
    }
}
