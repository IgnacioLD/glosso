package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import com.glosso.app.platform.todayEpochDay
import com.glosso.app.resources.*
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.StringResource

/** Right/wrong answers for one competence, the raw signal behind its score. */
@Serializable
data class SkillTally(val correct: Int = 0, val total: Int = 0)

/** Everything the Progress screen shows and the achievements are judged on. */
@Serializable
data class ProgressData(
    val answered: Int = 0,
    val correct: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val drills: Int = 0,
    val bestDrill: Int = 0,
    val drillSum: Int = 0,
    /** Consecutive days with at least one scored take, the honest streak. */
    val dailyStreak: Int = 0,
    /** Epoch day of the last scored take, to roll [dailyStreak] over. */
    val lastTakeDay: Long = 0,
    /** Ids of the lessons finished, so the list can mark them done. */
    val finishedLessons: List<String> = emptyList(),
    /** Answers per day, keyed by epoch day, for the chart and the calendar. */
    val days: Map<String, Int> = emptyMap(),
    val unlocked: List<String> = emptyList(),
    /** Answers per competence (SkillDimension name), for the multidimensional profile. */
    val skills: Map<String, SkillTally> = emptyMap(),
) {
    val lessons: Int get() = finishedLessons.size
    val accuracy: Int get() = if (answered == 0) 0 else (correct * 100) / answered
    val drillAverage: Int get() = if (drills == 0) 0 else drillSum / drills
    val activeDays: Int get() = days.size
}

/** What kind of moment is being celebrated, so the overlay can style it. */
enum class CelebrationKind { ACHIEVEMENT, MASTERY }

/** A short, honest reward: an achievement or a sound that just landed. */
data class Celebration(
    val titleRes: StringResource,
    val detailRes: StringResource,
    val kind: CelebrationKind,
)

/** A quiet record of effort, kept on the device. No XP, no coins. */
object ProgressStore {

    private const val FILE = "glosso_stats"
    private const val KEY = "progress"

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: KeyValueStore

    private val _state = MutableStateFlow(ProgressData())
    val state: StateFlow<ProgressData> = _state.asStateFlow()

    /** The most recent moment to celebrate; consumed by the overlay. */
    private val _celebration = MutableStateFlow<Celebration?>(null)
    val celebration: StateFlow<Celebration?> = _celebration.asStateFlow()

    /** A drill at or above this is a mastered sound, worth a quiet celebration. */
    private const val MASTERY = SoundProgress.MASTERED

    /**
     * The live signals behind the sound and phrase seals. They change only on a
     * scored take, so they are refreshed by [recordDrill] and left alone by the
     * exercise paths, which cannot move them.
     */
    @Volatile
    private var signals = AchievementSignals()

    fun init(context: PlatformContext) {
        if (::prefs.isInitialized) return
        prefs = keyValueStore(context, FILE)
        val saved = prefs.getString(KEY)
            ?.let { runCatching { json.decodeFromString<ProgressData>(it) }.getOrNull() }
        if (saved != null) _state.value = saved
    }

    /**
     * Records one exercise answer. [skill] is the competence it exercised (a
     * `SkillDimension` name), so the profile can report comprehension, vocabulary
     * and grammar alongside pronunciation; unmapped exercise types pass null and
     * are not counted toward any competence.
     */
    fun recordAnswer(correct: Boolean, skill: String? = null) {
        val d = _state.value
        val streak = if (correct) d.streak + 1 else 0
        val skills = if (skill == null) d.skills else {
            val tally = d.skills[skill] ?: SkillTally()
            d.skills + (skill to tally.copy(
                correct = tally.correct + if (correct) 1 else 0,
                total = tally.total + 1,
            ))
        }
        commit(
            touchToday(d).copy(
                answered = d.answered + 1,
                correct = d.correct + if (correct) 1 else 0,
                streak = streak,
                bestStreak = maxOf(d.bestStreak, streak),
                skills = skills,
            ),
        )
    }

    /**
     * Records one scored take. [signals] carries the sound and phrase facts the
     * take just changed, so the seals judged on them are checked against the
     * profile as it stands now rather than the last stored snapshot.
     */
    fun recordDrill(score: Int, signals: AchievementSignals) {
        val d = _state.value
        val today = todayLong()
        this.signals = signals.copy(today = today)
        // The daily streak counts days with a scored take, so it can never claim
        // a run of days the learner did not actually practise.
        val dailyStreak = DailyStreak.next(d.lastTakeDay, d.dailyStreak, today)
        // The first time a sound is mastered, celebrate it. Achievements, if any
        // unlock, take priority inside commit().
        val fallback = if (d.bestDrill < MASTERY && score >= MASTERY) {
            Celebration(
                titleRes = Res.string.celebration_mastery_title,
                detailRes = Res.string.celebration_mastery_detail,
                kind = CelebrationKind.MASTERY,
            )
        } else {
            null
        }
        commit(
            touchToday(d).copy(
                drills = d.drills + 1,
                bestDrill = maxOf(d.bestDrill, score),
                drillSum = d.drillSum + score,
                dailyStreak = dailyStreak,
                lastTakeDay = today,
            ),
            fallback = fallback,
        )
        if (dailyStreak > d.dailyStreak) Analytics.track("streak_extended", mapOf("days" to dailyStreak))
    }

    /** Bumps today's tally in the activity log, pruning anything past 400 days. */
    private fun touchToday(d: ProgressData): ProgressData {
        val today = todayLong()
        val days = d.days.toMutableMap()
        days[today.toString()] = (days[today.toString()] ?: 0) + 1
        return d.copy(
            days = days.filterKeys { key -> key.toLongOrNull()?.let { it > today - 400 } ?: true },
        )
    }

    fun recordLesson(id: String) {
        val d = _state.value
        if (id in d.finishedLessons) return
        commit(d.copy(finishedLessons = d.finishedLessons + id))
    }

    /**
     * Persists any seal the live [signals] already earn, with no celebration. It
     * runs when the progress screen appears, so a seal reached before this build
     * (or while the app was closed) is recorded instead of only showing a full
     * bar next to an open one.
     */
    fun reconcile(signals: AchievementSignals) {
        this.signals = signals.copy(today = todayLong())
        val live = this.signals
        val data = _state.value
        val newly = AchievementCatalog.all.filter { it.id !in data.unlocked && it.earned(data, live) }
        if (newly.isEmpty()) return
        val next = data.copy(unlocked = data.unlocked + newly.map { it.id })
        _state.value = next
        prefs.edit { putString(KEY, json.encodeToString(ProgressData.serializer(), next)) }
    }

    fun consumeCelebration() {
        _celebration.value = null
    }

    /**
     * Debug-only fixture: competence tallies so the multidimensional profile has
     * real-shaped data to render. Reachable only from `MainActivity` under
     * `BuildConfig.DEBUG`; see `DemoProfile`.
     */
    fun seedDemo() {
        commit(
            _state.value.copy(
                skills = mapOf(
                    "VOCABULARY" to SkillTally(correct = 26, total = 30),
                    "GRAMMAR" to SkillTally(correct = 18, total = 30),
                    "COMPREHENSION" to SkillTally(correct = 21, total = 30),
                ),
            ),
        )
    }

    private fun commit(next: ProgressData, fallback: Celebration? = null) {
        val live = signals.copy(today = todayLong())
        val newly = AchievementCatalog.all.filter { it.id !in next.unlocked && it.earned(next, live) }
        val withUnlocked = if (newly.isEmpty()) next else next.copy(unlocked = next.unlocked + newly.map { it.id })
        _state.value = withUnlocked
        prefs.edit { putString(KEY, json.encodeToString(ProgressData.serializer(), withUnlocked)) }
        val celebration = when {
            newly.isNotEmpty() -> Celebration(
                titleRes = newly.first().titleRes,
                detailRes = newly.first().detailRes,
                kind = CelebrationKind.ACHIEVEMENT,
            )
            fallback != null -> fallback
            else -> null
        }
        if (celebration != null) _celebration.value = celebration
    }

    private fun todayLong(): Long = todayEpochDay()
}
