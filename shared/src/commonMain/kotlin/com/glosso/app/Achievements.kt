package com.glosso.app

import com.glosso.app.resources.*
import com.glosso.engine.model.PhonemeObservationModel
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.engine.profile.PhonemeTrends
import com.glosso.engine.profile.SkillDimension
import org.jetbrains.compose.resources.StringResource

/**
 * What the phonetic profile reduces to for the seals: how many sounds are
 * mastered and the biggest measured climb on one sound. Pure, so the criteria
 * are tested without a device.
 */
data class SoundProgress(
    val mastered: Int = 0,
    val bestClimb: Int = 0,
) {
    companion object {
        /** Accuracy at or above which a sound counts as mastered. */
        const val MASTERED = 85

        /** Attempts a sound needs before its accuracy is trusted. */
        const val MIN_ATTEMPTS = 5

        /**
         * The mastery count and the best first-to-latest climb. A sound counts as
         * mastered only with enough attempts, so one lucky take cannot claim it.
         * The climb needs at least two days on record, so a single noisy take
         * cannot pass as improvement.
         */
        fun from(
            profile: PhoneticProfileModel?,
            history: List<PhonemeObservationModel>,
        ): SoundProgress {
            val mastered = profile?.stats.orEmpty()
                .count { it.attempts >= MIN_ATTEMPTS && it.accuracy >= MASTERED }
            val bestClimb = PhonemeTrends.all(history)
                .filter { it.days >= PhonemeTrends.MIN_DAYS }
                .maxOfOrNull { it.delta }
                ?.coerceAtLeast(0)
                ?: 0
            return SoundProgress(mastered = mastered, bestClimb = bestClimb)
        }
    }
}

/** The live facts the seals are judged on, beyond the stored practice record. */
data class AchievementSignals(
    val sounds: SoundProgress = SoundProgress(),
    val phrasesMastered: Int = 0,
    /**
     * Epoch day the signals were read. [UNSTAMPED] until a take or reconcile
     * stamps it, which cannot be a real day, so a streak read against it stays
     * broken instead of passing a stale run as fresh.
     */
    val today: Long = UNSTAMPED,
) {
    companion object {
        /** Placeholder day for signals still waiting to be stamped. */
        const val UNSTAMPED = Long.MIN_VALUE
    }
}

/**
 * One seal: a threshold, and how far the learner is toward it. [value] reads the
 * practice record and the live signals; [target] is the count that opens it, so
 * every seal can show real progress as X/Y instead of a binary locked/unlocked.
 */
data class Achievement(
    val id: String,
    val titleRes: StringResource,
    val detailRes: StringResource,
    val target: Int,
    val value: (ProgressData, AchievementSignals) -> Int,
) {
    /** Progress capped at [target], for the X/Y shown under the seal. */
    fun progress(data: ProgressData, signals: AchievementSignals): Int =
        value(data, signals).coerceIn(0, target)

    fun earned(data: ProgressData, signals: AchievementSignals): Boolean =
        value(data, signals) >= target
}

/** Open now, or recorded as earned before the value dipped below its target. */
fun Achievement.isUnlocked(data: ProgressData, signals: AchievementSignals): Boolean =
    id in data.unlocked || earned(data, signals)

/**
 * The count shown under the seal. An open seal always reads its full [target],
 * never less: a streak that later broke, or a phrase that slipped below mastery,
 * must not paint a lit seal with an empty bar.
 */
fun Achievement.displayProgress(data: ProgressData, signals: AchievementSignals): Int =
    if (isUnlocked(data, signals)) target else progress(data, signals)

/**
 * Every seal the app can award, and the criterion behind each one. All of them
 * read progress the app already measures: answers, finished lessons, honest
 * consecutive days, the per-sound record and the phrases taken to mastery. None
 * is given for opening the app, and none can be reached without the work it
 * names.
 */
object AchievementCatalog {

    /** Competences that have to stand on their own for the well-rounded seal. */
    private val COMPETENCES = listOf(
        SkillDimension.VOCABULARY,
        SkillDimension.GRAMMAR,
        SkillDimension.COMPREHENSION,
    )

    /** Answers one competence needs before its accuracy is trusted. */
    private const val COMPETENCE_MIN_ANSWERS = 20

    /** Accuracy at or above which a competence counts as solid. */
    private const val COMPETENCE_SOLID = 75

    private fun solidCompetences(data: ProgressData): Int =
        COMPETENCES.count { dimension ->
            val tally = data.skills[dimension.name] ?: SkillTally()
            tally.total >= COMPETENCE_MIN_ANSWERS &&
                tally.correct * 100 / tally.total >= COMPETENCE_SOLID
        }

    /**
     * The honest daily streak: the stored run only while the last take was today
     * or yesterday, so a broken run never keeps claiming days it did not hold.
     */
    private fun dailyStreak(data: ProgressData, signals: AchievementSignals): Int =
        DailyStreak.displayed(data.lastTakeDay, data.dailyStreak, signals.today)

    /**
     * The sound and phrase facts a scored take just changed, read from the stores
     * at the moment the take landed. The caller passes the result to
     * [ProgressStore.recordDrill], which stamps the day on it.
     */
    fun takeSignals(
        profile: PhoneticProfileModel?,
        history: List<PhonemeObservationModel>,
    ): AchievementSignals = AchievementSignals(
        sounds = SoundProgress.from(profile, history),
        phrasesMastered = AttemptHistory.masteredCount(),
    )

    val all: List<Achievement> = listOf(
        // The first step, then the volume that follows it.
        Achievement("first", Res.string.achievement_first_title, Res.string.achievement_first_detail, 1) { d, _ -> d.answered },
        Achievement("answered50", Res.string.achievement_answered50_title, Res.string.achievement_answered50_detail, 50) { d, _ -> d.answered },
        Achievement(
            "answered200", Res.string.achievement_answered200_title,
            Res.string.achievement_answered200_detail, 200,
        ) { d, _ -> d.answered },
        Achievement(
            "answered500", Res.string.achievement_answered500_title,
            Res.string.achievement_answered500_detail, 500,
        ) { d, _ -> d.answered },
        // Correct answers in a row: the short, in-session streak.
        Achievement("streak5", Res.string.achievement_streak5_title, Res.string.achievement_streak5_detail, 5) { d, _ -> d.bestStreak },
        Achievement("streak10", Res.string.achievement_streak10_title, Res.string.achievement_streak10_detail, 10) { d, _ -> d.bestStreak },
        Achievement("streak20", Res.string.achievement_streak20_title, Res.string.achievement_streak20_detail, 20) { d, _ -> d.bestStreak },
        // Total days practised, then the honest run of consecutive days.
        Achievement("days3", Res.string.achievement_days3_title, Res.string.achievement_days3_detail, 3) { d, _ -> d.activeDays },
        Achievement("days7", Res.string.achievement_days7_title, Res.string.achievement_days7_detail, 7) { d, _ -> d.activeDays },
        Achievement("daily3", Res.string.achievement_daily3_title, Res.string.achievement_daily3_detail, 3) { d, s -> dailyStreak(d, s) },
        Achievement("daily7", Res.string.achievement_daily7_title, Res.string.achievement_daily7_detail, 7) { d, s -> dailyStreak(d, s) },
        Achievement("daily30", Res.string.achievement_daily30_title, Res.string.achievement_daily30_detail, 30) { d, s -> dailyStreak(d, s) },
        // Lessons finished, and pronunciation attempts taken.
        Achievement("lesson1", Res.string.achievement_lesson1_title, Res.string.achievement_lesson1_detail, 1) { d, _ -> d.lessons },
        Achievement("lesson10", Res.string.achievement_lesson10_title, Res.string.achievement_lesson10_detail, 10) { d, _ -> d.lessons },
        Achievement("lesson25", Res.string.achievement_lesson25_title, Res.string.achievement_lesson25_detail, 25) { d, _ -> d.lessons },
        Achievement("drills50", Res.string.achievement_drills50_title, Res.string.achievement_drills50_detail, 50) { d, _ -> d.drills },
        // A perfect drill, and the sounds mastered behind the profile.
        Achievement("perfect", Res.string.achievement_perfect_title, Res.string.achievement_perfect_detail, 100) { d, _ -> d.bestDrill },
        Achievement("sound3", Res.string.achievement_sound3_title, Res.string.achievement_sound3_detail, 3) { _, s -> s.sounds.mastered },
        Achievement("sound10", Res.string.achievement_sound10_title, Res.string.achievement_sound10_detail, 10) { _, s -> s.sounds.mastered },
        // Real improvement: the climb on one sound over at least two days.
        Achievement("climb20", Res.string.achievement_climb20_title, Res.string.achievement_climb20_detail, 20) { _, s -> s.sounds.bestClimb },
        Achievement("climb40", Res.string.achievement_climb40_title, Res.string.achievement_climb40_detail, 40) { _, s -> s.sounds.bestClimb },
        // Phrases taken to mastery, and the written competences held together.
        Achievement(
            "phrases15", Res.string.achievement_phrases15_title,
            Res.string.achievement_phrases15_detail, 15,
        ) { _, s -> s.phrasesMastered },
        Achievement(
            "competences3", Res.string.achievement_competences3_title,
            Res.string.achievement_competences3_detail, 3,
        ) { d, _ -> solidCompetences(d) },
    )
}
