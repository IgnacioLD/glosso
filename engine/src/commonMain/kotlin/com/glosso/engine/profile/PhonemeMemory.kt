package com.glosso.engine.profile

import com.glosso.engine.model.MatchStatusModel
import com.glosso.engine.model.PhonemeMatchModel
import com.glosso.engine.model.PhonemeObservationModel
import com.glosso.engine.phonetics.ErrorRules

/**
 * The learner's memory of individual sounds over time: one dated record per sound
 * per attempt. This is what turns "how is /θ/ doing?" into "how has /θ/ been
 * doing?".
 *
 * Pure and platform-free: the caller keeps and stores the list, nothing here
 * touches disk or time. Dates arrive as epoch days, so the same code runs on
 * device and in tests.
 */
object PhonemeMemory {

    /**
     * Cap of retained records per sound. Old enough history is dropped once a
     * sound passes this many attempts, so the log cannot grow without bound.
     */
    const val MAX_PER_PHONEME = 120

    /** Marker stored when a reference sound was not produced at all. */
    const val DROPPED = "(dropped)"

    /**
     * Appends one attempt's per-sound records, preserving chronological order and
     * bounding each sound's history. Returns the same list when the alignment
     * carries no scoreable sound.
     */
    fun record(
        history: List<PhonemeObservationModel>,
        alignment: List<PhonemeMatchModel>,
        day: Long,
        language: String = "en",
    ): List<PhonemeObservationModel> {
        val fresh = observationsOf(alignment, day, language)
        if (fresh.isEmpty()) return history
        return (history + fresh)
            .groupBy { it.phoneme }
            .values
            .flatMap { it.takeLast(MAX_PER_PHONEME) }
            .sortedBy { it.day }
    }

    /**
     * Collapses one attempt's alignment into one record per distinct reference
     * sound. A dropped sound and a substitution are both misses; the record keeps
     * whichever wrong phone was produced most often, so a pattern can be named.
     */
    fun observationsOf(
        alignment: List<PhonemeMatchModel>,
        day: Long,
        language: String = "en",
    ): List<PhonemeObservationModel> {
        val groups = LinkedHashMap<String, MutableList<PhonemeMatchModel>>()
        for (match in alignment) {
            if (match.expected.isBlank() || match.expected == "-") continue
            groups.getOrPut(match.expected) { mutableListOf() }.add(match)
        }
        return groups.map { (phoneme, matches) ->
            val correct = matches.count { it.status == MatchStatusModel.PERFECT }
            val error = if (correct == matches.size) null else {
                matches.asSequence()
                    .filter { it.status != MatchStatusModel.PERFECT }
                    .groupingBy { if (it.actual == "-") DROPPED else it.actual }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
            }
            PhonemeObservationModel(
                phoneme = phoneme,
                day = day,
                total = matches.size,
                correct = correct,
                error = error,
                // Name the error through the same curated rules the feedback uses,
                // so the (phoneme, pattern) pair is consistent across the product.
                pattern = patternFor(phoneme, error, language),
            )
        }
    }

    /** The curated rule id for a wrong phone, or null when nothing names it. */
    fun patternFor(phoneme: String, error: String?, language: String = "en"): String? = when (error) {
        null -> null
        DROPPED -> ErrorRules.ruleFor(phoneme, null, language)?.id
        else -> ErrorRules.ruleFor(phoneme, error, language)?.id
    }

    /** This sound's records, oldest first. */
    fun series(
        history: List<PhonemeObservationModel>,
        phoneme: String,
    ): List<PhonemeObservationModel> =
        history.filter { it.phoneme == phoneme }.sortedBy { it.day }

    /** Every sound the log has a record for, in first-seen order. */
    fun sounds(history: List<PhonemeObservationModel>): List<String> =
        history.map { it.phoneme }.distinct()
}
