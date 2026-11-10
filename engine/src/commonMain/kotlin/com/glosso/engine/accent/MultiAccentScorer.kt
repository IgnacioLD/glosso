package com.glosso.engine.accent

import com.glosso.engine.g2p.widenReferenceVariants
import com.glosso.engine.model.AccentCandidateModel
import com.glosso.engine.model.AccentMatchModel
import com.glosso.engine.phonetics.MatchStatus
import com.glosso.engine.phonetics.PhoneticComparator
import com.glosso.engine.phonetics.ScoringResult

/** How one accent system scored a take. */
data class AccentCandidate(
    val system: AccentSystem,
    val result: ScoringResult,
    /** The whole-sentence reference of this system that scored best. */
    val reference: String,
)

/** The best whole system for a take, plus how every system scored it. */
data class MultiAccentResult(
    val best: AccentCandidate,
    /** Every system, best first. */
    val candidates: List<AccentCandidate>,
) {
    val result: ScoringResult get() = best.result
    val system: AccentSystem get() = best.system

    fun toModel(target: AccentTarget?): AccentMatchModel = AccentMatchModel(
        system = system.id,
        accent = system.accent,
        label = system.label,
        gender = system.gender,
        score = result.score,
        target = target?.accent,
        calibrated = system.phonology.calibrated,
        candidates = candidates.map { AccentCandidateModel(it.system.id, it.system.accent, it.result.score) },
    )
}

/**
 * The accent the learner wants to master (the "mode"). It picks the content and
 * the goal; in scoring it only breaks an exact tie, it never lowers the score of
 * a take that matches another accent better.
 */
data class AccentTarget(val accent: String)

/**
 * Scores a take against each accent system as a whole and keeps the best system.
 *
 * Coherence is the point: a system's reference is its phonology applied to every
 * word of the sentence (plus, when given, a whole-sentence reference of that
 * accent, see [score]'s `accentReferences`), so a take that is rhotic in one word and
 * non-rhotic in the next, or Irish in one sound and Southern in another, matches
 * no system fully and is not rewarded as if it did. The legacy per-word widening
 * (any dictionary reading per word, plus the whole-sentence British reading) is
 * switched off inside a system; the dictionary's lexical alternates are still
 * accepted, each realized in that system's accent.
 */
object MultiAccentScorer {

    /**
     * @param canonicalIpa the rhotic canonical reference (CMUdict G2P), which each
     *   system's phonology rewrites.
     * @param wordVariants the dictionary's accepted readings per canonical word
     *   (first = canonical), realized per system.
     * @param accentReferences a whole-sentence reference already in one accent,
     *   keyed by system id ("en-US-South-female") or accent ("en-GB"), e.g. a
     *   voice's own clip phonemized, or an espeak en-GB transcription. It is
     *   scored next to the rule-derived reference of that system (both are the
     *   same accent) and reported on a tie. The system id wins over the accent.
     */
    fun score(
        text: String,
        canonicalIpa: String,
        actual: String,
        systems: List<AccentSystem>,
        language: String = "en",
        actualConfidence: List<Float> = emptyList(),
        wordVariants: List<List<String>> = emptyList(),
        accentReferences: Map<String, String> = emptyMap(),
        target: AccentTarget? = null,
    ): MultiAccentResult {
        require(systems.isNotEmpty()) { "no accent systems to score against" }
        val references = accentReferences
            .filterValues { it.isNotBlank() }
            .mapKeys { it.key.lowercase() }
        // Systems that share a phonology and a reference (the two genders of one
        // accent without a voice-specific reference) score identically; score once.
        val cache = HashMap<Pair<String, String?>, AccentCandidate>()
        val candidates = systems.mapIndexed { order, system ->
            val override = references[system.id.lowercase()] ?: references[system.accent.lowercase()]
            val key = system.phonology.accent to override
            val scored = cache.getOrPut(key) {
                scoreSystem(text, canonicalIpa, actual, system, language, actualConfidence, wordVariants, override)
            }
            Ranked(scored.copy(system = system), order)
        }
        val ranked = candidates.sortedWith(ranking(target)).map { it.candidate }
        return MultiAccentResult(best = ranked.first(), candidates = ranked)
    }

    private data class Ranked(val candidate: AccentCandidate, val order: Int)

    private fun ranking(target: AccentTarget?): Comparator<Ranked> =
        compareByDescending<Ranked> { it.candidate.result.score }
            .thenBy { missed(it.candidate.result) }
            .thenByDescending { target != null && it.candidate.system.accent.equals(target.accent, ignoreCase = true) }
            .thenBy { it.order }

    private fun missed(result: ScoringResult): Int = result.alignment.count { it.status == MatchStatus.MISSED }

    private fun scoreSystem(
        text: String,
        canonicalIpa: String,
        actual: String,
        system: AccentSystem,
        language: String,
        actualConfidence: List<Float>,
        wordVariants: List<List<String>>,
        override: String?,
    ): AccentCandidate {
        var best: AccentCandidate? = null
        fun consider(reference: String, variants: List<List<String>>) {
            val result = PhoneticComparator.calculateScoringResult(
                text = text,
                expected = reference,
                actual = actual,
                language = language,
                actualConfidence = actualConfidence,
                expectedWordVariants = variants,
                dialectWidening = false,
            )
            val current = best
            if (current == null || result.score > current.result.score ||
                (result.score == current.result.score && missed(result) < missed(current.result))
            ) {
                best = AccentCandidate(system, result, reference)
            }
        }
        system.phonology.realizers().forEachIndexed { index, realize ->
            val realizedVariants = wordVariants.map { readings -> readings.map(realize) }
            // The accent's own reference first, so it is the one reported on a tie;
            // the dictionary's lexical alternates, realized in the same accent, are
            // accepted after it.
            if (override != null && index == 0) consider(override, widenReferenceVariants(override, realizedVariants))
            consider(system.phonology.realizeSentence(canonicalIpa, realize), realizedVariants)
        }
        return checkNotNull(best)
    }
}

/**
 * The score of one take, as the server and the device both compute it.
 *
 * Off ([multiAccent] false, or a language with no accent system) it is exactly
 * the single-reference score. On, the take is also scored against the accent
 * systems, each with its published whole-sentence reference
 * ([accentReferences], the reading in the convention the acoustic model
 * transcribes in), and the better of the two wins. It can therefore only match
 * or raise the single-reference score: a client that sends no accent reference,
 * or a take that mixes readings the dictionary accepts word by word, is never
 * scored lower than before the flag.
 *
 * The result always speaks the canonical reference's phones. The accent
 * reference is written in the model's convention (British-style symbols for any
 * English accent), and the feedback, the phonetic profile and the drill targets
 * are all keyed by the dictionary's phones: a word the take got right against
 * the accent reference is reported as right in the canonical alignment, instead
 * of switching the learner's sounds to another notation.
 */
object AccentScoring {

    data class Outcome(
        val result: ScoringResult,
        /** The accent systems' verdict when one of them decided the score; null otherwise. */
        val accent: MultiAccentResult?,
    )

    fun score(
        text: String,
        canonicalIpa: String,
        actual: String,
        language: String,
        actualConfidence: List<Float> = emptyList(),
        wordVariants: List<List<String>> = emptyList(),
        multiAccent: Boolean = false,
        accentReferences: Map<String, String> = emptyMap(),
        target: AccentTarget? = null,
    ): Outcome {
        val single = PhoneticComparator.calculateScoringResult(
            text = text,
            expected = canonicalIpa,
            actual = actual,
            language = language,
            actualConfidence = actualConfidence,
            expectedWordVariants = wordVariants,
        )
        val systems = if (multiAccent) AccentCatalog.scoringSystemsFor(language) else emptyList()
        if (systems.isEmpty()) return Outcome(single, null)
        val multi = MultiAccentScorer.score(
            text = text,
            canonicalIpa = canonicalIpa,
            actual = actual,
            systems = systems,
            language = language,
            actualConfidence = actualConfidence,
            wordVariants = wordVariants,
            accentReferences = accentReferences,
            target = target,
        )
        return if (multi.result.score >= single.score) {
            Outcome(lift(single, multi.result), multi)
        } else {
            Outcome(single, null)
        }
    }

    /**
     * [single] with the score of [best], and with every word [best] heard right
     * marked right. Both references carry one IPA chunk per word of the text, so
     * the words line up by index; when they do not (a reference with a different
     * word count), the canonical alignment is kept as it is.
     */
    private fun lift(single: ScoringResult, best: ScoringResult): ScoringResult {
        val aligned = single.words.size == best.words.size
        val right = if (aligned) {
            best.words.filter { word -> word.score >= PERFECT && rightIn(best, word.index) }.map { it.index }.toSet()
        } else {
            emptySet()
        }
        val perfectLevel = best.words.firstOrNull { it.index in right }?.level
        return single.copy(
            score = best.score,
            accuracy = maxOf(single.accuracy, best.accuracy),
            completeness = maxOf(single.completeness, best.completeness),
            // Never worse than the dictionary reference in any field: on a tie in
            // score the accent reference could still read one level lower.
            isMastery = single.isMastery || best.isMastery,
            level = minOf(single.level, best.level),
            alignment = single.alignment.map { match ->
                if (match.wordIndex in right && match.status != MatchStatus.PERFECT) {
                    match.copy(actual = match.expected, status = MatchStatus.PERFECT)
                } else {
                    match
                }
            },
            words = single.words.map { word ->
                if (word.index in right && perfectLevel != null) word.copy(score = PERFECT, level = perfectLevel) else word
            },
        )
    }

    private fun rightIn(result: ScoringResult, word: Int): Boolean =
        result.alignment.none { it.wordIndex == word && it.status != MatchStatus.PERFECT }

    private const val PERFECT = 100
}
