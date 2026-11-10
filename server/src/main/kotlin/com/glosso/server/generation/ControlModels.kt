package com.glosso.server.generation

import com.glosso.engine.model.ScoringConfig
import java.time.Instant
import java.util.UUID

/**
 * How many learners a Need Class represents, grouped by a CEFR that may be
 * unknown. A missing CEFR is never guessed: it is bucketed as [UNKNOWN_CEFR] so
 * it is visible and can be curated later.
 */
const val UNKNOWN_CEFR = "unknown"

/** The pronunciation skill. The engine only measures pronunciation today. */
const val SKILL_PRONUNCIATION = "pronunciation"

/** The aggregate the learner sends. No audio, no free text, no personal data. */
data class LearnerProfile(
    val userId: UUID,
    val language: String,
    val cefr: String? = null,
    val vocabularyScore: Int? = null,
    val grammarScore: Int? = null,
    val comprehensionScore: Int? = null,
    val pronunciationScore: Int? = null,
    val fluencyScore: Int? = null,
    val speakingScore: Int? = null,
    val sampleCountsJson: String = "{}",
)

/** One sound's aggregate state for a learner. */
data class PhonemeProfile(
    val userId: UUID,
    val language: String,
    val phoneme: String,
    val score: Int = 0,
    val confidence: Float = 0f,
    val errorPattern: String? = null,
    val replacementPhoneme: String? = null,
    val position: String? = null,
    val attempts: Int = 0,
    val successfulAttempts: Int = 0,
    val lastSeen: Instant? = null,
    val trend: String? = null,
)

/**
 * A learner's need, derived from the profile. It carries the user_id because it
 * is stored per learner; the moment it is aggregated it loses it and becomes a
 * [NeedClass].
 */
data class UserNeed(
    val userId: UUID,
    val language: String,
    val cefr: String? = null,
    val skill: String,
    val phoneme: String? = null,
    val errorPattern: String? = null,
    val replacementPhoneme: String? = null,
    val position: String? = null,
    val priority: Float = 0f,
) {
    fun needClassKey(): NeedClassKey = NeedClassKey(
        language = language,
        cefr = cefr ?: UNKNOWN_CEFR,
        skill = skill,
        phoneme = phoneme,
        errorPattern = errorPattern,
        replacementPhoneme = replacementPhoneme,
        position = position,
    )
}

/** The anonymous identity of a Need Class: no user_id, ever. */
data class NeedClassKey(
    val language: String,
    val cefr: String,
    val skill: String,
    val phoneme: String? = null,
    val errorPattern: String? = null,
    val replacementPhoneme: String? = null,
    val position: String? = null,
) {
    /** Compact form for logs and admin output, e.g. `A1/θ/substitution-t/initial`. */
    fun label(): String = listOfNotNull(
        cefr,
        phoneme ?: "-",
        errorPattern ?: "-",
        position ?: "-",
    ).joinToString("/")
}

/** Why a Need Class is (or is not) short of content. */
enum class GapState { BELOW_MIN, BELOW_TARGET, OK, AT_MAX }

/** Pool sizing. Defaults are the brief's 20/50/100, always configuration. */
data class PoolConfig(
    val minimum: Int = 20,
    val target: Int = 50,
    val maximum: Int = 100,
) {
    companion object {
        /** Normalizes an arbitrary configuration into a valid min <= target <= max. */
        fun of(minimum: Int, target: Int, maximum: Int): PoolConfig {
            val min = minimum.coerceAtLeast(1)
            val max = maximum.coerceAtLeast(min)
            val tgt = target.coerceIn(min, max)
            return PoolConfig(min, tgt, max)
        }
    }
}

/** One aggregated class after a Demand Aggregator cycle. */
data class NeedClass(
    val key: NeedClassKey,
    val learnerCount: Int,
    val availableExisting: Int = 0,
    val availableEffective: Int = 0,
    val gap: Int = 0,
    val gapState: GapState = GapState.OK,
    val priority: Double = 0.0,
)

/** Tunables of the Need Engine. Mastery defaults to the scorer's own bar. */
data class NeedConfig(
    val minAttempts: Int = 3,
    val masteryThreshold: Int = ScoringConfig.MASTERY_THRESHOLD,
) {
    companion object {
        val DEFAULT = NeedConfig()
    }
}
