package com.glosso.app

import com.glosso.engine.model.PhonemeObservationModel
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.engine.profile.PhonemeMemory

/**
 * Debug fixture: a documented, multi-day phonetic history and the aggregate it
 * implies. Used by `ProfileStore.seedDemo` so the trend and "your practice
 * today" views can be inspected on a device without weeks of real recordings.
 *
 * Not shipped behaviour: only `MainActivity` under `BuildConfig.DEBUG` reaches it.
 * The numbers are chosen to exercise each state:
 *
 * - `/θ/` 43, 51, 63, 71 -> the weakest sound, and improving.
 * - `/ð/` 58, 62, 59, 61, 60 -> the same error again and again, a persistent pattern.
 * - `/ɪ/` 64, 68, 66 -> weak and flat.
 * - `/ɹ/` 82, 85 -> solid.
 * - `/æ/` 95, 92 -> nearly mastered.
 */
object DemoProfile {

    fun history(today: Long): List<PhonemeObservationModel> = buildList {
        series("θ", "s", today, (13L to 43), (11L to 51), (7L to 63), (0L to 71))
        series("ð", "d", today, (12L to 58), (10L to 62), (8L to 59), (5L to 61), (0L to 60))
        series("ɪ", "i", today, (9L to 64), (6L to 68), (0L to 66))
        series("ɹ", "w", today, (4L to 82), (0L to 85))
        series("æ", "ɛ", today, (3L to 95), (0L to 92))
    }

    fun aggregate(history: List<PhonemeObservationModel>): PhoneticProfileModel =
        PhoneticProfileModel(
            stats = history.groupBy { it.phoneme }
                .map { (phoneme, obs) ->
                    val errors = LinkedHashMap<String, Int>()
                    for (o in obs) {
                        val error = o.error ?: continue
                        errors[error] = (errors[error] ?: 0) + (o.total - o.correct)
                    }
                    PhonemeStatModel(
                        phoneme = phoneme,
                        attempts = obs.sumOf { it.total },
                        correct = obs.sumOf { it.correct },
                        errors = errors,
                    )
                }
                .sortedWith(compareBy({ it.accuracy }, { it.phoneme })),
        )

    private fun MutableList<PhonemeObservationModel>.series(
        phoneme: String,
        error: String,
        today: Long,
        vararg points: Pair<Long, Int>,
    ) {
        for ((ageDays, accuracy) in points) {
            val landed = accuracy >= 100
            val errorCode = if (landed) null else error
            add(
                PhonemeObservationModel(
                    phoneme = phoneme,
                    day = today - ageDays,
                    total = 100,
                    correct = accuracy,
                    error = errorCode,
                    pattern = PhonemeMemory.patternFor(phoneme, errorCode),
                ),
            )
        }
    }
}
