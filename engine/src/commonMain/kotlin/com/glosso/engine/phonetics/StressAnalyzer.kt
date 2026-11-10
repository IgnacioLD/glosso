package com.glosso.engine.phonetics

import com.glosso.engine.model.PhoneTimingModel
import com.glosso.engine.model.StressFindingModel
import com.glosso.engine.model.StressIssueModel
import com.glosso.engine.model.StressReportModel

/**
 * Scores word stress from the forced-alignment timings: a stressed vowel should
 * be longer and louder than the unstressed ones around it. If it is not, the
 * learner said the right sounds with the wrong rhythm, which is exactly the
 * mistake phoneme scoring cannot see.
 *
 * Stress only exists when the reference carries a primary-stress mark (CMUdict
 * does), so this returns null rather than guessing.
 */
object StressAnalyzer {

    /** Below this many vowels there is nothing stable to compare against. */
    const val MIN_VOWELS = 3

    /**
     * A stressed vowel must beat this fraction of the unstressed median. Length
     * is the reliable cue; energy falls off toward the end of any sentence
     * (declination), so the energy bar is deliberately low to avoid nagging a
     * perfectly good final word. Pitch accent is the next cue to add.
     */
    private const val LENGTH_RATIO = 0.9
    private const val ENERGY_RATIO = 0.5

    fun analyze(
        timings: List<PhoneTimingModel>,
        referenceWords: List<List<PhoneticComparator.ReferencePhone>>,
    ): StressReportModel? {
        val reference = referenceWords.flatten()
        if (reference.isEmpty() || reference.size != timings.size) return null

        val wordOfPhone = IntArray(reference.size)
        run {
            var index = 0
            for (word in referenceWords.indices) {
                repeat(referenceWords[word].size) { wordOfPhone[index++] = word }
            }
        }

        data class Vowel(
            val phone: String,
            val stressed: Boolean,
            val wordIndex: Int,
            val durationMs: Int,
            val energy: Float,
        )

        val vowels = reference.indices
            .filter { isVowel(reference[it].phone) }
            .map { i ->
                Vowel(
                    phone = reference[i].phone,
                    stressed = reference[i].stressed,
                    wordIndex = wordOfPhone[i],
                    durationMs = timings[i].durationMs,
                    energy = timings[i].energy,
                )
            }
        if (vowels.size < MIN_VOWELS) return null

        val unstressed = vowels.filter { !it.stressed }
        val stressed = vowels.filter { it.stressed }
        if (unstressed.isEmpty() || stressed.isEmpty()) return null

        val medianUnstressedDuration = medianInt(unstressed.map { it.durationMs })
        val medianUnstressedEnergy = medianFloat(unstressed.map { it.energy })

        val findings = stressed.mapNotNull { vowel ->
            when {
                vowel.durationMs < medianUnstressedDuration * LENGTH_RATIO -> StressFindingModel(
                    issue = StressIssueModel.NOT_LONG_ENOUGH,
                    phoneme = vowel.phone,
                    wordIndex = vowel.wordIndex,
                    title = "The stressed vowel /${vowel.phone}/ was not long enough to stand out.",
                )
                vowel.energy < medianUnstressedEnergy * ENERGY_RATIO -> StressFindingModel(
                    issue = StressIssueModel.TOO_WEAK,
                    phoneme = vowel.phone,
                    wordIndex = vowel.wordIndex,
                    title = "The stressed vowel /${vowel.phone}/ was too quiet to stand out.",
                )
                else -> null
            }
        }

        return StressReportModel(
            findings = findings,
            medianVowelMs = medianInt(vowels.map { it.durationMs }),
        )
    }

    private fun isVowel(phone: String): Boolean = PhoneticInventory.isVowel(phone)

    private fun medianInt(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun medianFloat(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
