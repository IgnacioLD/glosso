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
        val weak = PhoneticProfile.weakest(profile, limit = (limit * 2).coerceAtLeast(4))
        val chosen = mutableListOf<DrillModel>()
        val usedIds = mutableSetOf<String>()
        val usedThemes = mutableSetOf<String>()

        for (stat in weak) {
            if (chosen.size >= limit) break
            val pick = pickFor(stat, bank.drills, themes, targets, level, seenIds + usedIds, usedThemes)
                ?: continue
            chosen += pick
            usedIds += pick.id
            usedThemes += pick.theme
        }

        val available = suggestedThemes(profile, bank, knownThemes)
        return DrillPlanModel(drills = chosen, themes = available)
    }

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
