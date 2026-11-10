package com.glosso.engine.accent

import com.glosso.engine.model.VoiceGender
import com.glosso.engine.phonetics.PhoneticComparator

/**
 * One realization rule of an accent: every match of [pattern] in a reference
 * word becomes [replacement]. Rules are applied to every word of the sentence,
 * never to a chosen few, which is what keeps an accent coherent.
 */
data class AccentRule(
    val id: String,
    val description: String,
    val pattern: Regex,
    val replacement: String,
) {
    fun apply(word: String): String = pattern.replace(word, Regex.escapeReplacement(replacement))
}

/**
 * The coherent set of phoneme realizations of one accent, as a rewrite of the
 * canonical reference (CMUdict, General American, rhotic).
 *
 * [rhotic] = false folds every coda /ɹ/ of the sentence away (the same rule the
 * legacy scorer uses for British English). [rules] run first, on the rhotic
 * canonical form, in order.
 *
 * [calibrated] stays false until the accent's own native audio has been measured
 * against it (docs/MULTI-ACCENT.md). An uncalibrated phonology is a seed from
 * standard descriptions of the accent, not a measured fact.
 */
data class AccentPhonology(
    val accent: String,
    val rhotic: Boolean,
    val rules: List<AccentRule> = emptyList(),
    val calibrated: Boolean = false,
) {
    /**
     * The word rewrites this accent allows. A rhotic accent has one; a non-rhotic
     * one has one per NEAR onset (ɪə / iə), which is free variation inside the
     * accent, not a second accent. Each rewrite is applied to the whole sentence.
     */
    fun realizers(): List<(String) -> String> {
        val withRules: (String) -> String = { word -> rules.fold(word) { acc, rule -> rule.apply(acc) } }
        if (rhotic) return listOf(withRules)
        return PhoneticComparator.nonRhoticNearOnsets.map { onset ->
            { word: String -> PhoneticComparator.nonRhoticWord(withRules(word), onset) }
        }
    }

    /**
     * [canonicalIpa] with [realize] applied to every word: one whole-sentence
     * reference. The single place the word split/join lives, so scoring and the
     * metric cannot drift apart.
     */
    fun realizeSentence(canonicalIpa: String, realize: (String) -> String): String =
        canonicalIpa.trim().split(WHITESPACE).filter { it.isNotBlank() }.joinToString(" ") { realize(it) }

    /** Every whole-sentence reading of [canonicalIpa] in this accent. */
    fun realize(canonicalIpa: String): List<String> = realizers().map { realizeSentence(canonicalIpa, it) }.distinct()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/** A reference voice from the owner's voice provider (UUID -> accent/gender). */
data class ReferenceVoice(
    val voiceId: String,
    val accent: String,
    val gender: VoiceGender,
    val label: String,
)

/**
 * An accent system: an accent, a gender, its coherent phonology and the voices
 * whose audio is its reference. A take is scored against a system as a whole.
 *
 * [id] is "<accent>-<gender>" ("en-US-South-female"), the same voice-key
 * convention the content audio maps already use ("en-GB-female").
 */
data class AccentSystem(
    val accent: String,
    val label: String,
    val gender: VoiceGender,
    val phonology: AccentPhonology,
    val voices: List<ReferenceVoice>,
) {
    val id: String get() = systemId(accent, gender)

    companion object {
        fun systemId(accent: String, gender: VoiceGender): String = "$accent-${gender.name.lowercase()}"
    }
}
