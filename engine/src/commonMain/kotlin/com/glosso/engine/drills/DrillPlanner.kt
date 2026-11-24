package com.glosso.engine.drills

import com.glosso.engine.model.DrillBank
import com.glosso.engine.model.DrillModel
import com.glosso.engine.model.DrillPlanModel
import com.glosso.engine.model.PhonemeErrorModel
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.engine.model.ThemeModel
import com.glosso.engine.phonetics.PhoneticProfile

/**
 * Picks practice from the reviewed bank for one learner. Selection is driven by
 * the phonetic profile: the weakest sound first, then the specific confusion the
 * learner actually makes, then a theme they have not just practised, so the
 * session varies without ever inventing content.
 */
object DrillPlanner {

    const val DEFAULT_LIMIT = 3

    /**
     * Shares [limit] drills out across the sounds the (filtered) bank trains, in
     * proportion to each sound's priority:
     * - need: how often the learner misses it (an untried sound counts as half);
     * - importance: how much of the course trains it, so key sounds get covered;
     * - practice gap: sounds practised little are brought back.
     * Slots go one at a time to the sound with the highest priority divided by
     * the drills it already has, so the weakest sound gets the most practice while
     * the important and neglected ones still come up. A sound mastered over many
     * attempts only fills what is left.
     *
     * @param themes only pick drills in these themes (empty = any).
     * @param targets only pick drills training these sounds (empty = any); a
     *   lesson sets this to its own sounds so practice stays on-topic.
     * @param level optional CEFR filter ("A1"...).
     * @param seenIds drills already shown recently, so practice does not repeat.
     * @param knownThemes labels from the content manifest, to annotate the picker.
     */
    fun plan(
        profile: PhoneticProfileModel,
        bank: DrillBank,
        limit: Int = DEFAULT_LIMIT,
        themes: Set<String> = emptySet(),
        targets: Set<String> = emptySet(),
        level: String? = null,
        seenIds: Set<String> = emptySet(),
        knownThemes: List<ThemeModel> = emptyList(),
    ): DrillPlanModel {
        val pool = bank.drills.filter { d ->
            (themes.isEmpty() || d.theme in themes) &&
                (targets.isEmpty() || targets.any { samePhone(it, d.target) }) &&
                (level == null || d.level == level) &&
                d.id !in seenIds
        }
        val sounds = mutableListOf<String>()
        pool.forEach { d -> if (sounds.none { samePhone(it, d.target) }) sounds += d.target }
        val coverage = sounds.associateWith { s -> bank.drills.count { samePhone(it.target, s) } }
        val maxCoverage = coverage.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val priority = sounds.associateWith { s ->
            soundPriority(profile.stats.firstOrNull { samePhone(it.phoneme, s) }, coverage.getValue(s).toDouble() / maxCoverage)
        }

        val chosen = mutableListOf<DrillModel>()
        val usedIds = mutableSetOf<String>()
        val usedThemes = mutableSetOf<String>()
        val given = sounds.associateWith { 0 }.toMutableMap()
        val exhausted = mutableSetOf<String>()
        while (chosen.size < limit) {
            val sound = sounds
                .filter { it !in exhausted }
                .maxWithOrNull(compareBy<String> { priority.getValue(it) / (1 + given.getValue(it)) }.thenByDescending { it })
                ?: break
            val stat = profile.stats.firstOrNull { samePhone(it.phoneme, sound) } ?: PhonemeStatModel(sound)
            val pick = pickFor(stat, pool, emptySet(), emptySet(), null, usedIds, usedThemes)
            if (pick == null) {
                exhausted += sound
                continue
            }
            chosen += pick
            usedIds += pick.id
            usedThemes += pick.theme
            given[sound] = given.getValue(sound) + 1
        }

        val available = suggestedThemes(profile, bank, knownThemes)
        return DrillPlanModel(drills = chosen, themes = available)
    }

    /**
     * 0..1, see [plan]. A sound with too few attempts to judge counts as half
     * missed; one mastered over [MASTERED_ATTEMPTS] tries keeps only a token
     * weight so it is revisited after everything else.
     */
    internal fun soundPriority(stat: PhonemeStatModel?, importance: Double): Double {
        val attempts = stat?.attempts ?: 0
        val judged = attempts >= PhoneticProfile.MIN_ATTEMPTS
        val need = if (judged) (100 - stat!!.accuracy) / 100.0 else 0.5
        if (judged && need == 0.0 && attempts >= MASTERED_ATTEMPTS) return 0.01
        val gap = 1.0 / (1 + attempts)
        return 0.6 * need + 0.25 * importance + 0.15 * gap
    }

    private const val MASTERED_ATTEMPTS = 10

    /**
     * The reviewed drill that best trains one diagnosed error: the same sound,
     * and when the rule names the confusion, the same contrast. Null when the
     * bank has none, so the caller can say so instead of offering an unrelated
     * drill. Never invents content: only picks from [drills].
     */
    fun drillForError(error: PhonemeErrorModel, drills: List<DrillModel>): DrillModel? {
        val sameTarget = drills.filter { samePhone(it.target, error.phoneme) }
        if (sameTarget.isEmpty()) return null
        return sameTarget
            .sortedWith(
                compareByDescending<DrillModel> {
                    error.actual != null && it.contrast != null && samePhone(it.contrast, error.actual)
                }
                    .thenByDescending { error.ruleId != null && it.ruleId == error.ruleId }
                    .thenBy { it.id },
            )
            .first()
    }

    private fun pickFor(
        stat: PhonemeStatModel,
        drills: List<DrillModel>,
        themes: Set<String>,
        targets: Set<String>,
        level: String?,
        excludedIds: Set<String>,
        usedThemes: Set<String>,
    ): DrillModel? {
        val typical = stat.typicalError?.takeIf { it != PhoneticProfile.DROPPED }
        return drills.asSequence()
            .filter { samePhone(it.target, stat.phoneme) }
            .filter { themes.isEmpty() || it.theme in themes }
            .filter { targets.isEmpty() || targets.any { t -> samePhone(t, it.target) } }
            .filter { level == null || it.level == level }
            .filter { it.id !in excludedIds }
            .sortedWith(
                compareByDescending<DrillModel> {
                    typical != null && it.contrast != null && samePhone(it.contrast, typical)
                }
                    .thenByDescending { it.theme !in usedThemes }
                    .thenBy { it.id },
            )
            .firstOrNull()
    }

    /**
     * Themes that actually have drills for this learner's weak sounds, weakest
     * sound first. This is what the post-placement screen offers so the learner
     * picks what to practise instead of getting a fixed list.
     */
    fun suggestedThemes(
        profile: PhoneticProfileModel,
        bank: DrillBank,
        knownThemes: List<ThemeModel> = emptyList(),
        limit: Int = 8,
    ): List<ThemeModel> {
        val labels = knownThemes.associateBy { it.id }
        val ordered = LinkedHashSet<String>()
        for (stat in PhoneticProfile.weakest(profile, limit = limit)) {
            bank.drills.asSequence()
                .filter { samePhone(it.target, stat.phoneme) && it.theme.isNotBlank() }
                .sortedBy { it.id }
                .forEach { ordered += it.theme }
            if (ordered.size >= limit) break
        }
        return ordered.take(limit).map { labels[it] ?: ThemeModel(it) }
    }
}
