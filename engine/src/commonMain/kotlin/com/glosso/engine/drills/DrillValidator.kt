package com.glosso.engine.drills

import com.glosso.engine.g2p.G2P
import com.glosso.engine.model.DrillModel
import com.glosso.engine.phonetics.PhoneticComparator

/**
 * The quality gate for the drill bank. Runs when a bank is published (so no bad
 * item ever ships) and again when it is loaded (so a corrupted or hand-edited
 * bank is caught before it reaches a learner).
 *
 * The checks that matter are deterministic and use the same G2P and phone parsing
 * as scoring: a drill must actually contain its target sound, and its [focus]
 * words must be a true minimal pair for the claimed contrast. That is what keeps
 * generated or hand-written content honest instead of plausible-looking slop.
 */
object DrillValidator {

    const val MAX_WORDS = 16
    const val MAX_CHARS = 120
    const val MIN_TARGET_HITS = 2

    data class Problem(val code: String, val detail: String)

    data class Result(
        val problems: List<Problem>,
        /** How many times the target sound occurs in the sentence. */
        val targetHits: Int,
    ) {
        val ok: Boolean get() = problems.isEmpty()
    }

    /**
     * Common English profanity. Slurs are kept in a separate, maintained list.
     * Only applied to English drills: a word that is banned in English can be
     * everyday vocabulary elsewhere (German "dick" means thick).
     */
    private val BANNED_WORDS: Set<String> = setOf(
        "ass", "asshole", "bastard", "bitch", "bollocks", "bugger", "bullshit",
        "crap", "cunt", "damn", "dick", "fuck", "fucked", "fucking", "piss",
        "prick", "shit", "slut", "twat", "wanker", "whore",
    )

    fun validate(drill: DrillModel, g2p: G2P): Result {
        val problems = mutableListOf<Problem>()
        fun fail(code: String, detail: String) = problems.add(Problem(code, detail))

        if (drill.id.isBlank()) fail("id", "id is blank")
        if (drill.language.isBlank()) fail("language", "language is blank")
        if (drill.level.isBlank()) fail("level", "level is blank")
        if (drill.theme.isBlank()) fail("theme", "theme is blank")
        if (drill.target.isBlank()) fail("target", "target is blank")

        val text = drill.text.trim()
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) {
            fail("text", "text is empty")
        } else {
            if (words.size > MAX_WORDS) fail("text.words", "${words.size} words > $MAX_WORDS")
            if (text.length > MAX_CHARS) fail("text.chars", "${text.length} chars > $MAX_CHARS")
            if (text.last() !in ".!?") fail("text.punct", "sentence must end with . ! or ?")
        }

        val normalized = words.map { normalizeWord(it) }
        if (drill.language.lowercase().substringBefore('-').substringBefore('_') == "en") {
            for (word in normalized) {
                if (word in BANNED_WORDS) fail("text.banned", "banned word: $word")
            }
        }

        val targetHits = if (text.isNotEmpty()) {
            PhoneticComparator.contentPhones(g2p.phonemize(text), drill.language).count { samePhone(it, drill.target) }
        } else {
            0
        }
        if (targetHits < MIN_TARGET_HITS) {
            fail("target.hits", "target /${drill.target}/ appears $targetHits time(s), needs $MIN_TARGET_HITS")
        }

        if (drill.focus.isNotEmpty()) {
            // The sentence must practise at least one word of the pair; the other
            // is shown next to it for listening, not forced into the sentence.
            if (drill.focus.none { normalizeWord(it) in normalized }) {
                fail("focus.absent", "no focus word of ${drill.focus} is in the sentence")
            }
            if (drill.contrast != null && drill.focus.size >= 2) {
                validatePair(drill, g2p)?.let { fail("focus.pair", it) }
            }
        }

        return Result(problems, targetHits)
    }

    /** Returns a problem description when the focus words are not a true pair. */
    private fun validatePair(drill: DrillModel, g2p: G2P): String? {
        val first = phonesOfWord(drill.focus[0], g2p, drill.language)
            ?: return "focus word '${drill.focus[0]}' is not in the dictionary"
        val second = phonesOfWord(drill.focus[1], g2p, drill.language)
            ?: return "focus word '${drill.focus[1]}' is not in the dictionary"
        if (first.size != second.size) {
            return "'${drill.focus[0]}' and '${drill.focus[1]}' are not a minimal pair (different length)"
        }
        val diffs = first.indices.filter { !samePhone(first[it], second[it]) }
        // A hard and a soft consonant never differ alone: the vowel after them
        // changes with them (мы /my/ vs ми /mʲɪ/, мод /mot/ vs мёд /mʲɵt/). That is
        // one contrast for the learner, and the drill may name either half of it.
        val softPair = diffs.size == 2 && diffs[1] == diffs[0] + 1 &&
            softnessOnly(first[diffs[0]], second[diffs[0]])
        if (diffs.size != 1 && !softPair) {
            return "'${drill.focus[0]}' and '${drill.focus[1]}' differ in ${diffs.size} sounds, not 1"
        }
        val ok = diffs.any { samePhone(first[it], drill.target) && samePhone(second[it], drill.contrast!!) }
        val i = diffs.first()
        return if (ok) null else "pair contrasts /${first[i]}/ vs /${second[i]}/, not /${drill.target}/ vs /${drill.contrast}/"
    }

    private const val SOFT = '\u02b2'

    /** True when the two phones are the same consonant, one hard and one soft. */
    private fun softnessOnly(a: String, b: String): Boolean =
        a != b && a.trimEnd(SOFT) == b.trimEnd(SOFT)
}

/** Phones of one word, or null when the word is not in the dictionary. */
internal fun phonesOfWord(word: String, g2p: G2P, language: String? = null): List<String>? {
    val clean = word.lowercase().trim { !it.isLetter() && it != '\'' }
    if (clean.isEmpty()) return null
    val ipa = g2p.phonemize(clean)
    if (ipa.trim().lowercase() == clean) return null
    return PhoneticComparator.contentPhones(ipa, language)
}

/** Length-insensitive phone equality; normalizes the two glyphs used for /g/. */
internal fun samePhone(a: String, b: String): Boolean {
    val x = a.replace('g', 'ɡ')
    val y = b.replace('g', 'ɡ')
    return x == y || x.removeSuffix("ː") == y.removeSuffix("ː")
}

internal fun normalizeWord(word: String): String =
    word.lowercase().trim { !it.isLetter() && it != '\'' }
