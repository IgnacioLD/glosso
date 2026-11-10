package com.glosso.engine.phonetics

import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel

/**
 * Builds a learner's phonetic profile by folding one attempt's phone alignment
 * at a time.
 *
 * The profile is deliberately a plain, serializable value with no storage of its
 * own: the caller keeps it (on device or in the account) and sends it back with
 * the next attempt. That keeps scoring stateless and the profile portable.
 */
object PhoneticProfile {

    /** A sound needs at least this many attempts before it can be called weak. */
    const val MIN_ATTEMPTS = 3

    /** One attempt's alignment folded into [profile] (or a fresh one). */
    fun fold(profile: PhoneticProfileModel?, alignment: List<PhonemeMatch>): PhoneticProfileModel {
        val stats = (profile?.stats ?: emptyList())
            .associateByTo(LinkedHashMap()) { it.phoneme }

        for (match in alignment) {
            if (match.expected.isBlank() || match.expected == "-") continue
            val current = stats[match.expected] ?: PhonemeStatModel(match.expected)
            val correct = current.correct + if (match.status == MatchStatus.PERFECT) 1 else 0
            val errors = current.errors.toMutableMap()
            if (match.status != MatchStatus.PERFECT) {
                val key = if (match.actual == "-") DROPPED else match.actual
                errors[key] = (errors[key] ?: 0) + 1
            }
            stats[match.expected] = current.copy(
                attempts = current.attempts + 1,
                correct = correct,
                errors = errors,
            )
        }

        return PhoneticProfileModel(
            stats = stats.values.sortedWith(compareBy({ it.accuracy }, { it.phoneme })),
        )
    }

    /**
     * The sounds most worth practising: enough attempts to be meaningful and not
     * already perfect, lowest accuracy first.
     */
    fun weakest(
        profile: PhoneticProfileModel,
        minAttempts: Int = MIN_ATTEMPTS,
        limit: Int = 5,
    ): List<PhonemeStatModel> =
        profile.stats
            .filter { it.attempts >= minAttempts && it.accuracy < 100 }
            .sortedWith(compareBy({ it.accuracy }, { it.phoneme }))
            .take(limit)

    /** Marker stored when a reference sound was not produced at all. */
    const val DROPPED = "(dropped)"
}
