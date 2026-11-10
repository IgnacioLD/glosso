package com.glosso.app

import com.glosso.engine.model.DrillModel

/**
 * Picks the phrases the onboarding seed asks a new learner to say.
 *
 * The seed exists to give the phonetic profile real evidence from the first
 * minute, so the useful thing is *coverage*: one phrase per different trained
 * sound, not a run of near-identical sentences. The server can only page the
 * drill bank in row order, which today hands back the same two sounds four
 * times, so the variety is chosen here, from the reviewed drills and their
 * already-rendered reference audio. Nothing is authored or generated.
 */
object OnboardingSeed {

    /** How many phrases the seed asks for. */
    const val PHRASES = 6

    /**
     * How many drills to fetch so there is something to choose from. It is the
     * most the content endpoint serves in one page; the whole English bank is
     * larger, but one page already spans every trained sound, so a bigger fetch
     * would only cost the learner bytes on the first run.
     */
    const val POOL = 200

    /**
     * One phrase per distinct [DrillModel.target] sound, most-represented sound
     * first, up to [limit]. Fields the seed cannot use (blank target or text) are
     * ignored, and a phrase whose reference audio has shipped is preferred, so
     * the learner can always hear the model before saying it. Among the drills
     * for one sound, an unused theme comes first and then the shortest sentence,
     * which keeps the phrases varied and quick to say. The result is
     * deterministic: the same pool always yields the same phrases.
     */
    fun select(pool: List<DrillModel>, limit: Int = PHRASES): List<DrillModel> {
        if (limit <= 0) return emptyList()
        val usable = pool.filter { it.target.isNotBlank() && it.text.isNotBlank() }
        val ranked = usable
            .groupBy { it.target }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<DrillModel>>> { it.value.size }
                    .thenBy { it.key },
            )
        val chosen = ArrayList<DrillModel>(limit)
        val themes = HashSet<String>()
        for ((_, drills) in ranked) {
            if (chosen.size >= limit) break
            val pick = drills
                .sortedWith(
                    compareBy<DrillModel> { it.audio.isEmpty() }
                        .thenBy { it.theme in themes }
                        .thenBy { it.text.count { c -> c == ' ' } }
                        .thenBy { it.id },
                )
                .first()
            chosen += pick
            themes += pick.theme
        }
        return chosen
    }
}
