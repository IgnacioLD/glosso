package com.glosso.engine.lesson

import kotlinx.serialization.Serializable

/**
 * One phrase the learner has said from memory and will be asked again. It is a
 * snapshot of everything a review needs (text, references, clips, the prompt in
 * each interface language and the line that prompts it), so a review runs with
 * no content fetch, and of where the phrase stands on the interval ladder.
 */
@Serializable
data class RecallCard(
    /** `<dialogueId>#t<turn>`. */
    val ref: String,
    val lessonId: String = "",
    val text: String,
    val ipa: String = "",
    val displayIpa: String = "",
    val accentIpa: Map<String, String> = emptyMap(),
    /** Reference clips per voice key, as on the dialogue turn. */
    val audio: Map<String, String> = emptyMap(),
    /** The prompt per interface language: what to say, in the learner's own words. */
    val cues: Map<String, String> = emptyMap(),
    val speaker: String = "",
    /** The other character's line right before this one, when there is one. */
    val partnerSpeaker: String = "",
    val partnerText: String = "",
    val partnerAudio: Map<String, String> = emptyMap(),
    val partnerTranslations: Map<String, String> = emptyMap(),
    /** The accent whose wording [text] is ("en-US"), so a review plays that accent's clip. */
    val accent: String = "",
    /** How many intervals of [RecallScheduler.LADDER] the phrase has passed. */
    val step: Int = 0,
    /** Epoch day the phrase is next asked for. */
    val dueDay: Long = 0,
    /** How many times it was not recalled. */
    val lapses: Int = 0,
) {
    val hasPartner: Boolean get() = partnerText.isNotBlank()

    /** The IPA to show the learner: the textbook form when present, else the reference. */
    val displayPhonetic: String get() = displayIpa.ifBlank { ipa }
}

/**
 * Spaced recall of phrases: a phrase said from memory comes back after a
 * growing number of days, and one that was not recalled comes back tomorrow.
 * Pure: days in, days out, no clock. It is a fixed ladder on purpose (simple to
 * reason about and to test); the sound profile keeps its own scheduler.
 */
object RecallScheduler {

    /** Days until the next review after each successful recall. */
    val LADDER: List<Int> = listOf(1, 3, 7, 16, 35)

    /** How many phrases one lesson asks back at most, so review never becomes the lesson. */
    const val REVIEW_LIMIT = 4

    /**
     * [card] after a take on [today]. A recalled phrase climbs one rung; a
     * partly recalled one stays on its rung and returns tomorrow; one that was
     * not recalled (another phrase, or shown without saying it) starts again.
     */
    fun review(card: RecallCard, verdict: RecallVerdict?, today: Long): RecallCard = when (verdict) {
        RecallVerdict.RECALLED -> {
            val interval = LADDER[card.step.coerceIn(0, LADDER.lastIndex)]
            card.copy(step = (card.step + 1).coerceAtMost(LADDER.size), dueDay = today + interval)
        }
        RecallVerdict.PARTIAL -> card.copy(dueDay = today + 1)
        RecallVerdict.NOT_THAT_PHRASE, null -> card.copy(step = 0, dueDay = today + 1, lapses = card.lapses + 1)
    }

    /**
     * The phrases to ask on [today]: the most overdue first, then the ones that
     * were missed most, at most [limit].
     */
    fun due(cards: Collection<RecallCard>, today: Long, limit: Int = REVIEW_LIMIT): List<RecallCard> =
        cards.filter { it.dueDay <= today }
            .sortedWith(compareBy<RecallCard> { it.dueDay }.thenByDescending { it.lapses }.thenBy { it.ref })
            .take(limit)

    /**
     * [cards] with [reviewed] stored. The schedule belongs to the stored card;
     * the snapshot (text, clips, prompts) is refreshed from the new one, so a
     * corrected phrase replaces the old wording.
     */
    fun merge(cards: List<RecallCard>, reviewed: RecallCard): List<RecallCard> =
        cards.filter { it.ref != reviewed.ref } + reviewed
}
