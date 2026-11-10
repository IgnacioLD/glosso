package com.glosso.engine.phonetics

import kotlin.math.roundToInt
import com.glosso.engine.model.MatchStatusModel
import com.glosso.engine.model.PhonemeMatchModel
import com.glosso.engine.model.PhoneticProfileModel

/**
 * The arithmetic behind the visible value loop: how one take scored sound by
 * sound, how a learner's sound is trending, and how much of that to show. Kept
 * pure and platform-free so the client and any future screen compute the same
 * numbers, and so the loop can be tested without a device.
 */
object PhoneticLoop {

    /** A sound that landed exactly counts as a full score. */
    const val PERFECT_SCORE = 100

    /** A close-but-not-quite sound still reads as an "almost", matching the bands. */
    const val CLOSE_SCORE = 60

    /** A missed sound is the only one that earns nothing. */
    const val MISSED_SCORE = 0

    /** One sound's result inside a single take. */
    data class SoundScore(
        val phoneme: String,
        val score: Int,
        val correct: Int,
        val total: Int,
    )

    fun scoreOf(status: MatchStatusModel): Int = when (status) {
        MatchStatusModel.PERFECT -> PERFECT_SCORE
        MatchStatusModel.CLOSE -> CLOSE_SCORE
        MatchStatusModel.MISSED -> MISSED_SCORE
    }

    /**
     * Scores each reference sound of one attempt, averaged across the places it
     * appears in the sentence and ordered weakest first, so the learner sees the
     * sound to work on before the ones already landing.
     */
    fun breakdown(alignment: List<PhonemeMatchModel>): List<SoundScore> {
        val bySound = LinkedHashMap<String, MutableList<Int>>()
        for (match in alignment) {
            if (match.expected.isBlank() || match.expected == "-") continue
            bySound.getOrPut(match.expected) { mutableListOf() }.add(scoreOf(match.status))
        }
        return bySound
            .map { (phoneme, scores) ->
                SoundScore(
                    phoneme = phoneme,
                    score = scores.average().roundToInt(),
                    correct = scores.count { it == PERFECT_SCORE },
                    total = scores.size,
                )
            }
            .sortedWith(compareBy({ it.score }, { it.phoneme }))
    }

    /** A sound's running accuracy in the profile, or null when it is unseen. */
    fun profileScore(profile: PhoneticProfileModel?, phoneme: String): Int? =
        profile?.stats?.firstOrNull { samePhone(it.phoneme, phoneme) }?.accuracy

    /** Overall pronunciation accuracy across the profile, 0..100. */
    fun overall(profile: PhoneticProfileModel?): Int {
        val stats = profile?.stats.orEmpty()
        val attempts = stats.sumOf { it.attempts }
        return if (attempts == 0) 0 else stats.sumOf { it.correct } * 100 / attempts
    }

    /** The change between two measurements, or null when either is unknown. */
    fun delta(before: Int?, after: Int?): Int? =
        if (before == null || after == null) null else after - before

    /** True when [after] is a real step up from [before]. */
    fun improved(before: Int, after: Int): Boolean = after > before

    /** Same phone ignoring vowel length, so /i/ and /iː/ compare equal. */
    fun samePhone(a: String, b: String): Boolean = a.removeSuffix("ː") == b.removeSuffix("ː")
}
