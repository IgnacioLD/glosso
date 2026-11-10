package com.glosso.engine.model

/**
 * The single source of truth for how a pronunciation attempt is judged.
 *
 * A single averaged score is a poor gate for mastery: errors dilute as a
 * sentence gets longer, so a ten-word sentence with one destroyed word used to
 * score higher than a two-word sentence with one wrong sound. These rules
 * combine the overall score with structural requirements, so "mastered" means
 * the learner pronounced the whole sentence, not that they averaged well.
 *
 * Tuning guidance: [MASTERY_THRESHOLD] is the overall quality bar,
 * [COMPLETENESS_FLOOR] stops a fluent half-sentence passing, [WORD_FLOOR]
 * stops one destroyed word hiding behind a good average, and
 * [PHONES_PER_TOLERATED_SLIP] sets how many dropped or added sounds a long take
 * may carry and still read as good. Change nothing else.
 *
 * Verdict bands the learner sees (sentence level, see [sentenceLevel]):
 *
 * | Level    | Condition                                                             |
 * | -------- | --------------------------------------------------------------------- |
 * | PERFECT  | every reference sound exact, nothing added (never relaxed)            |
 * | MASTERED | score >= 85, completeness >= 80, no word < 50, no substituted sound,  |
 * |          | and at most n / 15 dropped or added sounds (n = reference sounds)     |
 * | ALMOST   | score >= 70                                                           |
 * | ROUGH    | score >= 50                                                           |
 * | NOT_YET  | below 50                                                              |
 *
 * None of this changes the score.
 */
object ScoringConfig {

    /** Overall score at or above this is mastery-quality. */
    const val MASTERY_THRESHOLD = 85

    /** Scores at or above this (but below mastery) were close. */
    const val CLOSE_THRESHOLD = 70

    /** Percent of the reference sounds the learner must actually produce. */
    const val COMPLETENESS_FLOOR = 80

    /** No individual word may score below this, however good the average is. */
    const val WORD_FLOOR = 50

    /**
     * A good take may carry one dropped or added sound per this many reference
     * sounds: in a long sentence that is mostly connected speech (a reduced weak
     * syllable, a linking r) or the recognizer splitting a sound, and a 95+ take
     * no longer drops to ALMOST over it. Shorter takes tolerate none. A
     * substituted sound is never tolerated: swapping one phoneme for another is
     * exactly what the minimal-pair drills test. The slip is still underlined on
     * its word, which keeps its own strict level ([levelFor]).
     */
    const val PHONES_PER_TOLERATED_SLIP = 15

    /** Score band only - drives colour, haptics and wording. */
    fun isMasteryBand(score: Int): Boolean = score >= MASTERY_THRESHOLD

    fun isCloseBand(score: Int): Boolean = score >= CLOSE_THRESHOLD

    /**
     * The named level for a single word. [perfect] wins outright: a word with
     * no missed or merely-close sound is never described as just "mastered".
     *
     * [hardError] is a sound the learner genuinely got wrong - a substituted or
     * dropped phoneme, as opposed to an allophone the scorer tolerates (an
     * unreleased final stop). A word with a hard error is never MASTERED, however
     * high its averaged score: a single substituted vowel in a three-phone word
     * averages to 90, which must still read as ALMOST, not "mastered".
     */
    fun levelFor(score: Int, perfect: Boolean = false, hardError: Boolean = false): MasteryLevel = when {
        perfect -> MasteryLevel.PERFECT
        score >= MASTERY_THRESHOLD && !hardError -> MasteryLevel.MASTERED
        score >= CLOSE_THRESHOLD -> MasteryLevel.ALMOST
        score >= WORD_FLOOR -> MasteryLevel.ROUGH
        else -> MasteryLevel.NOT_YET
    }

    /**
     * The named level for a whole sentence. Mastery is a gate, not a band, so a
     * high average with a skipped or destroyed word cannot claim MASTERED.
     */
    fun sentenceLevel(score: Int, isMastery: Boolean, perfect: Boolean = false): MasteryLevel = when {
        perfect -> MasteryLevel.PERFECT
        isMastery -> MasteryLevel.MASTERED
        score >= CLOSE_THRESHOLD -> MasteryLevel.ALMOST
        score >= WORD_FLOOR -> MasteryLevel.ROUGH
        else -> MasteryLevel.NOT_YET
    }

    /** How many dropped or added sounds a take over [referencePhones] sounds may carry and still be good. */
    fun toleratedSlips(referencePhones: Int): Int =
        referencePhones.coerceAtLeast(0) / PHONES_PER_TOLERATED_SLIP

    /**
     * The full mastery decision: a good overall score, most of the sentence
     * actually attempted, no word left destroyed, no sound substituted, and no
     * more dropped or added sounds than the take's length tolerates
     * ([toleratedSlips]).
     *
     * [substitutions] and [droppedOrAdded] count genuine errors only: a take can
     * average above [MASTERY_THRESHOLD] while still containing them, and a
     * mastery label must not paper over a wrong phoneme or more than a stray gap.
     * Allophones the scorer tolerates (unreleased final stops, flaps) count as
     * neither.
     */
    fun qualifiesForMastery(
        score: Int,
        completeness: Int,
        wordScores: List<Int>,
        substitutions: Int = 0,
        droppedOrAdded: Int = 0,
        referencePhones: Int = 0,
    ): Boolean =
        isMasteryBand(score) &&
            completeness >= COMPLETENESS_FLOOR &&
            wordScores.all { it >= WORD_FLOOR } &&
            substitutions == 0 &&
            droppedOrAdded <= toleratedSlips(referencePhones)

    /** The weakest word, or null when there are none to compare. */
    fun weakestWordIndex(wordScores: List<Int>): Int? =
        wordScores.withIndex().minByOrNull { it.value }?.index
}
