package com.glosso.engine.profile

/** The independent dimensions the product reports on, one number each. */
enum class SkillDimension { PRONUNCIATION, COMPREHENSION, VOCABULARY, GRAMMAR }

/** One competence's score, only produced when it has enough real data behind it. */
data class SkillScore(
    val dimension: SkillDimension,
    val score: Int,
    val sample: Int,
    val improving: Boolean = false,
)

/** Raw exercise outcomes for one competence. */
data class CompetenceSignal(val correct: Int = 0, val total: Int = 0) {
    val accuracy: Int get() = if (total <= 0) 0 else correct * 100 / total
}

/**
 * The multidimensional profile, without pretending. It reports pronunciation
 * from the phonetic profile and comprehension, vocabulary and grammar from the
 * exercises the learner has actually answered. A dimension with too little data
 * is simply absent: the UI must not invent a number for it, and no score is ever
 * stated as a CEFR equivalence.
 */
object SkillProfile {

    /** Fewer answered items than this is not a signal yet. */
    const val MIN_SAMPLE = 5

    fun build(
        pronunciation: SkillScore?,
        competencies: Map<SkillDimension, CompetenceSignal> = emptyMap(),
        minSample: Int = MIN_SAMPLE,
    ): List<SkillScore> {
        val scores = ArrayList<SkillScore>(4)
        if (pronunciation != null && pronunciation.sample >= minSample) scores += pronunciation
        for (dimension in listOf(
            SkillDimension.COMPREHENSION,
            SkillDimension.VOCABULARY,
            SkillDimension.GRAMMAR,
        )) {
            val signal = competencies[dimension] ?: continue
            if (signal.total >= minSample) {
                scores += SkillScore(dimension, signal.accuracy, signal.total)
            }
        }
        return scores
    }

    /**
     * The competence an exercise type exercises. Content-driven types that are not
     * mapped return null, so they are never counted toward a dimension.
     */
    fun dimensionForExerciseType(type: String?): SkillDimension? =
        when (type?.trim()?.lowercase()) {
            "multiple-choice", "match" -> SkillDimension.VOCABULARY
            "fill-blank", "order" -> SkillDimension.GRAMMAR
            "true-false" -> SkillDimension.COMPREHENSION
            else -> null
        }
}
