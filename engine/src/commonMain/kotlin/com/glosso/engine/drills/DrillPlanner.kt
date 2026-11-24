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
     * Builds a session of [limit] drills from the (filtered) bank in two shares:
     * - about [WEAK_SHARE] of it for the sounds the learner misses, the worst
     *   sound getting the most drills (up to [MAX_PER_SOUND]);
     * - the rest for sounds not practised enough to judge, most important first
     *   (importance = how much of the course trains the sound), one drill each.
     * The two shares alternate so a session never drills one sound in a row for
     * long. Whatever one share cannot fill goes to the other, and a sound the
     * learner already gets right only fills what is left.
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
        fun stat(sound: String) = profile.stats.firstOrNull { samePhone(it.phoneme, sound) }
        fun judged(sound: String) = (stat(sound)?.attempts ?: 0) >= PhoneticProfile.MIN_ATTEMPTS
        val importance = sounds.associateWith { s -> bank.drills.count { samePhone(it.target, s) } }

        // Missed sounds, weighted by how often they are missed.
        val weak = sounds.filter { judged(it) && stat(it)!!.accuracy < 100 }
            .associateWith { (100 - stat(it)!!.accuracy).toDouble() }
        // Sounds with too few attempts to judge: the course's key sounds first.
        val untried = sounds.filter { !judged(it) }
            .sortedWith(compareByDescending<String> { importance.getValue(it) }.thenBy { stat(it)?.attempts ?: 0 }.thenBy { it })
        val right = sounds.filter { judged(it) && stat(it)!!.accuracy == 100 }.sortedBy { it }

        val chosen = mutableListOf<DrillModel>()
        val usedThemes = mutableSetOf<String>()
        val given = mutableMapOf<String, Int>()
        fun take(sound: String): Boolean {
            val pick = pickFor(stat(sound) ?: PhonemeStatModel(sound), pool, chosen.map { it.id }.toSet(), usedThemes)
                ?: return false
            chosen += pick
            usedThemes += pick.theme
            given[sound] = (given[sound] ?: 0) + 1
            return true
        }
        val dry = mutableSetOf<String>()
        // The weak sound that most deserves the next drill: its weight divided by
        // the drills it already has, so the worst sound leads without taking all.
        fun nextWeak(cap: Int): String? = weak.keys
            .filter { it !in dry && (given[it] ?: 0) < cap }
            .maxWithOrNull(compareBy<String> { weak.getValue(it) / (1 + (given[it] ?: 0)) }.thenByDescending { it })
        fun nextUntried(): String? = untried.firstOrNull { it !in dry && (given[it] ?: 0) == 0 }

        val weakSlots = if (weak.isEmpty()) 0 else (limit * WEAK_SHARE + 0.999).toInt().coerceIn(1, limit)
        var weakGiven = 0
        while (chosen.size < limit) {
            // Keeps the weak share spread through the session instead of up front.
            val wantWeak = weakGiven < weakSlots && weakGiven * limit <= chosen.size * weakSlots
            val sound = (if (wantWeak) nextWeak(MAX_PER_SOUND) else nextUntried())
                ?: nextUntried().takeIf { wantWeak }
                ?: nextWeak(MAX_PER_SOUND)
                ?: nextWeak(Int.MAX_VALUE)
                ?: (untried + right).firstOrNull { it !in dry }
                ?: break
            if (!take(sound)) {
                dry += sound
                continue
            }
            if (sound in weak) weakGiven++
        }

        val available = suggestedThemes(profile, bank, knownThemes)
        return DrillPlanModel(drills = chosen, themes = available)
    }

    private const val WEAK_SHARE = 0.6
    private const val MAX_PER_SOUND = 3

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
        excludedIds: Set<String>,
        usedThemes: Set<String>,
    ): DrillModel? {
        val typical = stat.typicalError?.takeIf { it != PhoneticProfile.DROPPED }
        return drills.asSequence()
            .filter { samePhone(it.target, stat.phoneme) }
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
