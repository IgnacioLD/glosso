package com.glosso.engine.phonetics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import com.glosso.engine.log.EngineLog
import com.glosso.engine.model.MasteryLevel
import com.glosso.engine.model.ScoringConfig

data class ScoringResult(
    val score: Int,
    val normalizedExpected: String,
    val normalizedActual: String,
    val alignment: List<PhonemeMatch> = emptyList(),
    /** Quality of the sounds actually produced, ignoring anything skipped. 0..100 */
    val accuracy: Int = 0,
    /** Percent of the reference sounds the speaker actually produced. 0..100 */
    val completeness: Int = 0,
    /** One entry per reference word, in sentence order. */
    val words: List<WordScore> = emptyList(),
    /** The full mastery decision, not just the score band. */
    val isMastery: Boolean = false,
    /** The named result shown to the learner. */
    val level: MasteryLevel = MasteryLevel.NOT_YET
)

/** Per-word result, so the UI can point at the word that needs work. */
data class WordScore(
    val index: Int,
    val score: Int,
    val phoneCount: Int,
    val level: MasteryLevel = MasteryLevel.NOT_YET
)

data class PhonemeMatch(
    val expected: String,
    val actual: String,
    val status: MatchStatus, // PERFECT, CLOSE, MISSED
    /** Index of the reference word this match belongs to, or -1 when unknown. */
    val wordIndex: Int = -1,
)

data class LetterFeedbackInfo(
    val char: String,
    val status: MatchStatus
)

enum class MatchStatus { PERFECT, CLOSE, MISSED }

/**
 * Scores a spoken phone sequence against a reference.
 *
 * Provenance: original work for Glosso Pro, authored by the project owner with
 * AI assistance. The similarity values and the minimal-pair descriptions are
 * hand-authored heuristics derived from standard, non-copyrightable phonetic
 * facts (voicing, place and manner of articulation). No code was copied from
 * Allosaurus, ai-pronunciation-trainer or any other third-party project; those
 * names appear only in comments that document symbol-set conventions. See
 * docs/LICENSING.md.
 */
object PhoneticComparator {
    
    private const val TAG = "PhoneticComparator"

    /** Similarity at or above this counts as a correctly produced phoneme. */
    private const val PERFECT_THRESHOLD = 0.85

    /** Similarity at or above this earns partial credit; below it is a miss. */
    private const val CLOSE_THRESHOLD = 0.3

    /** Cost of a reference phoneme the speaker never produced. */
    private const val GAP_DELETION = 1.0

    /**
     * Cost of an extra phoneme the recognizer heard that the reference does not
     * contain. Still cheaper than a deletion, because an extra segment is often
     * the acoustic model splitting one phone in two rather than a sound the
     * learner added - but no longer half price, since added vowels (Spanish
     * "eschool" for "school") are real production errors.
     */
    private const val GAP_INSERTION = 0.7

    /** Similarity between a long and short vowel with the same base (i vs iː). */
    private const val LENGTH_SIMILARITY = 0.8

    /**
     * A word-final plosive is routinely unreleased, so the acoustic model often
     * never emits it at all (especially before a pause). Forgiving that omission
     * is not the same as ignoring a deleted sound: word-medial and word-initial
     * plosives keep the full deletion cost, and the final one still costs
     * something.
     */
    private const val FINAL_STOP_DELETION = 0.35

    /**
     * Fraction of an insertion penalty an utterly unsure extra phone still pays.
     *
     * Confidence is deliberately confined to insertions. A substitution or a
     * deletion is a difference in what the learner produced, so it pays in full
     * however unsure the model was: scaling those penalties by confidence let a
     * strong accent that the model found hard to decode (low confidence on every
     * phone) sail through as MASTERED. An unsure *extra* segment, by contrast, is
     * usually one phone the decoder split in two, so it earns a bounded rebate.
     */
    private const val UNSURE_INSERTION_FLOOR = 0.75

    /** Floating point tolerance for comparing accumulated DP costs. */
    private const val EPSILON = 1e-9

    /**
     * Upper bound on the reference-variant combinations the scorer will try.
     *
     * A drill is a short sentence and most of its words have a single reading, so
     * the real product is tiny. It only grows with the number of function words
     * that CMUdict lists several ways ("the" has three readings, "and" two), which
     * is exactly the connected-speech case where the reduced form has to be
     * accepted. The old cap of 64 silently gave up on those sentences and scored
     * the citation form alone, so a native "and" reduced to /ənd/ was charged as a
     * wrong vowel. 4096 covers the whole catalogue with room to spare while
     * keeping the work bounded (a combination is one O(n*m) alignment).
     */
    private const val MAX_VARIANT_COMBINATIONS = 4096L

    /**
     * Whether [MAX_VARIANT_COMBINATIONS] counts only the readings that differ once
     * normalized. Published variants repeat a reading with and without a stress
     * mark ("ˈɪz"/"ɪz"), which the scorer strips, so the raw count overstates the
     * work and a long dialogue turn can trip the cap and lose every variant
     * (measured in docs/ENGINE-IMPROVEMENTS.md). Off by default: turning it on
     * scores those turns with their variants, which can only raise a score, and
     * that changes the deployed number.
     */
    internal const val DISTINCT_VARIANT_CAP_DEFAULT = false

    private val WHITESPACE = Regex("\\s+")
    private val STRESS_MARKS = Regex("[ˈˌ]")
    private val PUNCTUATION = Regex("[.?!()\\-]")
    private val STRESS_AND_PUNCTUATION = Regex("[ˈˌ.?!()\\-]")
    private val COMBINING_MARKS = Regex("[\u0300-\u0302\u0304-\u0360\u0362-\u036F]")

    /** Stops whose release is routinely lost word-finally. */
    private val PLOSIVES = setOf("p", "b", "t", "d", "k", "ɡ", "g", "ʔ", "c", "ɟ", "q")

    /**
     * A /ɹ/ is an onset when it opens a syllable - it is followed by a vowel or a
     * stress mark ("story" sˈtɔɹi, "addressed") - and is pronounced in every
     * accent. Otherwise it is a coda and silent in non-rhotic English ("near",
     * "car", "north"). Both the generic drop and every vowel+/ɹ/ fold below are
     * gated on this lookahead, so an intervocalic /ɹ/ is never folded away.
     */
    private const val RHO_NOT_ONSET = "(?![aeiouɪɛæʌɔʊəɜɐɑɒɨᵻˈˌ])"

    /** A /ɹ/ before a vowel or a stress mark opens a syllable and is pronounced. */
    private const val RHO_ONSET = "(?=[aeiouɪɛæʌɔʊəɜɐɑɒɨᵻˈˌ])"

    /** A remaining coda /ɹ/ is simply dropped. */
    private val CODA_RHO = Regex("ɹ$RHO_NOT_ONSET")

    /** NEAR/here: the KIT vowel plus a coda /ɹ/ becomes a centering diphthong. */
    private val NEAR_RHO = Regex("ɪɹ$RHO_NOT_ONSET")

    /**
     * The rhotic vowels ɝ/ɚ already bundle their /ɹ/. Before another vowel that
     * /ɹ/ is an onset and is pronounced ("camera" kˈæmɚə -> kˈæmərə, "furry"
     * fˈɝi -> fˈɜːri); only a coda /ɹ/ is lost, leaving a plain central vowel
     * ("water" wˈɔtɚ -> wˈɔtə).
     */
    private val ER_ONSET_RHO = Regex("ɝ$RHO_ONSET")
    private val SCHWA_R_ONSET_RHO = Regex("ɚ$RHO_ONSET")
    private val ER_RHO = Regex("ɝ")
    private val SCHWA_R_RHO = Regex("ɚ")

    /**
     * The vowel+/ɹ/ codas of non-rhotic English and the reflex each becomes. The
     * /ɹ/ is folded away only when it is a coda; before a vowel it is an onset and
     * the segment stays intact ("story" keeps its r).
     */
    private val NON_RHOTIC_VOWEL_RHO = listOf(
        Regex("ɑɹ$RHO_NOT_ONSET") to "ɑː",
        Regex("ɔɹ$RHO_NOT_ONSET") to "ɔː",
        Regex("ɛɹ$RHO_NOT_ONSET") to "eə",
        Regex("ʊɹ$RHO_NOT_ONSET") to "ʊə",
        Regex("əɹ$RHO_NOT_ONSET") to "ə",
    )

    /** The NEAR vowel onsets a non-rhotic English speaker may use. */
    private val NON_RHOTIC_NEAR_ONSETS = listOf("i", "ɪ")

    /**
     * Pairs that are the same phoneme in context (allophones or symbol
     * conventions), so a CLOSE match between them is not a substitution and must
     * not count as a hard error. Similarity values live in the matrices; this set
     * is what separates "related but tolerated" from "a different sound". It is
     * curated for English, the language this scorer is validated on; a language
     * without an entry treats every CLOSE substitution as a genuine error until
     * its contrasts are curated too.
     */
    private val TOLERATED_ALLOPHONES: Set<Set<String>> = setOf(
        // English connected speech: flap, rhotic variants, glottalisation.
        setOf("t", "ɾ"),
        setOf("d", "ɾ"),
        setOf("ɾ", "ɹ"),
        setOf("ɾ", "r"),
        setOf("r", "ɹ"),
        setOf("r", "ɹ̩"),
        setOf("t", "ʔ"),
        setOf("d", "ʔ"),
        // A glide standing in for a diphthong onset.
        setOf("ɪ", "j"),
        setOf("ʊ", "w"),
        // COT/CAUGHT, merged for most American speakers.
        setOf("ɑ", "ɔ"),
        // British [ɒ] for LOT/CLOTH vs the American reference symbols ɑ/ɔ.
        setOf("ɑ", "ɒ"),
        setOf("ɔ", "ɒ"),
        // Affricate symbol conventions.
        setOf("ʧ", "t͡ʃ"),
        setOf("ʧ", "tʃ"),
        setOf("ʤ", "d͡ʒ"),
        setOf("ʤ", "dʒ"),
    )

    // EN similarity matrix.
    //
    // Only symbol-convention spellings of the *same* sound may reach
    // [PERFECT_THRESHOLD]. Distinct phonemes - voicing pairs like k/ɡ, and every
    // vowel-quality contrast - stay below it so a real substitution earns partial
    // credit at most, never a perfect mark. The values in the 0.6-0.8 band were
    // left over from reconciling two different IPA symbol sets in an earlier
    // implementation; against this espeak-trained model the symbols agree, so
    // those entries were silently excusing exactly the substitutions a Spanish
    // speaker makes (ship/sheep, bad/bed, pull/pool).
    private val enSimilarityMatrix = mapOf(
        // Plosives: /k/ and /ɡ/ are a voicing contrast (coat/goat), not one sound.
        setOf("k", "ɡ") to 0.7,
        // Fricatives - exact
        // Nasals - exact
        // Approximants
        setOf("l", "r") to 0.8,
        // Rhotic: eng_to_ipa uses r, Allosaurus uses ɹ or syllabic ɹ̩
        setOf("r", "ɹ") to 0.98,
        setOf("r", "ɹ̩") to 0.98,
        // Connected-speech allophony. The reference is a citation-form
        // phonemization, so it writes /t/ where fluent speech uses a flap
        // ("butter", "sat on"), a glottal stop ("what?", British "butter"), or
        // drops the release entirely. These are the same English sound in
        // context, not learner errors, so they must not read as MISSED.
        setOf("t", "ɾ") to 0.9,
        setOf("d", "ɾ") to 0.9,
        setOf("ɾ", "ɹ") to 0.9,
        setOf("ɾ", "r") to 0.85,
        // Both /t/ and /d/ glottalise in the same contexts ("button", "widen"),
        // so the pair is treated symmetrically.
        setOf("t", "ʔ") to 0.85,
        setOf("d", "ʔ") to 0.85,
        // Affricates: eng_to_ipa ʧ/ʤ vs Allosaurus t͡ʃ/d͡ʒ or tʃ/dʒ
        setOf("ʧ", "t͡ʃ") to 0.98,
        setOf("ʧ", "tʃ") to 0.98,
        setOf("ʤ", "d͡ʒ") to 0.98,
        setOf("ʤ", "dʒ") to 0.98,
        // Glides that can stand in for a diphthong onset (/ɪ/ ~ /j/, /ʊ/ ~ /w/).
        setOf("ɪ", "j") to 0.75,
        setOf("ʊ", "w") to 0.75,
        // Vowel quality: real minimal pairs, so CLOSE at best. A Spanish speaker
        // saying /i/ for /ɪ/ ("ship" as "sheep") must not score a clean match.
        setOf("i", "ɪ") to 0.7,
        setOf("e", "ɛ") to 0.7,
        setOf("æ", "a") to 0.6,
        setOf("ɑ", "a") to 0.7,
        setOf("ɔ", "o") to 0.7,
        setOf("u", "ʊ") to 0.7,
        // Schwa/STRUT are close but contrastive (sofa/cup).
        setOf("ə", "ʌ") to 0.75,
        setOf("ə", "ɤ") to 0.75,
        setOf("ʌ", "ə") to 0.75,
        // COT/CAUGHT: genuinely merged for most American speakers, so the model
        // hearing /ɔ/ for a reference /ɑ/ is not a learner error. Kept perfect on
        // purpose; this is a dialect merge, not a Spanish-accent substitution.
        setOf("ɑ", "ɔ") to 0.85,
        // The British [ɒ] realisation of LOT/CLOTH, missing from the reference
        // symbols (CMUdict writes them ɑ/ɔ). espeak en-GB, the model and the
        // reference TTS, says "cot" as [kɒt] and "long" as [lɒŋ]; without this the
        // practiced sound is scored 0 on the clip the learner is imitating. It is
        // the same sound in the other convention, not a learner substitution, so
        // it matches perfectly (like the COT/CAUGHT merge above).
        setOf("ɑ", "ɒ") to 0.85,
        setOf("ɔ", "ɒ") to 0.85
    )

    /**
     * The English closed class of weak-form words, as normalized (stress-free)
     * reference IPA. These are the words a fluent speaker reduces or drops in
     * connected speech, so omitting one is an elision, not a mispronunciation.
     * Every reading CMUdict lists for a word is included ("and" is both ænd and
     * ənd, "the" is ðə/ði/ðʌ), so the rule applies whichever variant the reference
     * happens to carry. Content words are deliberately absent: dropping "cat" or
     * "think" is a real error.
     */
    private val EN_FUNCTION_WORDS: Set<String> = setOf(
        // Articles, determiners and possessives.
        "ə", "ən", "ðə", "ði", "ðʌ", "ðɪs", "ðæt", "ðət", "ðiz", "ðoʊz",
        "maɪ", "jɔɹ", "jʊɹ", "hɪz", "hɚ", "hɝ", "ɪts", "aʊɚ", "aʊɹ", "ɑɹ", "ðɛɹ",
        "sʌm", "ɛni",
        // Pronouns.
        "aɪ", "mi", "ju", "hi", "hɪm", "ɪm", "ʃi", "ɪt", "wi", "ʌs", "ðeɪ", "ðəm", "ðɛm",
        // Prepositions and conjunctions.
        "ʌv", "tu", "tə", "tɪ", "ɪn", "ɑn", "ɔn", "æt", "baɪ", "fɔɹ", "fɚ", "fɹɚ",
        "wɪð", "wɪθ", "fɹʌm", "ænd", "ənd", "ɔɹ", "ɚ", "bʌt", "nɔɹ", "ðæn", "ðən", "æz", "ɛz",
        // Auxiliaries and modals.
        "bi", "æm", "ɪz", "wʌz", "wəz", "wɝ", "bɪn", "hæz", "həz", "hæd",
        "du", "dʌz", "dɪd", "wɪl", "wʊd", "ʃæl", "ʃʊd", "kæn", "kən", "kʊd",
        "meɪ", "maɪt", "mʌst",
        // Negation.
        "nɑt",
    )

    /**
     * The same weak-form class as [EN_FUNCTION_WORDS], spelled as text. The
     * scorer prefers the source [text] over the reference IPA when both line up
     * word-for-word, so homophones of a function word ("sum" for "some", "bee"
     * for "be") are not mistaken for one; the IPA set is the fallback for callers
     * that only have the phonemization (the measurement probes).
     */
    private val EN_FUNCTION_WORD_TEXT: Set<String> = setOf(
        "a", "an", "the", "this", "that", "these", "those", "my", "your", "his", "her", "its",
        "our", "their", "some", "any", "i", "me", "you", "he", "him", "she", "it", "we", "us",
        "they", "them", "of", "to", "in", "on", "at", "by", "for", "with", "from",
        "and", "or", "but", "nor", "than", "as", "be", "am", "is", "are", "was", "were",
        "been", "have", "has", "had", "do", "does", "did", "will", "would", "shall", "should",
        "can", "could", "may", "might", "must", "not", "there",
    )

    // FR similarity matrix (from glosso-studio-fr)
    private val frSimilarityMatrix = mapOf(
        setOf("p", "b") to 0.8,
        setOf("t", "d") to 0.8,
        setOf("k", "ɡ") to 0.8,
        setOf("t", "t̪") to 0.9,
        setOf("d", "d̪") to 0.9,
        setOf("f", "v") to 0.8,
        setOf("s", "z") to 0.8,
        setOf("ʃ", "ʒ") to 0.8,
        setOf("ʒ", "dʒ") to 0.7,
        setOf("ʃ", "dʒ") to 0.5,
        setOf("ʁ", "ʀ") to 0.9,
        setOf("ʁ", "r") to 0.7,
        setOf("m", "n") to 0.6,
        setOf("n", "ɲ") to 0.6,
        setOf("n", "ŋ") to 0.6,
        setOf("y", "u") to 0.7,
        setOf("ø", "e") to 0.7,
        setOf("œ", "ɛ") to 0.7,
        setOf("ø", "œ") to 0.8,
        setOf("ɛ̃", "ɑ̃") to 0.6,
        setOf("œ̃", "ɛ̃") to 0.7,
        setOf("ɔ̃", "ɑ̃") to 0.6,
        setOf("e", "ɛ") to 0.85,
        setOf("o", "ɔ") to 0.7,
        setOf("a", "ɑ") to 0.8,
        setOf("ə", "e") to 0.85,
        setOf("ə", "œ") to 0.7,
        setOf("j", "i") to 0.8,
        setOf("w", "u") to 0.6,
        setOf("ɥ", "y") to 0.7,
        setOf("ɥ", "w") to 0.6
    )

    // ES similarity matrix — Spanish vowel and consonant allophony
    private val esSimilarityMatrix = mapOf(
        setOf("p", "b") to 0.8,
        setOf("t", "d") to 0.8,
        setOf("k", "ɡ") to 0.8,
        setOf("b", "β") to 0.9,
        setOf("d", "ð") to 0.9,
        setOf("ɡ", "ɣ") to 0.9,
        setOf("f", "v") to 0.7,
        setOf("s", "θ") to 0.6,
        setOf("s", "z") to 0.8,
        setOf("s", "ʃ") to 0.6,
        setOf("x", "h") to 0.7,
        setOf("tʃ", "ʃ") to 0.6,
        setOf("ʝ", "j") to 0.8,
        setOf("j", "i") to 0.85,
        setOf("w", "u") to 0.8,
        setOf("ʎ", "ʝ") to 0.9,
        setOf("ʎ", "l") to 0.7,
        setOf("ʎ", "dʒ") to 0.5,
        setOf("ʎ", "ʒ") to 0.5,
        setOf("ɲ", "n") to 0.7,
        setOf("m", "n") to 0.7,
        setOf("ɾ", "r") to 0.9,
        setOf("β", "v") to 0.7,
        setOf("ð", "β") to 0.6,
        setOf("i", "ɪ") to 0.9,
        setOf("e", "ɛ") to 0.9,
        setOf("a", "æ") to 0.85,
        setOf("a", "ɑ") to 0.9,
        setOf("a", "ʌ") to 0.7,
        setOf("a", "ə") to 0.8,
        setOf("o", "ɔ") to 0.9,
        setOf("o", "ʊ") to 0.6,
        setOf("u", "ʊ") to 0.9,
    )

    // DE similarity matrix - German vowel quality and length, R allophony,
    // rounded front vowels. Keys are the symbols espeak-ng `de` writes in the
    // reference (r/ɾ, ɜ, ɑː, ʏ, ɔø) plus what the model may answer with. Not
    // calibrated against real German speech yet (docs/ADD_LANGUAGE.md).
    private val deSimilarityMatrix = mapOf(
        setOf("p", "b") to 0.8,
        setOf("t", "d") to 0.8,
        setOf("k", "ɡ") to 0.8,
        setOf("f", "v") to 0.8,
        setOf("s", "z") to 0.8,
        setOf("ʃ", "ʒ") to 0.8,
        // One R phoneme. espeak-ng writes it r before a vowel and ɾ elsewhere;
        // speakers use a uvular, a trilled or a tapped R depending on region,
        // and none of them is a learner error.
        setOf("ʁ", "r") to 0.9,
        setOf("ɾ", "ʁ") to 0.9,
        setOf("ɾ", "r") to 0.95,
        // A coda R is vocalised ("Bier" [biːɐ]); the model's ɐ is folded to ə
        // before this lookup.
        setOf("ɾ", "ə") to 0.85,
        // The "-er" ending: espeak-ng writes ɜ, standard German says [ɐ].
        setOf("ɜ", "ə") to 0.85,
        setOf("ɜ", "a") to 0.6,
        setOf("ɜ", "ɛ") to 0.5,
        // A final "-e" is a schwa, not a full E.
        setOf("ə", "e") to 0.6,
        setOf("ə", "ɛ") to 0.6,
        setOf("x", "ç") to 0.6,
        setOf("x", "h") to 0.6,
        setOf("x", "k") to 0.4,
        setOf("ç", "ʃ") to 0.5,
        setOf("ç", "k") to 0.4,
        setOf("ʃ", "s") to 0.5,
        setOf("v", "b") to 0.6,
        setOf("v", "w") to 0.6,
        // Short Ü: espeak-ng writes ʏ, the model only has y. Same sound in two
        // spellings, so it matches; the long/short and the rounding contrasts
        // stay below the perfect threshold.
        setOf("ʏ", "y") to 0.98,
        setOf("ʏ", "yː") to 0.8,
        setOf("ʏ", "ɪ") to 0.7,
        setOf("ʏ", "ʊ") to 0.6,
        setOf("ʏ", "u") to 0.6,
        setOf("y", "i") to 0.7,
        setOf("y", "ɪ") to 0.7,
        setOf("y", "u") to 0.6,
        setOf("y", "ʊ") to 0.6,
        setOf("yː", "iː") to 0.7,
        setOf("yː", "uː") to 0.6,
        setOf("øː", "eː") to 0.6,
        setOf("øː", "oː") to 0.6,
        setOf("œ", "øː") to 0.7,
        setOf("œ", "ø") to 0.8,
        setOf("œ", "ɛ") to 0.7,
        setOf("œ", "ɔ") to 0.6,
        // The glide of "eu"/"äu": espeak-ng writes ɔø, also heard as ɔɪ or ɔʏ.
        setOf("ø", "ɪ") to 0.9,
        setOf("ø", "ʏ") to 0.9,
        setOf("ø", "y") to 0.9,
        // Long A is ɑː in the reference and aː in most transcriptions; against
        // a short A it is the Staat/Stadt length contrast.
        setOf("ɑ", "a") to 0.9,
        setOf("ɑː", "aː") to 0.98,
        setOf("ɑː", "a") to 0.8,
        setOf("ɑ", "aː") to 0.8,
        setOf("ɪ", "i") to 0.85,
        setOf("ɪ", "iː") to 0.7,
        setOf("ʊ", "u") to 0.8,
        setOf("ʊ", "uː") to 0.7,
        setOf("ɔ", "o") to 0.8,
        setOf("ɔ", "oː") to 0.7,
        setOf("e", "ɛ") to 0.9,
        setOf("ɛ", "eː") to 0.7,
        // Käse: ɛː and eː are merged for many speakers.
        setOf("ɛː", "eː") to 0.85,
        // /ts/ is parsed as t plus s (see isTiedPair), so these three only
        // apply to IPA that reaches the scorer already tokenised.
        setOf("t", "ts") to 0.7,
        setOf("ts", "s") to 0.6,
        setOf("ts", "z") to 0.6,
        setOf("m", "n") to 0.6,
        setOf("n", "ŋ") to 0.6,
    )

    // RU similarity matrix - keys are the symbols espeak-ng `ru` writes after
    // [RU_SYMBOL_FOLDS]: Ы is y, hard Л is ɭ, soft З is ʑ, unstressed О/А is ʌ.
    // Softness is its own segment (ʲ), so hard/soft pairs are not listed here: a
    // dropped ʲ is a deletion. Not calibrated against real Russian speech yet
    // (docs/ADD_LANGUAGE.md).
    private val ruSimilarityMatrix = mapOf(
        setOf("p", "b") to 0.8,
        setOf("t", "d") to 0.8,
        setOf("k", "ɡ") to 0.8,
        setOf("f", "v") to 0.8,
        setOf("s", "z") to 0.8,
        setOf("ʃ", "ʒ") to 0.8,
        setOf("v", "b") to 0.6,
        // Ш / Щ / Ч and the sounds a Spanish speaker reaches for instead.
        setOf("ʃ", "ɕ") to 0.6,
        setOf("ʃ", "s") to 0.5,
        setOf("ʃ", "tʃ") to 0.6,
        setOf("ɕ", "s") to 0.5,
        setOf("ʒ", "z") to 0.5,
        setOf("ʒ", "dʒ") to 0.6,
        // Soft З is one symbol (ʑ) in this notation.
        setOf("ʑ", "z") to 0.6,
        setOf("ʑ", "ʒ") to 0.6,
        setOf("x", "k") to 0.5,
        setOf("x", "h") to 0.9,
        // Hard Л: ɭ here, ɫ or a plain l elsewhere. Same phoneme.
        setOf("ɭ", "ɫ") to 0.98,
        setOf("ɭ", "l") to 0.9,
        setOf("r", "ɾ") to 0.9,
        setOf("m", "n") to 0.6,
        setOf("j", "i") to 0.8,
        setOf("j", "ɪ") to 0.8,
        // A soft consonant heard as consonant plus a short J ("vjet" for вет).
        setOf("ʲ", "j") to 0.9,
        // Ы: y here, ɨ in textbook IPA. Against И it is the быть/бить contrast.
        setOf("y", "ɨ") to 0.98,
        setOf("y", "i") to 0.6,
        setOf("y", "ɪ") to 0.6,
        setOf("y", "u") to 0.5,
        // Stressed and reduced A/O are the same phoneme in different positions;
        // a full O where the reference reduces it is the learner error.
        setOf("ɑ", "a") to 0.95,
        setOf("ʌ", "a") to 0.9,
        setOf("ʌ", "ɑ") to 0.9,
        setOf("ʌ", "ə") to 0.9,
        setOf("a", "ə") to 0.85,
        setOf("ʌ", "o") to 0.6,
        setOf("i", "ɪ") to 0.9,
        setOf("ɪ", "ə") to 0.85,
        setOf("i", "e") to 0.6,
        setOf("ɪ", "e") to 0.6,
        setOf("e", "ɛ") to 0.9,
        // Ё after a soft consonant is a fronted O.
        setOf("ɵ", "o") to 0.9,
        setOf("ɵ", "e") to 0.5,
        setOf("u", "ʉ") to 0.9,
        setOf("u", "ʊ") to 0.9,
        setOf("o", "ɔ") to 0.9,
    )

    /**
     * Russian symbol folds, applied to the reference and to what the model heard
     * before either is parsed. espeak-ng `ru` (and the model trained on it) uses
     * a few spellings that are one sound written in two or three characters; left
     * alone, the extra character would be scored as a segment of its own.
     *
     * - `tʃʲ`, `tɕ` -> `tʃ`: Ч is always soft, so its softness mark carries no
     *   contrast; a plain CH is the same phoneme.
     * - `ɕʲ` -> `ɕ`: likewise for Щ.
     * - `ɪ^` -> `ʲ`: the softness of a final РЬ ("дверь" `dvʲˈerɪ^`).
     * - `u"` -> `u`: У after a soft consonant, an allophone.
     * - `ʲʲ` -> `ʲ`: the model's doubled-softness tokens.
     *
     * `tools/content/phonemize.py` applies the espeak-ng ones at publish time, so
     * a published reference is already folded and its phone indices match the
     * forced alignment; keep the two tables in step.
     */
    private val RU_SYMBOL_FOLDS: List<Pair<String, String>> = listOf(
        "tʃʲ" to "tʃ",
        "tɕ" to "tʃ",
        "ɕʲ" to "ɕ",
        "ɪ^" to "ʲ",
        "u\"" to "u",
        // The multilingual recognizer (ZIPA) writes the same sounds in textbook
        // IPA where espeak-ng has its own symbols. One spelling per sound, so
        // the notation of the model is never scored as a difference.
        "t\u0361s" to "ts",
        "tsʲ" to "tʲ",
        "dzʲ" to "dʲ",
        "ɲ" to "nʲ",
        "ʂ" to "ʃ",
        "ʐ" to "ʒ",
        "ɨ" to "y",
        "g" to "ɡ",
        "ɣ" to "ɡ",
        "æ" to "a",
        "ɔ" to "o",
        "ː" to "",
        "˞" to "",
        "\u0303" to "",
        "ʲʲ" to "ʲ",
    )

    // LA similarity matrix — Latin vowel length, labialized velars, semivowels
    private val laSimilarityMatrix = mapOf(
        setOf("p", "b") to 0.8,
        setOf("t", "d") to 0.8,
        setOf("k", "ɡ") to 0.8,
        setOf("kʷ", "k") to 0.7,
        setOf("ɡʷ", "ɡ") to 0.7,
        setOf("j", "i") to 0.8,
        setOf("w", "u") to 0.8,
        setOf("r", "ɾ") to 0.85,
    )

    /**
     * The base language of a code ("fr-FR", "EN_gb" -> "fr", "en"). The matrices,
     * the hint tables and the letter mapping are keyed by base language, so a
     * region-tagged code must not fall through to the English defaults.
     */
    private fun baseLanguage(language: String): String =
        language.lowercase().substringBefore('-').substringBefore('_')

    private fun getMatrix(language: String) = when (baseLanguage(language)) {
        "fr" -> frSimilarityMatrix
        "es" -> esSimilarityMatrix
        "de" -> deSimilarityMatrix
        "ru" -> ruSimilarityMatrix
        "la" -> laSimilarityMatrix
        else -> enSimilarityMatrix
    }

    /** The similarity table of a language, so tests can hold its symbols to the model vocabulary. */
    internal fun similarityMatrix(language: String): Map<Set<String>, Double> = getMatrix(language)

    /**
     * The per-language rules that decide how two phones are compared: the
     * similarity matrix plus the symbol folds that language needs.
     *
     * English is scored from a CMUdict/ARPAbet reference while the acoustic model
     * was trained on espeak, and the two conventions differ in ways that have
     * nothing to do with the learner: the reference drops vowel length, and native
     * connected speech neutralises unstressed vowels. [englishFolds] turns those
     * spelling and realisation differences into the same phoneme, so the engine
     * stops failing its own reference audio (see docs/FINDINGS.md and the
     * ReferenceSelfScoreTest oracle). German and Latin keep a bare length
     * difference meaning-bearing (Stadt/Staat). Russian rewrites a few espeak
     * spellings before parsing ([RU_SYMBOL_FOLDS]); every other language has no
     * [symbolFolds] and its IPA reaches the parser untouched.
     */
    private data class ScoringProfile(
        val matrix: Map<Set<String>, Double>,
        val lengthMarksPhonemic: Boolean,
        val englishFolds: Boolean,
        val symbolFolds: List<Pair<String, String>> = emptyList(),
    ) {
        fun fold(ipa: String): String =
            symbolFolds.fold(ipa) { folded, (from, to) -> folded.replace(from, to) }
    }

    private fun profileFor(language: String): ScoringProfile {
        val normalized = language.lowercase()
        return ScoringProfile(
            matrix = getMatrix(language),
            lengthMarksPhonemic = normalized.startsWith("de") || normalized.startsWith("la"),
            englishFolds = normalized.startsWith("en"),
            symbolFolds = if (baseLanguage(language) == "ru") RU_SYMBOL_FOLDS else emptyList(),
        )
    }

    /**
     * An unstressed English schwa is routinely heard as a near-close vowel by the
     * acoustic model ("cinema", "prefer", "beautiful"). Schwa is never a stressed
     * English target, so accepting that realisation cannot forgive a contrast a
     * learner is meant to make (ship/sheep is `i` vs `ɪ`, untouched here).
     */
    private val REDUCED_VOWELS = setOf("ə")
    private val REDUCED_REALISATIONS = setOf("ɪ", "i")

    /** Human-readable descriptions for minimal phoneme pairs. */
    private val pairDescriptions: Map<Set<String>, Map<String, String>> = mapOf(
        setOf("p", "b") to mapOf("en" to "Put your fingers on your throat — B vibrates, P is silent. Same lips, different voice. Try: PAT vs. BAT.", "es" to "Pon los dedos en la garganta — la /b/ vibra, la /p/ no. Misma posición de los labios, distinto uso de la voz. Prueba: PAT vs. BAT.", "fr" to "Posez les doigts sur la gorge — le /b/ vibre, pas le /p/. Mêmes lèvres, voix différente. Essayez : PAT vs. BAT.", "de" to "Leg die Finger an den Hals — /b/ vibriert, /p/ nicht. Gleiche Lippen, andere Stimme. Probiere: PAT vs. BAT."),
        setOf("t", "d") to mapOf("en" to "D hums in your throat, T doesn't. Same tongue tap, different voice. Try: TEN vs. DEN.", "es" to "La /d/ zumba en la garganta, la /t/ no. Mismo toque de la lengua, distinto uso de la voz. Prueba: TEN vs. DEN.", "fr" to "Le /d/ résonne dans la gorge, pas le /t/. Même contact de langue, voix différente. Essayez : TEN vs. DEN.", "de" to "/d/ brummt im Hals, /t/ nicht. Gleicher Zungenschlag, andere Stimme. Probiere: TEN vs. DEN."),
        setOf("k", "ɡ") to mapOf("en" to "G vibrates in your throat, K doesn't. Try: COAT vs. GOAT.", "es" to "La /ɡ/ vibra en la garganta, la /k/ no. Prueba: COAT vs. GOAT.", "fr" to "Le /ɡ/ vibre dans la gorge, pas le /k/. Essayez : COAT vs. GOAT.", "de" to "/ɡ/ vibriert im Hals, /k/ nicht. Probiere: COAT vs. GOAT."),
        setOf("k", "g") to mapOf("en" to "G vibrates in your throat, K doesn't. Try: COAT vs. GOAT.", "es" to "La /g/ vibra en la garganta, la /k/ no. Prueba: COAT vs. GOAT.", "fr" to "Le /g/ vibre dans la gorge, pas le /k/. Essayez : COAT vs. GOAT.", "de" to "/g/ vibriert im Hals, /k/ nicht. Probiere: COAT vs. GOAT."),
        setOf("f", "v") to mapOf("en" to "V is a buzzing F — same lip-teeth contact, add voice. Try: FAN vs. VAN.", "es" to "La /v/ es una /f/ con zumbido — mismo contacto de labio y dientes, añade voz. Prueba: FAN vs. VAN.", "fr" to "Le /v/ est un /f/ bourdonnant — même contact lèvre-dents, ajoutez la voix. Essayez : FAN vs. VAN.", "de" to "/v/ ist ein summendes /f/ — gleicher Lippen-Zahn-Kontakt, nur mit Stimme. Probiere: FAN vs. VAN."),
        setOf("s", "z") to mapOf("en" to "Z is a buzzing S — same hiss, add voice from your throat. Try: SUE vs. ZOO.", "es" to "La /z/ es una /s/ con zumbido — mismo siseo, añade voz desde la garganta. Prueba: SUE vs. ZOO.", "fr" to "Le /z/ est un /s/ bourdonnant — même sifflement, ajoutez la voix. Essayez : SUE vs. ZOO.", "de" to "/z/ ist ein summendes /s/ — gleiches Zischen, Stimme aus dem Hals dazu. Probiere: SUE vs. ZOO."),
        setOf("ʃ", "ʒ") to mapOf("en" to "ZH is a buzzing SH — like SH but voiced. SH is in 'shoe'; ZH is in 'measure'.", "es" to "La /ʒ/ es una /ʃ/ con zumbido — como SH pero con voz. SH está en 'shoe'; ZH está en 'measure'.", "fr" to "Le /ʒ/ est un /ʃ/ bourdonnant — comme /ʃ/ mais voisé. /ʃ/ est dans 'shoe' ; /ʒ/ dans 'measure'.", "de" to "/ʒ/ ist ein summendes /ʃ/ — wie SCH, aber stimmhaft. SCH in 'shoe'; /ʒ/ in 'measure'."),
        setOf("θ", "ð") to mapOf("en" to "Put your tongue between your teeth. TH in 'think' (unvoiced) vs. TH in 'this' (voiced).", "es" to "Pon la lengua entre los dientes. TH en 'think' (sin voz) vs. TH en 'this' (con voz).", "fr" to "Placez la langue entre les dents. TH de 'think' (sourd) vs. TH de 'this' (voisé).", "de" to "Leg die Zunge zwischen die Zähne. TH in 'think' (stimmlos) vs. TH in 'this' (stimmhaft)."),
        setOf("m", "n") to mapOf("en" to "M closes your lips; N rests the tongue behind your teeth. Try humming 'M' then 'N' — you'll feel the difference.", "es" to "La /m/ cierra los labios; la /n/ apoya la lengua detrás de los dientes. Tararea 'M' y luego 'N' — notarás la diferencia.", "fr" to "Le /m/ ferme les lèvres ; le /n/ pose la langue derrière les dents. Fredonnez /m/ puis /n/ — vous sentirez la différence.", "de" to "/m/ schließt die Lippen; /n/ legt die Zunge hinter die Zähne. Summe 'M' und dann 'N' — du spürst den Unterschied."),
        setOf("n", "ŋ") to mapOf("en" to "N is at the front (teeth); NG is at the back (soft palate, as in 'sing'). Don't add a G at the end.", "es" to "La /n/ va adelante (dientes); la /ŋ/ va atrás (paladar blando, como en 'sing'). No añadas una G al final.", "fr" to "Le /n/ est à l'avant (dents) ; /ŋ/ est à l'arrière (palais mou, comme dans 'sing'). N'ajoutez pas de /g/ à la fin.", "de" to "/n/ ist vorn (an den Zähnen); /ŋ/ ist hinten (am Gaumensegel, wie in 'sing'). Kein /g/ am Ende anhängen."),
        setOf("l", "r") to mapOf("en" to "L touches the roof of your mouth with the tongue tip. R never touches — it curls or bunches. Try: LIGHT vs. RIGHT.", "es" to "La /l/ toca el paladar con la punta de la lengua. La /r/ inglesa nunca toca — se curva o se agrupa. Prueba: LIGHT vs. RIGHT.", "fr" to "Le /l/ touche le palais avec la pointe de la langue. Le /r/ ne touche jamais — il se recourbe. Essayez : LIGHT vs. RIGHT.", "de" to "/l/ berührt mit der Zungenspitze den Gaumen. /r/ berührt nichts — es krümmt oder bündelt sich. Probiere: LIGHT vs. RIGHT."),
        setOf("l", "ɾ") to mapOf("en" to "L holds your tongue against the roof. The tap (as in Spanish 'pero') is a single quick flick, not a hold.", "es" to "La /l/ mantiene la lengua contra el paladar. El toque (como en 'pero') es un golpecito rápido, no sostenido.", "fr" to "Le /l/ maintient la langue contre le palais. Le battement (comme dans 'pero' en espagnol) est un seul coup rapide, sans maintien.", "de" to "/l/ hält die Zunge am Gaumen. Der Schlag (wie im spanischen 'pero') ist ein einzelner schneller Tipper, kein Halten."),
        setOf("l", "ɹ") to mapOf("en" to "L touches the roof of your mouth; English R bunches the tongue back without touching. Try: LOCK vs. ROCK.", "es" to "La /l/ toca el paladar; la /ɹ/ inglesa agrupa la lengua atrás sin tocar. Prueba: LOCK vs. ROCK.", "fr" to "Le /l/ touche le palais ; le /ɹ/ anglais ramasse la langue en arrière sans toucher. Essayez : LOCK vs. ROCK.", "de" to "/l/ berührt den Gaumen; das englische /ɹ/ bündelt die Zunge zurück, ohne zu berühren. Probiere: LOCK vs. ROCK."),
        setOf("w", "v") to mapOf("en" to "Classical Latin V is pronounced like English W (veni = 'weh-nee', not 'veh-nee').", "es" to "La V del latín clásico se pronuncia como la W inglesa (veni = 'weh-nee', no 'veh-nee').", "fr" to "Le V du latin classique se prononce comme le W anglais (veni = 'weh-nee', pas 'veh-nee').", "de" to "Klassisch-lateinisches V wird wie englisches W gesprochen (veni = 'weh-nee', nicht 'veh-nee')."),
        setOf("j", "ʝ") to mapOf("en" to "Y in English 'yes' is a glide; the Spanish Y/LL ('yo', 'llave') is tighter, almost a J sound.", "es" to "La Y del inglés en 'yes' es una semivocal; la Y/LL del español ('yo', 'llave') es más cerrada, casi una J.", "fr" to "Le /j/ anglais de 'yes' est une semi-voyelle ; le Y/LL espagnol ('yo', 'llave') est plus tendu, presque un /ʝ/.", "de" to "/j/ im englischen 'yes' ist ein Gleitlaut; das spanische Y/LL ('yo', 'llave') ist enger, fast ein J-Laut."),
        setOf("tʃ", "ʃ") to mapOf("en" to "CH (chair) starts with a T before sliding into SH. SH (share) is pure fricative with no stop.", "es" to "La CH (chair) empieza con una T antes de deslizarse a SH. La SH (share) es pura fricativa, sin oclusión.", "fr" to "CH (chair) commence par un /t/ avant de glisser vers /ʃ/. SH (share) est une fricative pure sans occlusion.", "de" to "TSCH (chair) beginnt mit einem /t/, bevor es ins SCH gleitet. SCH (share) ist ein reiner Reibelaut ohne Verschluss."),
        setOf("dʒ", "ʒ") to mapOf("en" to "J (juice) starts with a D before the buzz. ZH (measure) is pure fricative with no stop.", "es" to "La J (juice) empieza con una D antes del zumbido. La ZH (measure) es pura fricativa, sin oclusión.", "fr" to "J (juice) commence par un /d/ avant le bourdonnement. ZH (measure) est une fricative pure sans occlusion.", "de" to "DSCH (juice) beginnt mit einem /d/ vor dem Summen. /ʒ/ (measure) ist ein reiner Reibelaut ohne Verschluss."),
        setOf("ts", "s") to mapOf("en" to "TS (German 'Zeit') starts with a T before the hiss. S is pure hiss.", "es" to "La TS (alemán 'Zeit') empieza con una T antes del siseo. La S es puro siseo.", "fr" to "TS (allemand 'Zeit') commence par un /t/ avant le sifflement. /s/ est un sifflement pur.", "de" to "TS (deutsches 'Zeit') beginnt mit einem /t/ vor dem Zischen. /s/ ist reines Zischen."),
        setOf("i", "ɪ") to mapOf("en" to "Tense EE (sheep) is long and smiley. Lax IH (ship) is short and relaxed. Don't stretch both the same.", "es" to "La EE tensa (sheep) es larga y con los labios estirados. La IH relajada (ship) es corta y suelta. No las alargues igual.", "fr" to "Le /i/ tendu (sheep) est long et souriant. Le /ɪ/ relâché (ship) est court et détendu. Ne les allongez pas pareil.", "de" to "Gespanntes EE (sheep) ist lang und lächelnd. Laxes IH (ship) ist kurz und entspannt. Nicht beide gleich dehnen."),
        setOf("iː", "ɪ") to mapOf("en" to "Long EE in 'sheep' vs. short IH in 'ship'. Length matters — don't collapse them.", "es" to "EE larga en 'sheep' vs. IH corta en 'ship'. La duración importa — no las mezcles.", "fr" to "Long /iː/ dans 'sheep' vs. court /ɪ/ dans 'ship'. La durée compte — ne les confondez pas.", "de" to "Langes /iː/ in 'sheep' vs. kurzes /ɪ/ in 'ship'. Die Länge zählt — wirf sie nicht zusammen."),
        setOf("e", "ɛ") to mapOf("en" to "E (like Spanish 'e') is tighter and higher than EH (English 'bed'). Drop your jaw slightly for EH.", "es" to "La E (como la 'e' española) es más cerrada y alta que la EH (inglés 'bed'). Baja un poco la mandíbula para EH.", "fr" to "Le /e/ (comme le 'e' espagnol) est plus tendu et plus fermé que /ɛ/ (anglais 'bed'). Ouvrez un peu la mâchoire pour /ɛ/.", "de" to "/e/ (wie das spanische 'e') ist enger und höher als /ɛ/ (englisches 'bed'). Kiefer für /ɛ/ leicht senken."),
        setOf("eɪ", "ɛ") to mapOf("en" to "AY (day) glides from E to EE. EH (dead) is a pure short vowel — no gliding.", "es" to "La AY (day) se desliza de E a EE. La EH (dead) es una vocal pura corta — sin deslizamiento.", "fr" to "AY (day) glisse de /e/ vers /i/. /ɛ/ (dead) est une voyelle courte pure — sans glissement.", "de" to "AY (day) gleitet von /e/ nach /i/. /ɛ/ (dead) ist ein reiner kurzer Vokal — kein Gleiten."),
        setOf("æ", "a") to mapOf("en" to "AA in 'cat' is a flatter, more front A. AH in 'palm' is deeper and more back. Open your mouth wider for AH.", "es" to "La AA en 'cat' es una A más plana y anterior. La AH en 'palm' es más grave y posterior. Abre más la boca para AH.", "fr" to "Le /æ/ de 'cat' est un /a/ plus plat et plus antérieur. Le /a/ de 'palm' est plus profond et postérieur. Ouvrez plus pour /a/.", "de" to "/æ/ in 'cat' ist ein flacheres, weiter vorne gebildetes A. /a/ in 'palm' ist tiefer und weiter hinten. Mund für /a/ weiter öffnen."),
        setOf("æ", "ɛ") to mapOf("en" to "AA in 'cat' is wider open than EH in 'bed'. Drop your jaw more for AA.", "es" to "La AA en 'cat' es más abierta que la EH en 'bed'. Baja más la mandíbula para AA.", "fr" to "Le /æ/ de 'cat' est plus ouvert que le /ɛ/ de 'bed'. Baissez davantage la mâchoire pour /æ/.", "de" to "/æ/ in 'cat' ist weiter geöffnet als /ɛ/ in 'bed'. Kiefer für /æ/ stärker senken."),
        setOf("ɑ", "a") to mapOf("en" to "AH in 'father' sits at the back. A in Spanish 'casa' sits more front. Move your tongue forward for the Spanish A.", "es" to "La AH en 'father' se produce atrás. La A del español 'casa' va más adelante. Mueve la lengua hacia adelante para la A española.", "fr" to "Le /ɑ/ de 'father' est postérieur. Le /a/ espagnol de 'casa' est plus antérieur. Avancez la langue pour le /a/ espagnol.", "de" to "/ɑ/ in 'father' sitzt hinten. /a/ im spanischen 'casa' sitzt weiter vorn. Zunge für das spanische A nach vorn."),
        setOf("ɑː", "ʌ") to mapOf("en" to "Long AH (father) is deep and open. UH (but) is shorter and higher in the mouth.", "es" to "La AH larga (father) es grave y abierta. La UH (but) es más corta y más alta en la boca.", "fr" to "Le /ɑː/ long (father) est profond et ouvert. /ʌ/ (but) est plus court et plus haut dans la bouche.", "de" to "Langes /ɑː/ (father) ist tief und offen. /ʌ/ (but) ist kürzer und höher im Mund."),
        setOf("u", "ʊ") to mapOf("en" to "OO in 'pool' is long with firmly rounded lips. OO in 'book' is shorter and less rounded.", "es" to "La OO en 'pool' es larga con labios bien redondeados. La OO en 'book' es más corta y menos redondeada.", "fr" to "Le /u/ de 'pool' est long avec les lèvres bien arrondies. Le /ʊ/ de 'book' est plus court et moins arrondi.", "de" to "/uː/ in 'pool' ist lang mit fest gerundeten Lippen. /ʊ/ in 'book' ist kürzer und weniger gerundet."),
        setOf("uː", "ʊ") to mapOf("en" to "Long OO (pool) vs. short OO (book) — tongue position is similar, but length and lip-rounding differ.", "es" to "OO larga (pool) vs. OO corta (book) — la lengua está parecida, pero cambian la duración y el redondeo.", "fr" to "Long /uː/ (pool) vs. court /ʊ/ (book) — position de langue proche, mais durée et arrondi diffèrent.", "de" to "Langes /uː/ (pool) vs. kurzes /ʊ/ (book) — Zungenstellung ähnlich, aber Länge und Lippenrundung unterscheiden sich."),
        setOf("ə", "ʌ") to mapOf("en" to "Schwa (sofa) is a completely relaxed, neutral sound. UH (cup) is slightly stronger and more open.", "es" to "La schwa (sofa) es un sonido totalmente relajado y neutro. La UH (cup) es algo más fuerte y abierta.", "fr" to "Le schwa /ə/ (sofa) est un son neutre complètement détendu. /ʌ/ (cup) est un peu plus fort et plus ouvert.", "de" to "Schwa (sofa) ist ein völlig entspannter, neutraler Laut. /ʌ/ (cup) ist etwas stärker und offener."),
        setOf("ə", "ɤ") to mapOf("en" to "Both are relaxed neutral vowels — slight tongue position difference, often interchangeable in rapid speech.", "es" to "Ambas son vocales neutras relajadas — leve diferencia de posición de la lengua, a menudo intercambiables en el habla rápida.", "fr" to "Les deux sont des voyelles neutres relâchées — légère différence de position de langue, souvent interchangeables en parole rapide.", "de" to "Beide sind entspannte neutrale Vokale — kleiner Unterschied in der Zungenstellung, oft austauschbar im schnellen Sprechen."),
        setOf("ɔ", "o") to mapOf("en" to "AW (thought) is open and relaxed. OH (note) is more rounded and higher in the mouth.", "es" to "La AW (thought) es abierta y relajada. La OH (note) es más redondeada y más alta en la boca.", "fr" to "Le /ɔ/ (thought) est ouvert et relâché. Le /o/ (note) est plus arrondi et plus haut dans la bouche.", "de" to "/ɔ/ (thought) ist offen und entspannt. /o/ (note) ist stärker gerundet und höher im Mund."),
        setOf("ɔː", "oʊ") to mapOf("en" to "AW (bought) is a pure open vowel. OH (boat) glides from O to UU — it's a diphthong.", "es" to "La AW (bought) es una vocal abierta pura. La OH (boat) se desliza de O a UU — es un diptongo.", "fr" to "/ɔː/ (bought) est une voyelle ouverte pure. /oʊ/ (boat) glisse de O vers U — c'est une diphtongue.", "de" to "/ɔː/ (bought) ist ein reiner offener Vokal. /oʊ/ (boat) gleitet von O nach U — es ist ein Diphthong."),
        setOf("aɪ", "eɪ") to mapOf("en" to "EYE (time) glides from AH to EE. AY (tame) glides from E to EE. Different starting point.", "es" to "La EYE (time) se desliza de AH a EE. La AY (tame) se desliza de E a EE. Distinto punto de partida.", "fr" to "/aɪ/ (time) glisse de /a/ vers /i/. /eɪ/ (tame) glisse de /e/ vers /i/. Point de départ différent.", "de" to "/aɪ/ (time) gleitet von /a/ nach /i/. /eɪ/ (tame) gleitet von /e/ nach /i/. Anderer Ausgangspunkt."),
        setOf("aʊ", "oʊ") to mapOf("en" to "OW (now) glides from AH to OO. OH (no) glides from O to OO. Different starting vowel.", "es" to "La OW (now) se desliza de AH a OO. La OH (no) se desliza de O a OO. Vocal inicial distinta.", "fr" to "/aʊ/ (now) glisse de /a/ vers /u/. /oʊ/ (no) glisse de /o/ vers /u/. Voyelle de départ différente.", "de" to "/aʊ/ (now) gleitet von /a/ nach /u/. /oʊ/ (no) gleitet von /o/ nach /u/. Anderer Ausgangsvokal."),
        setOf("ʁ", "r") to mapOf("en" to "French R is made at the back of the throat (like a soft gargle). It's NOT the rolled tongue R of Spanish or Italian.", "es" to "La R francesa se produce en el fondo de la garganta (como un gargareo suave). NO es la R vibrante de la lengua del español o el italiano.", "fr" to "Le /ʁ/ français se fait au fond de la gorge (comme un léger raclement). Ce n'est PAS le /r/ roulé espagnol ou italien.", "de" to "Das französische /ʁ/ entsteht hinten im Rachen (wie ein sanftes Gurgeln). Es ist NICHT das gerollte Zungen-R wie im Spanischen oder Italienischen."),
        setOf("ʁ", "ʀ") to mapOf("en" to "Both are French R variants from the back of the throat — one is a soft friction, the other a trill. Use the softer one.", "es" to "Ambas son variantes de la R francesa desde el fondo de la garganta — una es una fricción suave, la otra una vibrante. Usa la más suave.", "fr" to "Les deux sont des variantes françaises venant du fond de la gorge — l'une est une friction douce, l'autre un roulement. Utilisez la plus douce.", "de" to "Beide sind französische R-Varianten aus dem hinteren Rachen — eine ist sanfte Reibung, die andere ein Triller. Nimm die sanftere."),
        setOf("y", "u") to mapOf("en" to "French U (tu) — shape your lips as if whistling OO, but say EE with your tongue. OU (tout) is a normal OO.", "es" to "La U francesa (tu) — pon los labios como para silbar OO, pero di EE con la lengua. La OU (tout) es una OO normal.", "fr" to "Le U français (tu) — arrondissez les lèvres comme pour siffler OU, mais dites /i/ avec la langue. OU (tout) est un /u/ normal.", "de" to "Französisches /y/ (tu) — Lippen wie zum Pfeifen runden, aber mit der Zunge /i/ sagen. OU (tout) ist ein normales /u/."),
        setOf("ø", "e") to mapOf("en" to "EU (feu) — round your lips while saying E. É (fée) is the same E without the lip rounding.", "es" to "EU (feu) — redondea los labios mientras dices E. La É (fée) es la misma E sin redondeo de labios.", "fr" to "EU (feu) — arrondissez les lèvres en disant /e/. É (fée) est le même /e/ sans arrondi des lèvres.", "de" to "EU (feu) — runde die Lippen und sag gleichzeitig /e/. É (fée) ist dasselbe /e/ ohne Lippenrundung."),
        setOf("œ", "ɛ") to mapOf("en" to "EU open (peur) — round your lips while saying the open EH (père). Jaw slightly lower than for closed EU.", "es" to "EU abierta (peur) — redondea los labios mientras dices la EH abierta (père). Mandíbula un poco más baja que para la EU cerrada.", "fr" to "EU ouvert (peur) — arrondissez les lèvres en disant le /ɛ/ ouvert (père). Mâchoire un peu plus basse que pour EU fermé.", "de" to "Offenes EU (peur) — runde die Lippen und sag das offene /ɛ/ (père). Kiefer etwas tiefer als beim geschlossenen EU."),
        setOf("ø", "œ") to mapOf("en" to "Both are rounded front vowels. Closed EU (peu) is tighter, open EU (peur) has a more open mouth.", "es" to "Ambas son vocales anteriores redondeadas. La EU cerrada (peu) es más cerrada, la EU abierta (peur) tiene la boca más abierta.", "fr" to "Les deux sont des voyelles antérieures arrondies. EU fermé (peu) est plus tendu, EU ouvert (peur) a la bouche plus ouverte.", "de" to "Beide sind gerundete Vordervokale. Geschlossenes EU (peu) ist enger, offenes EU (peur) hat den Mund weiter offen."),
        setOf("ɥ", "w") to mapOf("en" to "UI (nuit) is a front-rounded glide (tongue forward, lips rounded). OUI uses a back glide (tongue back, lips rounded).", "es" to "La UI (nuit) es una semivocal anterior redondeada (lengua adelante, labios redondos). OUI usa una semivocal posterior (lengua atrás, labios redondos).", "fr" to "UI (nuit) est une semi-voyelle antérieure arrondie (langue avant, lèvres arrondies). OUI utilise une semi-voyelle postérieure (langue arrière, lèvres arrondies).", "de" to "UI (nuit) ist ein vorne-gerundeter Gleitlaut (Zunge vorn, Lippen gerundet). OUI nutzt einen hinteren Gleitlaut (Zunge hinten, Lippen gerundet)."),
        setOf("ɥ", "y") to mapOf("en" to "The semi-vowel in 'nuit' is a quick glide before the next vowel. The full vowel 'tu' holds longer.", "es" to "La semivocal de 'nuit' es un deslizamiento rápido antes de la siguiente vocal. La vocal plena 'tu' se mantiene más tiempo.", "fr" to "La semi-voyelle de 'nuit' est un glissement rapide avant la voyelle suivante. La voyelle pleine 'tu' se tient plus longtemps.", "de" to "Der Halbvokal in 'nuit' ist ein kurzer Gleitlaut vor dem nächsten Vokal. Der volle Vokal 'tu' wird länger gehalten."),
        setOf("ɔ̃", "ɑ̃") to mapOf("en" to "ON (bon) rounds the lips and pushes the sound through the nose. AN (banc) keeps lips unrounded, same nasal airflow.", "es" to "ON (bon) redondea los labios y empuja el sonido por la nariz. AN (banc) deja los labios sin redondear, con el mismo flujo nasal.", "fr" to "ON (bon) arrondit les lèvres et fait passer le son par le nez. AN (banc) garde les lèvres non arrondies, même passage nasal.", "de" to "ON (bon) rundet die Lippen und schickt den Laut durch die Nase. AN (banc) hält die Lippen ungerundet, gleicher Nasenstrom."),
        setOf("ɛ̃", "ɑ̃") to mapOf("en" to "IN (vin) is a nasal front vowel (like a nasal E). AN (banc) is a nasal back vowel (deeper and more open).", "es" to "IN (vin) es una vocal nasal anterior (como una E nasal). AN (banc) es una vocal nasal posterior (más grave y abierta).", "fr" to "IN (vin) est une voyelle nasale antérieure (comme un /ɛ/ nasalisé). AN (banc) est une voyelle nasale postérieure (plus profonde et plus ouverte).", "de" to "IN (vin) ist ein nasaler Vordervokal (wie ein nasales E). AN (banc) ist ein nasaler Hintervokal (tiefer und offener)."),
        setOf("ɛ̃", "œ̃") to mapOf("en" to "IN (vin) is unrounded; UN (brun) is rounded. In modern Parisian French these are often merged to IN.", "es" to "IN (vin) no lleva los labios redondeados; UN (brun) sí. En el francés parisino moderno suelen fusionarse en IN.", "fr" to "IN (vin) est non arrondi ; UN (brun) est arrondi. En français parisien moderne, ils se confondent souvent avec IN.", "de" to "IN (vin) ist ungerundet; UN (brun) ist gerundet. Im modernen Pariser Französisch werden sie oft zu IN verschmolzen."),
        setOf("ɲ", "n") to mapOf("en" to "GN (agneau) — press the middle of your tongue to the roof of your mouth. N is made with just the tongue tip.", "es" to "GN (agneau) — aprieta el medio de la lengua contra el paladar. La N se hace solo con la punta de la lengua.", "fr" to "GN (agneau) — pressez le milieu de la langue contre le palais. /n/ se fait juste avec la pointe de la langue.", "de" to "GN (agneau) — drücke den mittleren Teil der Zunge an den Gaumen. /n/ wird nur mit der Zungenspitze gebildet."),
        setOf("β", "b") to mapOf("en" to "Spanish B/V between vowels (lobo) — lips don't fully close, air keeps flowing. At start of word or after M/N it's a full stop.", "es" to "La B/V española entre vocales (lobo) — los labios no se cierran del todo, el aire sigue fluyendo. Al inicio de palabra o tras M/N es una oclusiva plena.", "fr" to "Le B/V espagnol entre voyelles (lobo) — les lèvres ne se ferment pas complètement, l'air continue de passer. En début de mot ou après M/N, c'est une occlusion pleine.", "de" to "Spanisches B/V zwischen Vokalen (lobo) — Lippen schließen sich nicht ganz, Luft strömt weiter. Am Wortanfang oder nach M/N ist es ein voller Verschluss."),
        setOf("ð", "d") to mapOf("en" to "Spanish D between vowels (cada) is soft, like English TH in 'this'. At start of word it's a crisp full D.", "es" to "La D española entre vocales (cada) es suave, como la TH inglesa en 'this'. Al inicio de palabra es una D plena y firme.", "fr" to "Le D espagnol entre voyelles (cada) est doux, comme le TH anglais de 'this'. En début de mot, c'est un D franc et plein.", "de" to "Spanisches D zwischen Vokalen (cada) ist weich, wie das englische TH in 'this'. Am Wortanfang ein klares volles D."),
        setOf("ɣ", "ɡ") to mapOf("en" to "Spanish G between vowels (lago) is soft — the tongue doesn't fully touch. At start it's a full hard G (gato).", "es" to "La G española entre vocales (lago) es suave — la lengua no toca del todo. Al inicio es una G plena y dura (gato).", "fr" to "Le G espagnol entre voyelles (lago) est doux — la langue ne touche pas complètement. En début, c'est un G dur plein (gato).", "de" to "Spanisches G zwischen Vokalen (lago) ist weich — die Zunge berührt nicht ganz. Am Anfang ist es ein volles hartes G (gato)."),
        setOf("ɣ", "g") to mapOf("en" to "Spanish G between vowels (lago) is soft — the tongue doesn't fully touch. At start it's a full hard G (gato).", "es" to "La G española entre vocales (lago) es suave — la lengua no toca del todo. Al inicio es una G plena y dura (gato).", "fr" to "Le G espagnol entre voyelles (lago) est doux — la langue ne touche pas complètement. En début, c'est un G dur plein (gato).", "de" to "Spanisches G zwischen Vokalen (lago) ist weich — die Zunge berührt nicht ganz. Am Anfang ist es ein volles hartes G (gato)."),
        setOf("ɾ", "r") to mapOf("en" to "Single R (pero) is ONE quick tongue flick. Double RR (perro) is a rolled trill — the tongue bounces several times.", "es" to "La R simple (pero) es UN golpecito rápido de la lengua. La RR doble (perro) es vibrante — la lengua rebota varias veces.", "fr" to "Le R simple (pero) est UN seul coup de langue rapide. Le RR double (perro) est un roulement — la langue bat plusieurs fois.", "de" to "Einfaches R (pero) ist EIN schneller Zungenschlag. Doppeltes RR (perro) ist ein gerollter Triller — die Zunge hüpft mehrfach."),
        setOf("ɾ", "ɹ") to mapOf("en" to "Spanish R is a quick tongue-tip flick against the roof. English R curls back without any tap — very different action.", "es" to "La R española es un golpecito rápido de la punta de la lengua contra el paladar. La R inglesa se curva atrás sin tocar — acción muy distinta.", "fr" to "Le /ɾ/ espagnol est un coup rapide de la pointe de la langue contre le palais. Le /ɹ/ anglais se recourbe sans toucher — geste très différent.", "de" to "Spanisches R ist ein schneller Zungenspitzen-Schlag gegen den Gaumen. Englisches /ɹ/ krümmt sich zurück ohne Schlag — ganz andere Bewegung."),
        setOf("r", "ɹ") to mapOf("en" to "Spanish trilled R bounces the tongue tip repeatedly. English R is a single bunch — no bouncing.", "es" to "La R vibrante española hace rebotar la punta de la lengua varias veces. La R inglesa es un solo agrupamiento — sin rebotes.", "fr" to "Le R roulé espagnol bat la pointe de la langue plusieurs fois. Le /ɹ/ anglais est un seul ramassé — sans battement.", "de" to "Spanisches gerolltes R lässt die Zungenspitze mehrfach springen. Englisches /ɹ/ ist ein einzelnes Bündeln — kein Springen."),
        setOf("ʝ", "ʎ") to mapOf("en" to "In most Spanish dialects Y and LL sound the same. LL traditionally uses a palatal L (press middle of tongue to roof).", "es" to "En la mayoría de dialectos del español, Y y LL suenan igual. La LL tradicional usa una L palatal (medio de la lengua contra el paladar).", "fr" to "Dans la plupart des dialectes espagnols, Y et LL se prononcent pareil. LL utilise traditionnellement un L palatal (milieu de la langue contre le palais).", "de" to "In den meisten spanischen Dialekten klingen Y und LL gleich. LL nutzt traditionell ein palatales L (Zungenmitte an den Gaumen drücken)."),
        setOf("ʎ", "l") to mapOf("en" to "LL (lluvia) presses the middle of the tongue against the roof. L (luna) uses just the tongue tip.", "es" to "LL (lluvia) aprieta el medio de la lengua contra el paladar. L (luna) usa solo la punta de la lengua.", "fr" to "LL (lluvia) presse le milieu de la langue contre le palais. /l/ (luna) n'utilise que la pointe de la langue.", "de" to "LL (lluvia) drückt die Zungenmitte an den Gaumen. /l/ (luna) nutzt nur die Zungenspitze."),
        setOf("x", "h") to mapOf("en" to "Spanish J (jota) is raspier — made at the back of the mouth with friction. English H is just breath with no friction.", "es" to "La J española (jota) es más áspera — se hace atrás en la boca con fricción. La H inglesa es solo aire, sin fricción.", "fr" to "Le J espagnol (jota) est plus râpeux — fait au fond de la bouche avec friction. Le H anglais est juste un souffle sans friction.", "de" to "Spanisches J (jota) ist rauer — hinten im Mund mit Reibung gebildet. Englisches H ist nur Atem ohne Reibung."),
        setOf("x", "k") to mapOf("en" to "Spanish J hisses at the back of the mouth. K is a hard stop — same place, but stop vs. hiss.", "es" to "La J española sisea atrás en la boca. La K es una oclusiva dura — mismo lugar, pero oclusiva vs. siseo.", "fr" to "Le J espagnol siffle au fond de la bouche. Le /k/ est une occlusion dure — même lieu, mais occlusion vs. sifflement.", "de" to "Spanisches J zischt hinten im Mund. /k/ ist ein harter Verschluss — gleicher Ort, aber Verschluss vs. Zischen."),
        setOf("x", "ç") to mapOf("en" to "German CH after A/O/U (Bach) is a back raspy sound. After E/I (ich) it's a soft front hiss like a whispered Y.", "es" to "La CH alemana tras A/O/U (Bach) es un sonido áspero posterior. Tras E/I (ich) es un siseo anterior suave, como una Y susurrada.", "fr" to "Le CH allemand après A/O/U (Bach) est un son râpeux postérieur. Après E/I (ich), c'est un doux sifflement antérieur, comme un Y chuchoté.", "de" to "Deutsches CH nach A/O/U (Bach) ist ein hinterer rauer Laut. Nach E/I (ich) ist es ein weiches vorderes Zischen wie ein geflüstertes Y."),
        setOf("ʁ", "ɐ") to mapOf("en" to "At the start of a syllable, German R is a back-of-throat sound. At the end (or after a vowel), it becomes a relaxed UH-like vowel.", "es" to "Al inicio de sílaba, la R alemana es un sonido del fondo de la garganta. Al final (o tras una vocal), se convierte en una vocal relajada tipo UH.", "fr" to "En début de syllabe, le R allemand est un son de fond de gorge. À la fin (ou après voyelle), il devient une voyelle relâchée proche d'un /ɐ/.", "de" to "Am Silbenanfang ist das deutsche R ein hinterer Rachenlaut. Am Ende (oder nach einem Vokal) wird es zu einem entspannten /ɐ/-ähnlichen Vokal."),
        setOf("pf", "f") to mapOf("en" to "PF (Pferd) starts with a P before the F — both sounds in quick succession. F alone is just the fricative.", "es" to "PF (Pferd) empieza con una P antes de la F — ambos sonidos en rápida sucesión. La F sola es solo la fricativa.", "fr" to "PF (Pferd) commence par un /p/ avant le /f/ — les deux sons s'enchaînent rapidement. /f/ seul n'est que la fricative.", "de" to "PF (Pferd) beginnt mit einem /p/ vor dem /f/ — beide Laute in schneller Folge. /f/ allein ist nur der Reibelaut."),
        setOf("ts", "z") to mapOf("en" to "Z (Zeit) is pronounced TS — a T immediately followed by S. German S between vowels is voiced like English Z.", "es" to "La Z (Zeit) se pronuncia TS — una T seguida de inmediato por una S. La S alemana entre vocales tiene voz, como la Z inglesa.", "fr" to "Z (Zeit) se prononce TS — un /t/ suivi immédiatement d'un /s/. Le S allemand entre voyelles est voisé, comme le Z anglais.", "de" to "Z (Zeit) wird TS gesprochen — ein /t/ sofort gefolgt von /s/. Deutsches S zwischen Vokalen ist stimmhaft wie englisches Z."),
        setOf("yː", "uː") to mapOf("en" to "Ü (Tür) — round your lips like OO, but keep your tongue forward as for EE. U (Tuch) is a normal back OO.", "es" to "Ü (Tür) — redondea los labios como para OO, pero mantén la lengua adelante como para EE. La U (Tuch) es una OO posterior normal.", "fr" to "Ü (Tür) — arrondissez les lèvres comme pour /uː/, mais gardez la langue en avant comme pour /iː/. U (Tuch) est un /uː/ postérieur normal.", "de" to "Ü (Tür) — runde die Lippen wie für OO, aber halte die Zunge vorn wie für EE. U (Tuch) ist ein normales hinteres /uː/."),
        setOf("ʏ", "yː") to mapOf("en" to "Short Ü (dünn) is relaxed and quick. Long Ü (Tür) is held longer with tighter lip rounding.", "es" to "Ü corta (dünn) es relajada y rápida. Ü larga (Tür) se sostiene más, con redondeo de labios más cerrado.", "fr" to "Ü court (dünn) est relâché et rapide. Ü long (Tür) se tient plus longtemps avec un arrondi de lèvres plus serré.", "de" to "Kurzes Ü (dünn) ist entspannt und schnell. Langes Ü (Tür) wird länger gehalten mit fester Lippenrundung."),
        setOf("ø", "oː") to mapOf("en" to "Ö (schön) — round your lips like O, but keep your tongue forward as for E. O (Sohn) is a normal back O.", "es" to "Ö (schön) — redondea los labios como para O, pero mantén la lengua adelante como para E. La O (Sohn) es una O posterior normal.", "fr" to "Ö (schön) — arrondissez les lèvres comme pour /oː/, mais gardez la langue en avant comme pour /e/. O (Sohn) est un /oː/ postérieur normal.", "de" to "Ö (schön) — runde die Lippen wie für O, aber halte die Zunge vorn wie für E. O (Sohn) ist ein normales hinteres O."),
        setOf("œ", "ɔ") to mapOf("en" to "Short Ö (können) is front-rounded. Short O (Sonne) is back-rounded. Move your tongue forward for Ö.", "es" to "Ö corta (können) es anterior redondeada. O corta (Sonne) es posterior redondeada. Mueve la lengua adelante para Ö.", "fr" to "Ö court (können) est antérieur arrondi. O court (Sonne) est postérieur arrondi. Avancez la langue pour Ö.", "de" to "Kurzes Ö (können) ist vorne gerundet. Kurzes O (Sonne) ist hinten gerundet. Zunge für Ö nach vorn bewegen."),
        setOf("ɛː", "eː") to mapOf("en" to "Ä long (Bär) is slightly lower and more open than E long (Beet). Drop your jaw a bit for Ä.", "es" to "Ä larga (Bär) es un poco más baja y abierta que E larga (Beet). Baja un poco la mandíbula para Ä.", "fr" to "Ä long (Bär) est un peu plus bas et plus ouvert que E long (Beet). Baissez un peu la mâchoire pour Ä.", "de" to "Langes Ä (Bär) ist etwas tiefer und offener als langes E (Beet). Kiefer für Ä etwas senken."),
        setOf("ə", "ɐ") to mapOf("en" to "Schwa (bitte) is a silent-E sound. Vocalic R (bitter) is slightly more open, like a softened UH.", "es" to "La schwa (bitte) es un sonido de E muda. La R vocálica (bitter) es algo más abierta, como una UH suavizada.", "fr" to "Le schwa /ə/ (bitte) est un E muet. Le R vocalisé /ɐ/ (bitter) est un peu plus ouvert, comme un /ʌ/ adouci.", "de" to "Schwa (bitte) ist ein stummer E-Laut. Vokalisches R (bitter) ist etwas offener, wie ein weiches /ɐ/."),
        setOf("a", "aː") to mapOf("en" to "Length matters in German! Stadt (short A, quick) vs. Staat (long A, held). Double the duration.", "es" to "¡La duración importa en alemán! Stadt (A corta, rápida) vs. Staat (A larga, sostenida). Duplica la duración.", "fr" to "La durée compte en allemand ! Stadt (A court, rapide) vs. Staat (A long, tenu). Doublez la durée.", "de" to "Länge ist im Deutschen wichtig! Stadt (kurzes A, schnell) vs. Staat (langes A, gehalten). Doppelte Dauer."),
        setOf("eː", "e") to mapOf("en" to "Latin long E is held twice as long as short E. Length contrasts real words — LĒGIT (reads) vs. LEGIT (gathers).", "es" to "La E larga del latín se sostiene el doble que la E corta. La duración distingue palabras reales — LĒGIT (lee) vs. LEGIT (recoge).", "fr" to "Le E long latin se tient deux fois plus longtemps que le E court. La durée distingue des mots réels — LĒGIT (lit) vs. LEGIT (rassemble).", "de" to "Lateinisches langes E wird doppelt so lang gehalten wie kurzes E. Länge unterscheidet echte Wörter — LĒGIT (liest) vs. LEGIT (sammelt)."),
        setOf("iː", "i") to mapOf("en" to "Latin long I is held twice as long as short I. Keep the tongue in the same position — just longer.", "es" to "La I larga del latín se sostiene el doble que la I corta. Mantén la lengua en la misma posición — solo más tiempo.", "fr" to "Le I long latin se tient deux fois plus longtemps que le I court. Gardez la langue au même endroit — juste plus longtemps.", "de" to "Lateinisches langes I wird doppelt so lang gehalten wie kurzes I. Zunge in gleicher Stellung — nur länger."),
        setOf("oː", "o") to mapOf("en" to "Latin long O is held twice as long as short O. Same mouth shape, double duration.", "es" to "La O larga del latín se sostiene el doble que la O corta. Misma forma de la boca, duración duplicada.", "fr" to "Le O long latin se tient deux fois plus longtemps que le O court. Même forme de bouche, durée doublée.", "de" to "Lateinisches langes O wird doppelt so lang gehalten wie kurzes O. Gleiche Mundform, doppelte Dauer."),
        setOf("uː", "u") to mapOf("en" to "Latin long U is held twice as long as short U. Same mouth shape, double duration.", "es" to "La U larga del latín se sostiene el doble que la U corta. Misma forma de la boca, duración duplicada.", "fr" to "Le U long latin se tient deux fois plus longtemps que le U court. Même forme de bouche, durée doublée.", "de" to "Lateinisches langes U wird doppelt so lang gehalten wie kurzes U. Gleiche Mundform, doppelte Dauer."),
        setOf("kʷ", "k") to mapOf("en" to "QU in 'quod' is K with rounded lips — almost 'kw'. K alone has unrounded lips.", "es" to "QU en 'quod' es K con labios redondeados — casi 'kw'. La K sola lleva los labios sin redondear.", "fr" to "QU dans 'quod' est un /k/ avec lèvres arrondies — presque 'kw'. /k/ seul a les lèvres non arrondies.", "de" to "QU in 'quod' ist /k/ mit gerundeten Lippen — fast 'kw'. /k/ allein hat ungerundete Lippen."),
        setOf("ɡʷ", "ɡ") to mapOf("en" to "GU before a vowel (lingua) is G with rounded lips — almost 'gw'. G alone has unrounded lips.", "es" to "GU antes de vocal (lingua) es G con labios redondeados — casi 'gw'. La G sola lleva los labios sin redondear.", "fr" to "GU devant une voyelle (lingua) est un /ɡ/ avec lèvres arrondies — presque 'gw'. /ɡ/ seul a les lèvres non arrondies.", "de" to "GU vor einem Vokal (lingua) ist /ɡ/ mit gerundeten Lippen — fast 'gw'. /ɡ/ allein hat ungerundete Lippen."),
    )

    /**
     * Descriptions tied to the language being practised, keyed by target
     * language and then by phoneme pair. The same sound contrast can belong to
     * more than one language (/ɪ/ vs /iː/ is "bit/beat" in English and
     * "bitten/bieten" in German), so the examples must follow the language the
     * learner is practising or the hint teaches the wrong one. Lookups fall
     * back to [pairDescriptions] when the target has no specific entry.
     */
    private val pairDescriptionsByTarget: Map<String, Map<Set<String>, Map<String, String>>> = mapOf(
        "fr" to mapOf(
            setOf("ʒ", "ʃ") to mapOf("en" to "J (jour) buzzes in your throat. CH (chou) is silent — same mouth position, different voicing.", "es" to "La J (jour) zumba en la garganta. La CH (chou) no tiene voz — misma posición de la boca, distinto uso de la voz.", "fr" to "J (jour) bourdonne dans la gorge. CH (chou) est sourd — même position de bouche, voisement différent.", "de" to "J (jour) summt im Hals. CH (chou) ist stimmlos — gleiche Mundstellung, anderes Stimmverhalten."),
        ),
        "de" to mapOf(
            setOf("ɪ", "iː") to mapOf("en" to "Short I, as in German 'bitten', is relaxed, like English 'bit'. Long I, as in German 'bieten', is tense and held, like English 'beat'.", "es" to "La I corta, como en el alemán 'bitten', es relajada, como la inglesa 'bit'. La I larga, como en el alemán 'bieten', es tensa y sostenida, como la inglesa 'beat'.", "fr" to "Le I court, comme dans l'allemand 'bitten', est relâché, comme l'anglais 'bit'. Le I long, comme dans l'allemand 'bieten', est tendu et tenu, comme l'anglais 'beat'.", "de" to "Kurzes I (bitten) ist entspannt, wie im englischen 'bit'. Langes I (bieten) ist gespannt und gehalten, wie 'beat'."),
            // espeak-ng writes the long A of "Staat" as ɑː; same hint as a/aː.
            setOf("a", "ɑː") to pairDescriptions.getValue(setOf("a", "aː")),
            setOf("ç", "ʃ") to mapOf("en" to "The ICH sound (ich, Kirche) is a soft hiss with the tongue flat near the palate. SCH (Kirsche) rounds the lips and is darker.", "es" to "El sonido de ICH (ich, Kirche) es un soplo suave con la lengua plana cerca del paladar. SCH (Kirsche) redondea los labios y suena más oscuro.", "fr" to "Le son ICH (ich, Kirche) est un souffle doux, langue plate près du palais. SCH (Kirsche) arrondit les lèvres et sonne plus sombre.", "de" to "Der ICH-Laut (ich, Kirche) ist ein weiches Zischen mit flacher Zunge am Gaumen. SCH (Kirsche) rundet die Lippen und klingt dunkler."),
            setOf("ʊ", "uː") to mapOf("en" to "Short U, as in German 'muss', is relaxed like English 'book'. Long U, as in German 'Mus', is tense and held like English 'moose'.", "es" to "La U corta, como en el alemán 'muss', es relajada como la inglesa 'book'. La U larga, como en el alemán 'Mus', es tensa y sostenida como la inglesa 'moose'.", "fr" to "Le U court, comme dans l'allemand 'muss', est relâché comme l'anglais 'book'. Le U long, comme dans l'allemand 'Mus', est tendu et tenu comme l'anglais 'moose'.", "de" to "Kurzes U (muss) ist entspannt wie im englischen 'book'. Langes U (Mus) ist gespannt und gehalten wie 'moose'."),
        ),
        "ru" to mapOf(
            setOf("y", "i") to mapOf("en" to "Ы (быть) is not И (бить). Say EE, then pull your tongue back without rounding your lips.", "es" to "La Ы (быть) no es И (бить). Di una I y lleva la lengua hacia atrás sin redondear los labios.", "fr" to "Ы (быть) n'est pas И (бить). Dites I, puis reculez la langue sans arrondir les lèvres.", "de" to "Ы (быть) ist nicht И (бить). Sprich ein I und zieh die Zunge zurück, ohne die Lippen zu runden."),
            setOf("ʌ", "o") to mapOf("en" to "Unstressed О (молоко) weakens to a short A-like sound. Only the stressed О is a full O.", "es" to "La О átona (молоко) se debilita y suena casi como una A breve. Solo la О tónica es una O plena.", "fr" to "Le О non accentué (молоко) s'affaiblit en un son proche d'un A bref. Seul le О accentué est un vrai O.", "de" to "Unbetontes О (молоко) wird zu einem kurzen, A-ähnlichen Laut. Nur das betonte О ist ein volles O."),
            setOf("ʃ", "ɕ") to mapOf("en" to "Ш (шит) is hard: tongue pulled back, a dark hush. Щ (щит) is soft: tongue raised to the palate, a brighter and longer hush.", "es" to "La Ш (шит) es dura: lengua retraída y sonido oscuro. La Щ (щит) es blanda: lengua elevada hacia el paladar, sonido más agudo y más largo.", "fr" to "Ш (шит) est dur : langue reculée, son sombre. Щ (щит) est mou : langue relevée vers le palais, son plus clair et plus long.", "de" to "Ш (шит) ist hart: Zunge zurückgezogen, dunkler Zischlaut. Щ (щит) ist weich: Zunge zum Gaumen gehoben, hellerer und längerer Zischlaut."),
            setOf("ʑ", "z") to mapOf("en" to "Soft З (взять) raises the middle of the tongue to the palate. Hard З (зал) keeps the tongue flat.", "es" to "La З blanda (взять) eleva el centro de la lengua hacia el paladar. La З dura (зал) mantiene la lengua plana.", "fr" to "Le З mou (взять) relève le milieu de la langue vers le palais. Le З dur (зал) garde la langue plate.", "de" to "Weiches З (взять) hebt die Zungenmitte zum Gaumen. Hartes З (зал) lässt die Zunge flach."),
        ),
        "la" to mapOf(
            setOf("aː", "a") to mapOf("en" to "Latin long A is held twice as long as short A. Length changes meaning: MĀLUM (apple) vs. MALUM (bad).", "es" to "La A larga del latín se sostiene el doble que la A corta. La duración cambia el significado: MĀLUM (manzana) vs. MALUM (malo).", "fr" to "Le A long latin se tient deux fois plus longtemps que le A court. La durée change le sens : MĀLUM (pomme) vs. MALUM (mauvais).", "de" to "Lateinisches langes A wird doppelt so lang gehalten wie kurzes A. Länge verändert die Bedeutung: MĀLUM (Apfel) vs. MALUM (schlecht)."),
        ),
    )

    /** Maps a target-language code to the bucket used by [pairDescriptionsByTarget]. */
    private fun targetLanguageKey(language: String): String = when (val base = baseLanguage(language)) {
        "fr", "es", "de", "ru", "la" -> base
        else -> "en"
    }

    /**
     * Pairs in the generic [pairDescriptions] table that actually belong to one
     * language, because their examples are written for it (the TS/S hint talks
     * about German "Zeit"). Without this, an English learner whose plural /ts/
     * got recognised as /s/ would be taught German. A pair absent from this map
     * is genuinely shared and falls back to [pairDescriptions].
     */
    private val pairLanguageRestriction: Map<Set<String>, Set<String>> = mapOf(
        setOf("ts", "s") to setOf("de"),
        setOf("ts", "z") to setOf("de"),
        setOf("pf", "f") to setOf("de"),
        setOf("x", "ç") to setOf("de"),
        setOf("ʁ", "ɐ") to setOf("de"),
        setOf("yː", "uː") to setOf("de"),
        setOf("ʏ", "yː") to setOf("de"),
        setOf("ø", "oː") to setOf("de"),
        setOf("œ", "ɔ") to setOf("de"),
        setOf("ɛː", "eː") to setOf("de"),
        setOf("ə", "ɐ") to setOf("de"),
        setOf("a", "aː") to setOf("de"),
        setOf("y", "u") to setOf("fr"),
        setOf("ø", "e") to setOf("fr"),
        setOf("œ", "ɛ") to setOf("fr"),
        setOf("ø", "œ") to setOf("fr"),
        setOf("ɥ", "w") to setOf("fr"),
        setOf("ɥ", "y") to setOf("fr"),
        setOf("ɔ̃", "ɑ̃") to setOf("fr"),
        setOf("ɛ̃", "ɑ̃") to setOf("fr"),
        setOf("ɛ̃", "œ̃") to setOf("fr"),
        setOf("ɲ", "n") to setOf("fr"),
        setOf("ʁ", "r") to setOf("fr", "de"),
        setOf("ʁ", "ʀ") to setOf("fr", "de"),
        setOf("β", "b") to setOf("es"),
        setOf("ð", "d") to setOf("es"),
        setOf("ɣ", "ɡ") to setOf("es"),
        setOf("ɣ", "g") to setOf("es"),
        setOf("ɾ", "r") to setOf("es"),
        setOf("r", "ɹ") to setOf("es"),
        setOf("ɾ", "ɹ") to setOf("es"),
        setOf("ʝ", "ʎ") to setOf("es"),
        setOf("ʎ", "l") to setOf("es"),
        setOf("x", "h") to setOf("es"),
        setOf("x", "k") to setOf("es"),
        setOf("j", "ʝ") to setOf("es"),
        setOf("w", "v") to setOf("la"),
        setOf("eː", "e") to setOf("la"),
        setOf("iː", "i") to setOf("la"),
        setOf("oː", "o") to setOf("la"),
        setOf("uː", "u") to setOf("la"),
        setOf("kʷ", "k") to setOf("la"),
        setOf("ɡʷ", "ɡ") to setOf("la"),
    )

    /**
     * Returns a human-readable description if two phonemes form a known minimal
     * pair, written in [uiLanguage] for learners of [targetLanguage].
     */
    fun getMinimalPairDescription(
        expected: String,
        actual: String,
        uiLanguage: String = "en",
        targetLanguage: String = "en",
    ): String? {
        val pair = setOf(expected, actual)
        val targetKey = targetLanguageKey(targetLanguage)
        val targetSpecific = pairDescriptionsByTarget[targetKey]?.get(pair)
        if (targetSpecific != null) return targetSpecific[uiLanguage] ?: targetSpecific["en"]

        val restrictedTo = pairLanguageRestriction[pair]
        if (restrictedTo != null && targetKey !in restrictedTo) return null

        val entry = pairDescriptions[pair] ?: return null
        return entry[uiLanguage] ?: entry["en"]
    }

    /**
     * Scores [actual] against the reference [expected], accepting every
     * pronunciation variant a word may have.
     *
     * [expectedWordVariants] is parallel to the space-separated source words of
     * [expected] (the shape [com.glosso.engine.g2p.Pronunciations.wordVariants]
     * returns); each entry lists the IPA readings the scorer may treat as correct,
     * the first being [expected] itself. When a word has more than one reading the
     * scorer picks the combination that best matches what was said, so a British
     * /t/ in "thirty" is not charged as a missing American flap /d/.
     *
     * Every accepted reading is scored and the best one wins, so a dialect variant
     * can only ever *raise* the score, never lower it: a form the learner did not
     * say is never required. See [nonRhoticReadings] for the English counterpart
     * derived from a rhotic reference.
     *
     * An empty or misaligned list keeps the single-reference options (the
     * canonical reading plus the derived dialect readings).
     */
    fun calculateScoringResult(
        text: String,
        expected: String,
        actual: String,
        language: String = "en",
        actualConfidence: List<Float> = emptyList(),
        expectedWordVariants: List<List<String>> = emptyList(),
    ): ScoringResult = calculateScoringResult(
        text = text,
        expected = expected,
        actual = actual,
        language = language,
        actualConfidence = actualConfidence,
        expectedWordVariants = expectedWordVariants,
        dialectWidening = true,
    )

    /**
     * As [calculateScoringResult] but with the derived dialect readings toggleable.
     * `internal` only so the measurement probes can reproduce the deployed
     * behaviour (variants without the coda-/ɹ/ widening); production always asks
     * for the widening.
     */
    internal fun calculateScoringResult(
        text: String,
        expected: String,
        actual: String,
        language: String = "en",
        actualConfidence: List<Float> = emptyList(),
        expectedWordVariants: List<List<String>> = emptyList(),
        dialectWidening: Boolean,
        distinctVariantCap: Boolean = DISTINCT_VARIANT_CAP_DEFAULT,
    ): ScoringResult {
        val profile = profileFor(language)
        // No-ops unless the language has symbol folds (Russian).
        val foldedExpected = profile.fold(expected)
        val foldedVariants = if (profile.symbolFolds.isEmpty()) {
            expectedWordVariants
        } else {
            expectedWordVariants.map { readings -> readings.map(profile::fold) }
        }
        val actualList = getNormalizedPhoneList(profile.fold(actual))
        // Confidence is only usable when it lines up one-to-one with the parsed
        // phones; anything else is dropped rather than mis-attributed.
        val confidence = actualConfidence.takeIf { it.size == actualList.size } ?: emptyList()
        val similarity = SimilarityCache(profile)

        // Every accepted reading is scored once. Two readings that differ only in
        // what normalization strips (a stress mark) are the same phones and would
        // score identically, so the repeat is skipped.
        val scored = HashSet<List<List<String>>>()
        var best: ScoringResult? = null
        fun consider(reference: List<List<String>>) {
            if (!scored.add(reference)) return
            val candidate = scoreReference(text, reference, actualList, language, profile, confidence, similarity)
            val current = best
            if (current == null || candidate.score > current.score ||
                (candidate.score == current.score && missedCount(candidate) < missedCount(current))
            ) {
                best = candidate
            }
        }

        consider(getNormalizedPhoneWords(foldedExpected))
        forEachVariantCombination(foldedExpected, foldedVariants, distinctVariantCap, ::consider)
        if (dialectWidening) nonRhoticReadings(expected, language).forEach { consider(getNormalizedPhoneWords(it)) }
        elidedFunctionWordReadings(expected, text, language).forEach(::consider)
        return checkNotNull(best)
    }

    private fun missedCount(result: ScoringResult): Int =
        result.alignment.count { it.status == MatchStatus.MISSED }

    /**
     * Scores one reference with no variants and no dialect widening. `internal`
     * only so the measurement probes can report a true single-reference baseline;
     * production always goes through [calculateScoringResult].
     */
    internal fun scoreSingleReference(
        text: String,
        expected: String,
        actual: String,
        language: String = "en",
    ): ScoringResult {
        val profile = profileFor(language)
        return scoreReference(
            text,
            getNormalizedPhoneWords(profile.fold(expected)),
            getNormalizedPhoneList(profile.fold(actual)),
            language,
            profile,
            emptyList(),
            SimilarityCache(profile),
        )
    }

    /**
     * British (non-rhotic) readings of a rhotic reference, as additional accepted
     * readings that can only raise the score.
     *
     * CMUdict is an American dictionary, so the shipped reference writes a coda
     * /ɹ/ ("near" nˈɪɹ, "water" wˈɔtɚ). The product is British, and a non-rhotic
     * learner does not say that /ɹ/; when the American reading was the only one,
     * the missing coda was charged as MISSED (reported: `/ɹ/` at 0 for "near",
     * 92/100 for a sentence read correctly). Adding the British reading as one
     * more accepted reference lets that learner pass without ever requiring it,
     * while an American learner, who does say the /ɹ/, still matches the canonical
     * reading. No reading here is injected into what the speaker must produce.
     *
     * The NEAR vowel before the lost /ɹ/ is heard as ɪ or i depending on the
     * accent, so both onsets are offered; neither is a learner error. Only English
     * has this contrast, so other languages get no extra readings.
     */
    private fun nonRhoticReadings(expected: String, language: String): List<String> {
        if (!language.lowercase().startsWith("en")) return emptyList()
        val words = expected.trim().split(WHITESPACE).filter { it.isNotBlank() }
        val canonical = words.joinToString(" ")
        val readings = ArrayList<String>(2)
        for (onset in NON_RHOTIC_NEAR_ONSETS) {
            val rewritten = words.joinToString(" ") { nonRhoticWord(it, onset) }
            if (rewritten != canonical && readings.none { it == rewritten }) readings.add(rewritten)
        }
        return readings
    }

    /** The NEAR onsets [nonRhoticWord] accepts, for the accent systems. */
    internal val nonRhoticNearOnsets: List<String> get() = NON_RHOTIC_NEAR_ONSETS

    /**
     * One word of a rhotic reference with its coda /ɹ/ folded away. The accent
     * systems apply it to every word of a sentence at once, so a non-rhotic
     * system never keeps half of the /ɹ/.
     */
    internal fun nonRhoticWord(word: String, nearOnset: String): String {
        if ('ɹ' !in word && 'ɝ' !in word && 'ɚ' !in word) return word
        // ɝ/ɚ bundle their /ɹ/: keep it when a vowel follows (intervocalic onset),
        // drop it otherwise. ɝ is already a long central vowel either way.
        var rewritten = word
        rewritten = ER_ONSET_RHO.replace(rewritten, "ɜːr")
        rewritten = ER_RHO.replace(rewritten, "ɜː")
        rewritten = SCHWA_R_ONSET_RHO.replace(rewritten, "ər")
        rewritten = SCHWA_R_RHO.replace(rewritten, "ə")
        rewritten = NEAR_RHO.replace(rewritten, "${nearOnset}ə")
        for ((pattern, reflex) in NON_RHOTIC_VOWEL_RHO) {
            rewritten = pattern.replace(rewritten, reflex)
        }
        // Drop only a coda /ɹ/ that the folds above did not already consume.
        return CODA_RHO.replace(rewritten, "")
    }

    /**
     * Readings with a weak function word elided, as additional accepted readings
     * that can only raise the score.
     *
     * Citation-form phonemization writes the weak words a fluent speaker drops in
     * connected speech ("see you later" -> "see ya later", "most of his" ->
     * "mosta his", "well, I think" -> "I think"). Scoring only the full form
     * charged that omission as a mispronunciation, which is the inversion the
     * engine audit documented: a native who reduces scored *below* a learner who
     * over-articulates. The elided form is offered as one more reading, so it can
     * never be required, and only words of the closed class in
     * [EN_FUNCTION_WORD_TEXT] / [EN_FUNCTION_WORDS] are dropped - a content word
     * ("cat", "think") is untouched and its omission stays a real error.
     *
     * One weak word is elided per reading, never all of them at once: a native
     * reduction is local ("see ya later"), and a reading that dropped every weak
     * word would pass "I have a cat" said as just "kæt" (reported in review of
     * #104). The learner still has to produce the weak words they keep.
     *
     * The source [text] decides which words belong to the class whenever it lines
     * up word-for-word with [expected], so homophones ("sum"/"some") are not
     * misread; otherwise the reference IPA is used (the measurement probes only
     * have the phonemization). Only English has the curated class.
     *
     * The elided word keeps its slot as an empty phone word, so every per-word
     * score still lands on its own sentence word. Dropping the slot shifted the
     * scores after it onto the previous word ("the gate later" reported gate's
     * score on "the" and lost "later").
     */
    private fun elidedFunctionWordReadings(
        expected: String,
        text: String,
        language: String,
    ): List<List<List<String>>> {
        if (!language.lowercase().startsWith("en")) return emptyList()
        val words = expected.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (words.size <= 1) return emptyList()

        val textWords = text.lowercase().split(WHITESPACE)
            .map { it.trim { c -> !c.isLetter() && c != '\'' } }
        val byText = textWords.size == words.size
        val weak = BooleanArray(words.size) { i ->
            if (byText) textWords[i] in EN_FUNCTION_WORD_TEXT
            else normalize(words[i]) in EN_FUNCTION_WORDS
        }
        if (!weak.any { it }) return emptyList()

        val parsed = getNormalizedPhoneWords(expected)
        val keepsSlots = parsed.size == words.size
        val readings = ArrayList<List<List<String>>>(words.size)
        for (i in words.indices) {
            if (!weak[i]) continue
            readings.add(
                if (keepsSlots) {
                    parsed.mapIndexed { j, phones -> if (j == i) emptyList() else phones }
                } else {
                    getNormalizedPhoneWords(words.filterIndexed { j, _ -> j != i }.joinToString(" "))
                },
            )
        }
        return readings
    }

    /**
     * Calls [action] with every reference generated by swapping in each accepted
     * pronunciation for the words that have one, already parsed into phone words.
     * Nothing is generated when there is nothing to vary, or when there are too
     * many combinations to be worth it (the canonical reference is then scored
     * alone - never an error, just no variant forgiveness).
     *
     * A word's readings that normalize to the same phones ("ˈɪz" and "ɪz") are
     * expanded once: they would produce identical references. Whether they still
     * count towards [MAX_VARIANT_COMBINATIONS] is [distinctCap]; the combinations
     * come out in the same order either way, so the best reading and its tie-break
     * do not depend on it.
     */
    private fun forEachVariantCombination(
        expected: String,
        wordVariants: List<List<String>>,
        distinctCap: Boolean,
        action: (List<List<String>>) -> Unit,
    ) {
        if (wordVariants.isEmpty()) return
        val words = expected.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (words.size != wordVariants.size) return

        // One entry per source word: its distinct readings, each already parsed.
        // A reading can parse to zero or several phone words, exactly as it would
        // inside the joined sentence.
        val options = words.indices.map { i ->
            val variants = wordVariants[i]
            val readings = if (variants.size <= 1) listOf(variants.firstOrNull() ?: words[i]) else variants
            readings.map { getNormalizedPhoneWords(it) }.distinct()
        }
        var rawTotal = 1L
        var distinctTotal = 1L
        for (i in words.indices) {
            if (wordVariants[i].size > 1) {
                rawTotal = (rawTotal * wordVariants[i].size).coerceAtMost(MAX_VARIANT_COMBINATIONS + 1)
                distinctTotal = (distinctTotal * options[i].size).coerceAtMost(MAX_VARIANT_COMBINATIONS + 1)
            }
        }
        if (rawTotal <= 1L) return
        if ((if (distinctCap) distinctTotal else rawTotal) > MAX_VARIANT_COMBINATIONS) return

        val choices = IntArray(options.size)
        while (true) {
            val resolved = ArrayList<List<String>>(words.size)
            for (i in options.indices) resolved.addAll(options[i][choices[i]])
            action(resolved)
            var k = choices.size - 1
            while (k >= 0) {
                choices[k]++
                if (choices[k] < options[k].size) break
                choices[k] = 0
                k--
            }
            if (k < 0) break
        }
    }

    private fun scoreReference(
        text: String,
        expectedWords: List<List<String>>,
        actualList: List<String>,
        language: String,
        profile: ScoringProfile,
        confidence: List<Float>,
        similarity: SimilarityCache,
    ): ScoringResult {
        val expectedList = expectedWords.flatten()

        EngineLog.d(TAG) { "=== calculateScoringResult [lang=$language text=$text] ===" }
        EngineLog.d(TAG) { "  expected phones  : $expectedList" }
        EngineLog.d(TAG) { "  actual   phones  : $actualList" }

        if (expectedList.isEmpty()) {
            return if (actualList.isEmpty()) {
                ScoringResult(100, "", "", level = MasteryLevel.PERFECT)
            } else {
                ScoringResult(0, "", "")
            }
        }

        val n = expectedList.size
        val m = actualList.size

        // Which reference phones end a word. A final plosive is often unreleased
        // and never emitted, so the aligner forgives it (see [FINAL_STOP_DELETION]).
        val wordOfPhone = IntArray(n)
        val wordStart = IntArray(expectedWords.size)
        val wordFinal = BooleanArray(n)
        run {
            var idx = 0
            for (w in expectedWords.indices) {
                wordStart[w] = idx
                repeat(expectedWords[w].size) { wordOfPhone[idx++] = w }
                if (expectedWords[w].isNotEmpty()) wordFinal[idx - 1] = true
            }
        }

        // A coda /ɹ/ is silent in non-rhotic English, so the model (and a British
        // speaker) often does not produce it at all. Forgiving that deletion is the
        // counterpart of the derived non-rhotic readings; it can only raise a score.
        val codaR = BooleanArray(n) { i ->
            expectedList[i] == "ɹ" &&
                (i + 1 >= n || !PhoneticInventory.isVowel(expectedList[i + 1]))
        }

        // A word-final plosive is only forgiven when the rest of its word landed
        // cleanly: "mat" said as "mæ" is an unreleased t, but "cat" said as "shoe"
        // is just wrong, and nothing should be excused there. The first pass finds
        // which words are intact enough to excuse the missing release.
        // Similarity of every reference/produced pair, computed once and shared by
        // both alignment passes and their tracebacks.
        val sim = Array(n) { i -> DoubleArray(m) { j -> similarity.of(expectedList[i], actualList[j]) } }

        val firstPass = alignSequence(expectedList, actualList, sim, confidence, codaR)
        // The alignment now also carries one entry per inserted phone, so index it
        // by reference position explicitly instead of assuming alignment[i] is
        // reference phone i.
        val firstPassRef = firstPass.matches.filter { it.expected != "-" }
        val forgivableFinal = BooleanArray(n)
        for (i in 0 until n) {
            if (!wordFinal[i] || !isPlosive(expectedList[i])) continue
            val w = wordOfPhone[i]
            val intact = (wordStart[w] until i).all { firstPassRef[it].status == MatchStatus.PERFECT }
            if (intact) forgivableFinal[i] = true
        }
        val forgivableDeletion = BooleanArray(n) { codaR[it] || forgivableFinal[it] }

        val result = alignSequence(expectedList, actualList, sim, confidence, forgivableDeletion)

        // Insertions used to be dropped by the traceback, which made an added
        // phone invisible: it did not taint the word, the accuracy or mastery.
        // Now every produced phone has an entry, so attribute each one to the
        // reference word it belongs to. An inserted phone goes to the word of the
        // next reference phone (an epenthetic vowel belongs to the word it
        // precedes), or the last word when it trails the sentence.
        val wordForMatch = IntArray(result.matches.size)
        run {
            var ref = 0
            for (k in result.matches.indices) {
                if (result.matches[k].expected != "-") {
                    wordForMatch[k] = wordOfPhone[ref++]
                } else {
                    wordForMatch[k] = wordOfPhone.getOrElse(ref) { wordOfPhone.last() }
                }
            }
        }

        // Carry the word each phone belongs to on the match itself, so anything
        // downstream (the error rules, the UI) can say exactly which word failed
        // without re-deriving the boundaries.
        val alignment = result.matches.mapIndexed { k, match ->
            match.copy(wordIndex = wordForMatch[k])
        }

        // Overall score: normalised edit distance over phonemes. Substitutions,
        // dropped phonemes and extra phonemes all reduce it, and the length of
        // what the speaker actually produced matters.
        val denominator = max(n, m).toDouble().coerceAtLeast(1.0)
        val score = ((1.0 - result.cost / denominator) * 100)
            .roundToInt()
            .coerceIn(0, 100)

        val hardFlags = hardErrorFlags(alignment, profile)
        val creditsByWord = Array(expectedWords.size) { mutableListOf<Double>() }
        val wordPerfect = BooleanArray(expectedWords.size) { true }
        val wordHardError = BooleanArray(expectedWords.size)
        alignment.forEachIndexed { k, match ->
            val w = wordForMatch[k]
            creditsByWord[w].add(creditFor(match, profile))
            if (match.status != MatchStatus.PERFECT) wordPerfect[w] = false
            if (hardFlags[k]) wordHardError[w] = true
        }
        val wordScores = creditsByWord.mapIndexed { w, credits ->
            // A slot with no reference phones is an accepted elision of a weak
            // word: nothing was required there, so it counts as mastered, not
            // flawless.
            val elided = expectedWords[w].isEmpty() && credits.isEmpty()
            val wordScore = when {
                elided -> 100
                credits.isEmpty() -> 0
                else -> (credits.average() * 100).roundToInt()
            }
            WordScore(
                index = w,
                score = wordScore,
                phoneCount = expectedWords[w].size,
                level = ScoringConfig.levelFor(
                    wordScore,
                    perfect = credits.isNotEmpty() && wordPerfect[w],
                    hardError = wordHardError[w],
                )
            )
        }

        // Two independent axes so the feedback can say *why* it failed: bad
        // sounds versus not saying the whole sentence. Completeness counts only
        // reference phones actually produced; accuracy judges every produced
        // phone, including the ones with no reference partner, so an extra word
        // ("cat dog" for "cat") drags it down instead of hiding as segmentation.
        val matched = alignment.filter { it.expected != "-" && it.actual != "-" }
        val insertions = alignment.count { it.expected == "-" }
        val completeness = (matched.size.toDouble() / n * 100)
            .roundToInt()
            .coerceIn(0, 100)
        val accuracy = if (matched.isEmpty() && insertions == 0) {
            0
        } else {
            val credit = matched.sumOf { creditFor(it, profile) }
            (credit / (matched.size + insertions) * 100)
                .roundToInt()
                .coerceIn(0, 100)
        }

        // A flawless take: every reference sound matched exactly and nothing was
        // added. With insertions in the alignment an added phone is a MISSED
        // entry, so it already fails the all-perfect test; the count check keeps
        // a net-zero add-and-drop from slipping through.
        val perfect = alignment.isNotEmpty() &&
            n == m &&
            alignment.all { it.status == MatchStatus.PERFECT }

        val isMastery = ScoringConfig.qualifiesForMastery(
            score = score,
            completeness = completeness,
            wordScores = wordScores.map { it.score },
            substitutions = alignment.indices.count {
                hardFlags[it] && alignment[it].expected != "-" && alignment[it].actual != "-"
            },
            droppedOrAdded = alignment.indices.count {
                hardFlags[it] && (alignment[it].expected == "-" || alignment[it].actual == "-")
            },
            referencePhones = n,
        )

        val normExpected = expectedList.joinToString("")
            .replace(STRESS_MARKS, "")
        val normActual = actualList.joinToString("")
            .replace(STRESS_MARKS, "")

        return ScoringResult(
            score = score,
            normalizedExpected = normExpected,
            normalizedActual = normActual,
            alignment = alignment,
            accuracy = accuracy,
            completeness = completeness,
            words = wordScores,
            isMastery = isMastery,
            level = ScoringConfig.sentenceLevel(score, isMastery, perfect)
        )
    }

    /** Credit earned by a single aligned reference phone, in 0..1. */
    private fun creditFor(match: PhonemeMatch, profile: ScoringProfile): Double = when (match.status) {
        MatchStatus.PERFECT -> 1.0
        // A CLOSE match against a gap is the forgiven word-final plosive: it
        // earns back exactly the penalty that was not charged.
        MatchStatus.CLOSE -> if (match.actual == "-") {
            1.0 - FINAL_STOP_DELETION
        } else {
            getSimilarity(match.expected, match.actual, profile).coerceIn(0.0, 1.0)
        }
        MatchStatus.MISSED -> 0.0
    }

    /**
     * One flag per alignment entry: true when the learner genuinely got a sound
     * wrong. A substituted or dropped phoneme is a hard error; a plain allophone
     * (documented in [TOLERATED_ALLOPHONES]) and a forgiven word-final plosive are
     * not. An inserted phone is a hard error unless it is a nearby reference phone
     * heard as two (see [insertionIsSplit]), which is the segmentation artefact
     * the insertion cost was always meant to absorb.
     */
    private fun hardErrorFlags(
        alignment: List<PhonemeMatch>,
        profile: ScoringProfile,
    ): BooleanArray {
        val hard = BooleanArray(alignment.size)
        for (k in alignment.indices) {
            val match = alignment[k]
            hard[k] = when {
                match.expected == "-" -> !insertionIsSplit(alignment, k, profile)
                match.status == MatchStatus.PERFECT -> false
                match.status == MatchStatus.MISSED -> true
                // CLOSE against a gap is the forgiven word-final plosive.
                match.actual == "-" -> false
                else -> setOf(match.expected, match.actual) !in TOLERATED_ALLOPHONES
            }
        }
        return hard
    }

    /**
     * True when an inserted phone merely doubles a neighbouring reference phone -
     * the acoustic model hearing one long sound as two ("brother is" decoded with
     * a stray linking ɹ next to the existing ɹ). A genuine epenthetic vowel
     * ("eschool" for "school") is not similar to the cluster it breaks up, so it
     * stays a hard error.
     */
    private fun insertionIsSplit(
        alignment: List<PhonemeMatch>,
        index: Int,
        profile: ScoringProfile,
    ): Boolean {
        val phone = alignment[index].actual
        val neighbours = listOfNotNull(
            alignment.getOrNull(index - 1)?.takeIf { it.expected != "-" }?.expected,
            alignment.getOrNull(index + 1)?.takeIf { it.expected != "-" }?.expected,
        )
        return neighbours.any { getSimilarity(it, phone, profile) >= PERFECT_THRESHOLD }
    }

    /** Alignment plus the total edit cost it was derived from. */
    data class SequenceAlignment(
        val matches: List<PhonemeMatch>,
        val cost: Double
    )

    /**
     * Order-sensitive alignment of the reference phones against the produced
     * phones (Needleman-Wunsch over phonemes).
     *
     * The previous implementation searched the whole produced list once per
     * reference phoneme, which made word order irrelevant, let one produced
     * phoneme satisfy unlimited reference phonemes, and never penalised extra
     * sounds - so "tæk" and "kætkæt" both scored a perfect 100 against "kæt".
     * Alignment with gap costs fixes all three.
     *
     * Returns reference entries in order (one per reference phoneme, so letter
     * and word feedback keeps its stable contract) plus one `expected = "-"` entry
     * for every inserted phone, so an added sound is not invisible to the score,
     * the word result or the hard-error gate.
     */
    private fun alignSequence(
        expected: List<String>,
        actual: List<String>,
        sim: Array<DoubleArray>,
        actualConfidence: List<Float> = emptyList(),
        forgivableDeletion: BooleanArray? = null,
    ): SequenceAlignment {
        val n = expected.size
        val m = actual.size
        val confidence = actualConfidence.takeIf { it.size == m } ?: emptyList()
        val dp = Array(n + 1) { DoubleArray(m + 1) }

        for (i in 1..n) {
            dp[i][0] = dp[i - 1][0] + deletionCost(forgivableDeletion?.get(i - 1) == true)
        }
        for (j in 1..m) {
            dp[0][j] = dp[0][j - 1] + insertionCost(confidence.getOrNull(j - 1), actual[j - 1], null, 0.0)
        }

        for (i in 1..n) {
            val deletion = deletionCost(forgivableDeletion?.get(i - 1) == true)
            val row = sim[i - 1]
            for (j in 1..m) {
                val conf = confidence.getOrNull(j - 1)
                val pairSim = row[j - 1]
                dp[i][j] = minOf(
                    dp[i - 1][j - 1] + substitutionCost(pairSim),
                    dp[i - 1][j] + deletion,
                    dp[i][j - 1] + insertionCost(conf, actual[j - 1], expected[i - 1], pairSim),
                )
            }
        }

        val alignment = ArrayList<PhonemeMatch>(n)
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                val pairSim = sim[i - 1][j - 1]
                if (nearly(dp[i][j], dp[i - 1][j - 1] + substitutionCost(pairSim))) {
                    alignment.add(PhonemeMatch(expected[i - 1], actual[j - 1], statusFor(pairSim)))
                    i--
                    j--
                    continue
                }
            }
            if (i > 0) {
                val deletion = deletionCost(forgivableDeletion?.get(i - 1) == true)
                if (j == 0 || nearly(dp[i][j], dp[i - 1][j] + deletion)) {
                    // A forgiven deletion (an unreleased final stop, a non-rhotic
                    // coda /ɹ/) is reported as CLOSE: not a destroyed sound, but not
                    // a clean match either. It still earns its partial credit.
                    val status = if (deletion < GAP_DELETION) MatchStatus.CLOSE else MatchStatus.MISSED
                    alignment.add(PhonemeMatch(expected[i - 1], "-", status))
                    i--
                    continue
                }
            }
            // Insertion: an extra produced phoneme with no reference partner. It
            // gets its own entry (expected "-") so it is visible to word
            // perfection, accuracy and the hard-error gate instead of vanishing
            // into the edit cost.
            alignment.add(PhonemeMatch("-", actual[j - 1], MatchStatus.MISSED))
            j--
        }
        alignment.reverse()

        return SequenceAlignment(alignment, dp[n][m])
    }

    private fun nearly(a: Double, b: Double): Boolean = abs(a - b) < EPSILON

    /** Cost of a reference phone the speaker never produced. */
    private fun deletionCost(forgivable: Boolean): Double =
        if (forgivable) FINAL_STOP_DELETION else GAP_DELETION

    /**
     * Cost of an extra produced phone the reference does not contain.
     *
     * A phone the model emits as a lengthening of the reference phone right before
     * it is segmentation noise, not a sound the learner added: the geminate ɹ of a
     * linking "brother is", the held stop, or the glide the model writes out of a
     * vowel ("to are" -> `u w ɑ`). Those cost nothing. An unsure extra phone gets a
     * bounded rebate (see [UNSURE_INSERTION_FLOOR]); anything else pays in full, so
     * a repeated utterance or an epenthetic vowel ("eschool" for "school") is still
     * charged.
     */
    private fun insertionCost(
        confidence: Float?,
        phone: String,
        before: String?,
        similarityToBefore: Double,
    ): Double {
        val doublesPrevious = before != null && similarityToBefore >= PERFECT_THRESHOLD
        val glideOfPrevious = (phone == "w" && before in ROUNDED_GLIDE_SOURCES) ||
            (phone == "j" && before in FRONT_GLIDE_SOURCES)
        if (doublesPrevious || glideOfPrevious) return 0.0
        if (confidence == null) return GAP_INSERTION
        val trusted = confidence.coerceIn(0f, 1f).toDouble()
        return GAP_INSERTION * (UNSURE_INSERTION_FLOOR + (1.0 - UNSURE_INSERTION_FLOOR) * trusted)
    }

    /** Vowels whose offglide the model may write out as a separate w / j. */
    private val ROUNDED_GLIDE_SOURCES = setOf("u", "ʊ", "o")
    private val FRONT_GLIDE_SOURCES = setOf("i", "ɪ")

    /**
     * Cost of substituting a reference phoneme with a produced one, from their
     * similarity. Confidence deliberately does not scale this: a sound the
     * learner mispronounced stays mispronounced no matter how unsure the acoustic
     * model was.
     */
    private fun substitutionCost(sim: Double): Double =
        if (sim >= PERFECT_THRESHOLD) 0.0 else 1.0 - sim.coerceIn(0.0, 1.0)

    private fun statusFor(sim: Double): MatchStatus = when {
        sim >= PERFECT_THRESHOLD -> MatchStatus.PERFECT
        sim >= CLOSE_THRESHOLD -> MatchStatus.CLOSE
        else -> MatchStatus.MISSED
    }

    /**
     * Memoized [getSimilarity] for one scoring call. A call scores the same
     * produced phones against many references, and the phone inventory is small,
     * so each pair is resolved once instead of once per DP cell. Not shared across
     * calls, so it needs no synchronization.
     */
    private class SimilarityCache(private val profile: ScoringProfile) {
        private val cache = HashMap<String, HashMap<String, Double>>()

        fun of(expected: String, actual: String): Double =
            cache.getOrPut(expected) { HashMap() }.getOrPut(actual) { getSimilarity(expected, actual, profile) }
    }

    private fun isPlosive(phone: String): Boolean = phone.removeSuffix("ː") in PLOSIVES

    fun generateLetterFeedback(text: String, expectedIpa: String, phonemeAlignment: List<PhonemeMatch>, language: String = "en"): List<LetterFeedbackInfo> {
        val normalizedText = text.lowercase()
        val expectedPhonemes = getNormalizedPhoneList(profileFor(language).fold(expectedIpa))

        val n = normalizedText.length
        val m = expectedPhonemes.size

        if (m == 0) {
            return text.map { LetterFeedbackInfo(it.toString(), MatchStatus.PERFECT) }
        }

        // DP for Text to Phoneme alignment
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i * 10
        for (j in 0..m) dp[0][j] = j * 10

        for (i in 1..n) {
            for (j in 1..m) {
                val cost = getG2PCost(normalizedText[i-1], expectedPhonemes[j-1], language)
                dp[i][j] = minOf(
                    dp[i-1][j] + 10, // Skip char
                    dp[i][j-1] + 10, // Skip phoneme
                    dp[i-1][j-1] + cost // Match
                )
            }
        }

        // Backtrack
        val charToPhonemeIndex = mutableMapOf<Int, Int>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val cost = getG2PCost(normalizedText[i-1], expectedPhonemes[j-1], language)
            if (dp[i][j] == dp[i-1][j-1] + cost) {
                charToPhonemeIndex[i-1] = j-1
                i--; j--
            } else if (dp[i][j] == dp[i-1][j] + 10) {
                i--
            } else {
                j--
            }
        }

        // Map status from phonemeAlignment (which contains EXPECTED phonemes)
        val expectedStatus = phonemeAlignment.filter { it.expected != "-" }.map { it.status }
        
        val result = mutableListOf<LetterFeedbackInfo>()
        val nonPronounceable = setOf('.', ',', '!', '?', ';', ':', '(', ')', '-', ' ', '\'', '"')
        
        for (idx in 0 until text.length) {
            val char = text[idx]
            if (char in nonPronounceable) {
                result.add(LetterFeedbackInfo(char.toString(), MatchStatus.PERFECT))
                continue
            }
            
            val phonemeIdx = charToPhonemeIndex[idx]
            val status = if (phonemeIdx != null && phonemeIdx < expectedStatus.size) {
                expectedStatus[phonemeIdx]
            } else {
                // If a letter didn't map to a phoneme (backtrack skipped it), 
                // look for neighbors
                val prevStatus = charToPhonemeIndex[idx - 1]?.let { if (it < expectedStatus.size) expectedStatus[it] else null }
                val nextStatus = charToPhonemeIndex[idx + 1]?.let { if (it < expectedStatus.size) expectedStatus[it] else null }
                prevStatus ?: nextStatus ?: MatchStatus.PERFECT
            }
            result.add(LetterFeedbackInfo(char.toString(), status))
        }
        
        return result
    }

    private fun getG2PCost(char: Char, phoneme: String, language: String = "en"): Int {
        val p = phoneme.lowercase()
        val c = char.lowercaseChar()

        // Exact match
        if (p.startsWith(c)) return 0

        // Vowels
        if (c in G2P_VOWEL_LETTERS && p.any { it in G2P_IPA_VOWELS }) return 2

        val g2pSim = when (baseLanguage(language)) {
            "fr" -> FR_G2P_SIM
            "de" -> DE_G2P_SIM
            "ru" -> RU_G2P_SIM
            else -> EN_G2P_SIM
        }
        if (g2pSim[c]?.contains(p) == true) return 2

        return 8 // High cost for non-matching
    }

    private val G2P_VOWEL_LETTERS = setOf('a', 'e', 'i', 'o', 'u')
    private val G2P_IPA_VOWELS = setOf('æ', 'ɑ', 'ɔ', 'ə', 'ɛ', 'ɪ', 'ʊ', 'ʌ', 'i', 'u', 'e', 'o', 'a',
        'y', 'ø', 'œ') // French rounded vowels

    /** Letter -> phones it commonly spells, per language; built once, not per letter-phone pair. */
    private val EN_G2P_SIM: Map<Char, Set<String>> = g2pTable("en")
    private val FR_G2P_SIM: Map<Char, Set<String>> = g2pTable("fr")
    private val DE_G2P_SIM: Map<Char, Set<String>> = g2pTable("de")
    private val RU_G2P_SIM: Map<Char, Set<String>> = g2pTable("ru")

    private fun g2pTable(language: String): Map<Char, Set<String>> {
        val g2pSim = mutableMapOf(
            'c' to setOf("k", "s", "t͡ʃ"),
            'g' to setOf("ɡ", "g", "d͡ʒ", "j"),
            'j' to setOf("d͡ʒ", "j"),
            'y' to setOf("j", "i", "ɪ"),
            'x' to setOf("z", "k", "s"),
            'q' to setOf("k"),
            'w' to setOf("w", "u", "ʊ"),
            'h' to setOf("h", "ɦ"),
            'm' to setOf("m", "ɱ"),
            'n' to setOf("n", "ŋ", "ɲ"),
            'r' to setOf("r", "ɹ", "ɻ", "l"),
            's' to setOf("s", "z", "ʃ"),
            't' to setOf("t", "θ", "ð"),
            'd' to setOf("d", "ð"),
            'f' to setOf("f", "v"),
            'v' to setOf("v", "f")
        )

        if (language == "fr") {
            // French-specific overrides and additions
            // 'u' in French → /y/ (front rounded), not /u/
            g2pSim['u'] = setOf("y", "u", "ʊ", "ə")
            // 'r' in French → /ʁ/ (uvular)
            g2pSim['r'] = setOf("ʁ", "ʀ", "r", "ɹ")
            // 'e' → many allophones
            g2pSim['e'] = setOf("e", "ɛ", "ø", "œ", "ə")
            // 'o' → /o/ or /ɔ/
            g2pSim['o'] = setOf("o", "ɔ", "ø")
            // 'j' in French → /ʒ/ (je, jour)
            g2pSim['j'] = setOf("ʒ", "j")
            // 'g' → /ʒ/ before e/i, /ɡ/ elsewhere
            g2pSim['g'] = setOf("ɡ", "g", "ʒ")
            // 'n' often part of nasal vowel digraph
            g2pSim['n'] = setOf("n", "ŋ", "ɲ", "ɑ̃", "ɛ̃", "ɔ̃", "œ̃")
            // 'ç' → /s/
            g2pSim['ç'] = setOf("s")
            // 'é', 'è', 'ê', 'ë' → front vowel phonemes
            g2pSim['é'] = setOf("e", "ɛ")
            g2pSim['è'] = setOf("ɛ", "e")
            g2pSim['ê'] = setOf("ɛ", "e")
            g2pSim['à'] = setOf("a", "ɑ")
            g2pSim['â'] = setOf("ɑ", "a")
            g2pSim['î'] = setOf("i")
            g2pSim['ï'] = setOf("i")
            g2pSim['ô'] = setOf("o", "ɔ")
            g2pSim['û'] = setOf("y", "u")
            g2pSim['ù'] = setOf("y", "u")
        }

        if (language == "de") {
            // German-specific G2P overrides
            // 'v' → /f/ (Vogel), not /v/
            g2pSim['v'] = setOf("f", "v")
            // 'w' → /v/ (Wasser), not /w/
            g2pSim['w'] = setOf("v", "w")
            // 'z' → /ts/ (Zeit), not /z/
            g2pSim['z'] = setOf("ts", "z", "s")
            // 'j' → /j/ (ja), not /d͡ʒ/
            g2pSim['j'] = setOf("j")
            // 'r' → /ʁ/ uvular (rot)
            g2pSim['r'] = setOf("ʁ", "ʀ", "r", "ɐ", "ɹ")
            // 's' → /z/ before vowels, /ʃ/ in st/sp, /s/ elsewhere
            g2pSim['s'] = setOf("z", "s", "ʃ")
            // 'ß' → /s/ (Straße)
            g2pSim['ß'] = setOf("s")
            // Umlauts
            g2pSim['ä'] = setOf("ɛː", "ɛ", "eː", "e")
            g2pSim['ö'] = setOf("øː", "ø", "œ", "oː", "ɔ")
            g2pSim['ü'] = setOf("yː", "y", "ʏ", "uː", "ʊ")
        }

        if (language == "ru") {
            // Cyrillic never matches a phone by its first character, so every
            // letter lists the phones it spells (espeak-ng notation, after
            // [RU_SYMBOL_FOLDS]). The soft sign spells the softness segment.
            g2pSim['а'] = setOf("a", "ɑ", "ʌ", "ə")
            g2pSim['б'] = setOf("b", "p")
            g2pSim['в'] = setOf("v", "f")
            g2pSim['г'] = setOf("ɡ", "k", "v", "x")
            g2pSim['д'] = setOf("d", "t")
            g2pSim['е'] = setOf("e", "ɛ", "i", "ɪ", "j")
            g2pSim['ё'] = setOf("ɵ", "o", "j")
            g2pSim['ж'] = setOf("ʒ", "ʃ")
            g2pSim['з'] = setOf("z", "ʑ", "s")
            g2pSim['и'] = setOf("i", "ɪ", "y")
            g2pSim['й'] = setOf("j", "i")
            g2pSim['к'] = setOf("k", "ɡ")
            g2pSim['л'] = setOf("ɭ", "l")
            g2pSim['м'] = setOf("m")
            g2pSim['н'] = setOf("n")
            g2pSim['о'] = setOf("o", "ʌ", "a", "ə")
            g2pSim['п'] = setOf("p")
            g2pSim['р'] = setOf("r")
            g2pSim['с'] = setOf("s", "z")
            g2pSim['т'] = setOf("t", "d")
            g2pSim['у'] = setOf("u")
            g2pSim['ф'] = setOf("f")
            g2pSim['х'] = setOf("x")
            g2pSim['ц'] = setOf("t", "s")
            g2pSim['ч'] = setOf("tʃ", "ʃ")
            g2pSim['ш'] = setOf("ʃ")
            g2pSim['щ'] = setOf("ɕ")
            g2pSim['ы'] = setOf("y")
            g2pSim['ь'] = setOf("ʲ")
            g2pSim['э'] = setOf("ɛ", "e")
            g2pSim['ю'] = setOf("u", "j")
            g2pSim['я'] = setOf("a", "ɑ", "ʌ", "i", "ɪ", "j")
        }

        return g2pSim
    }

    private fun getNormalizedPhoneWords(ipa: String): List<List<String>> {
        // Reference IPA comes from the offline phonemizer (has stress marks,
        // allophones like ɐ/ɚ/ᵻ, and ZWJs in diphthongs). Runtime IPA comes from
        // the acoustic model - same phoneme inventory but usually no stress and
        // less consistent allophone choice. Fold near-identical allophones so a
        // correct speaker can actually hit a high score.
        val wordGroups = normalizeChunk(ipa).split(" ").filter { it.isNotBlank() }
        val words = mutableListOf<List<String>>()
        for (word in wordGroups) {
            val phones = parsePhones(word)
            if (phones.isNotEmpty()) words.add(phones)
        }
        return words
    }

    /**
     * Expands the model's own tokens into one confidence per phone the scorer
     * parses out of the IPA. Tokens do not map one-to-one onto phones (a
     * diphthong like "aɪ" is a single token but two phones), so this reuses the
     * exact same [parsePhones] as the actual side; a token that expands to
     * several phones gives each of them its confidence. Returns an empty list
     * when the expansion does not line up, so mismatched data is ignored rather
     * than attributed to the wrong sound.
     */
    fun phoneConfidencesOf(phones: List<RecognizedPhone>): List<Float> {
        if (phones.isEmpty()) return emptyList()
        val confidences = mutableListOf<Float>()
        for (token in phones) {
            if (token.phone.isBlank()) continue
            repeat(parsePhones(normalizeChunk(token.phone)).size) {
                confidences.add(token.confidence)
            }
        }
        return confidences
    }

    /** Folds allophones and drops stress/punctuation so both sides compare fairly. */
    private fun normalizeChunk(chunk: String, keepStress: Boolean = false): String {
        val folded = chunk
            .replace('ɐ', 'ə')
            .replace('ᵻ', 'ɪ')
            .replace("ɚ", "ər")
            // The phonemizer's stressed rhotic vowel (ɝ) is not in the acoustic
            // model's vocabulary; its long central vowel is the closest token the
            // model actually produces.
            .replace("ɝ", "ɜː")
            // GOAT: the British symbol is əʊ and the American one is oʊ, but both
            // are the same vowel. The reference (CMUdict) writes oʊ and the model,
            // on British audio, writes the əʊ the vocabulary also carries; folding
            // keeps a British learner from having the onset charged as a schwa.
            .replace("əʊ", "oʊ")
            .replace("\u200D", "")
            .replace("\u00A0", " ")
            .lowercase()
        val withoutPunctuation = if (keepStress) {
            folded.replace(PUNCTUATION, "")
        } else {
            folded.replace(STRESS_AND_PUNCTUATION, "")
        }
        return withoutPunctuation
            .replace(COMBINING_MARKS, "")
            .replace("\u00A0", "")
    }

    /** Parses one cleaned chunk into phones, honouring tie bars and length marks. */
    private fun parsePhones(cleaned: String): List<String> = parseReferencePhones(cleaned).map { it.phone }

    /**
     * Reference phones in the acoustic model's own token convention, for forced
     * alignment and the quality decision layer. Unlike [phonesOf], which splits a
     * diphthong into its two vowel targets for the phoneme scorer, this keeps
     * every phone the model emits as one token whole (aɪ, eɪ, oʊ, aʊ, ɔɪ and the
     * affricates). The quality head was trained and measured on exactly this
     * convention (`benchmark/phon-multilingual/common.py`, `arpabet_to_ipa`), so
     * tokenising the reference differently here would make its held-out AUC
     * describe an input the head never saw. Allophones stay as authored (ɝ, ɚ,
     * ɐ); the recognizer resolves them to the vocabulary.
     *
     * Stress marks, word boundaries and punctuation are dropped: this is only the
     * flat phone sequence the head consumes.
     */
    fun modelPhones(ipa: String): List<String> {
        val cleaned = ipa
            .replace("\u200D", "")
            .replace("\u00A0", " ")
            .lowercase()
            .replace(STRESS_AND_PUNCTUATION, "")
            .replace(" ", "")
        return parseReferencePhones(cleaned, keepDiphthongs = true).map { it.phone }
    }

    /**
     * Parses one chunk into phones while keeping the primary-stress mark (ˈ) on
     * the phone it precedes. Secondary stress (ˌ) is treated as unstressed: for
     * English the primary accent is what the learner has to place correctly.
     *
     * With [keepDiphthongs] a vowel+glide pair the model emits as one token (aɪ,
     * eɪ, oʊ, aʊ, ɔɪ) is one phone, not two. This is the benchmark convention the
     * quality head relies on.
     */
    private fun parseReferencePhones(chunk: String, keepDiphthongs: Boolean = false): List<ReferencePhone> {
        if (chunk.isEmpty()) return emptyList()
        val phones = mutableListOf<ReferencePhone>()
        var pendingStress = false
        var i = 0
        while (i < chunk.length) {
            val c = chunk[i]
            if (c == 'ˈ') {
                pendingStress = true
                i++
                continue
            }
            if (c == 'ˌ') {
                i++
                continue
            }
            val phone: String
            val consumed: Int
            when {
                i + 1 < chunk.length && chunk[i + 1] == '\u0303' -> {
                    phone = chunk.substring(i, i + 2); consumed = 2
                }
                i + 2 < chunk.length && chunk[i + 1] == '\u0361' -> {
                    phone = chunk.substring(i, i + 3); consumed = 3
                }
                i + 1 < chunk.length && isTiedPair(chunk[i], chunk[i + 1]) -> {
                    phone = chunk.substring(i, i + 2); consumed = 2
                }
                i + 1 < chunk.length && keepDiphthongs && chunk.substring(i, i + 2) in DIPHTHONGS -> {
                    phone = chunk.substring(i, i + 2); consumed = 2
                }
                c == 'g' -> {
                    phone = "ɡ"; consumed = 1
                }
                else -> {
                    phone = c.toString(); consumed = 1
                }
            }
            var next = i + consumed
            // A length mark belongs to the phone it follows (iː is one phone,
            // a long vowel - not the vowel plus a separate "ː" segment).
            val full = if (next < chunk.length && chunk[next] == 'ː') {
                next++
                phone + "ː"
            } else {
                phone
            }
            // A stress mark opens a syllable, and the stress falls on its vowel
            // nucleus, not on the consonant onset that follows the mark.
            val stressed = pendingStress && PhoneticInventory.isVowel(full)
            if (stressed) pendingStress = false
            phones.add(ReferencePhone(full, stressed))
            i = next
        }
        return phones
    }

    private fun getNormalizedPhoneList(ipa: String): List<String> =
        getNormalizedPhoneWords(ipa).flatten()

    private fun isTiedPair(c1: Char, c2: Char): Boolean {
        val s = "$c1$c2"
        // Only true affricates stay tied. English has no /ts/ or /dz/ phoneme -
        // they are always a t plus s across a boundary ("cat sat", "cats") - and
        // the acoustic model emits "ts" as one token, so keeping them tied would
        // misalign the model's fusion against a word-separated reference.
        return s == "tʃ" || s == "dʒ"
    }

    /**
     * Diphthongs the model emits as one token. [phonesOf] splits them (two vowel
     * targets), [modelPhones] keeps them whole - the convention the quality head
     * was trained on. English is the only pack today; the same symbols recur in
     * the eSpeak inventories of other languages.
     */
    private val DIPHTHONGS = setOf("aɪ", "eɪ", "oʊ", "aʊ", "ɔɪ")

    private fun getSimilarity(p1: String, p2: String, profile: ScoringProfile): Double {
        if (p1 == p2) return 1.0
        // Length is phonemic in German/Latin and meaning-bearing in English, so a
        // long/short vowel mismatch must not count as perfect. Checked before the
        // matrices so every language treats it consistently.
        if (isLengthVariant(p1, p2)) {
            return if (profile.lengthMarksPhonemic) LENGTH_SIMILARITY else 1.0
        }
        // English unstressed-vowel neutralisation: a reference schwa heard as a
        // near-close vowel is the same sound, not a substitution.
        if (profile.englishFolds && p1 in REDUCED_VOWELS && p2 in REDUCED_REALISATIONS) return 1.0
        profile.matrix[setOf(p1, p2)]?.let { return it }
        // No fallback - only explicit mappings count
        // This prevents random matches from scoring high
        return 0.0
    }

    /**
     * True when two phones are the same vowel differing only by a length mark.
     * Whether that is a real difference depends on the language; see
     * [lengthMarksPhonemic].
     */
    private fun isLengthVariant(a: String, b: String): Boolean {
        val aLong = a.endsWith("ː")
        val bLong = b.endsWith("ː")
        if (aLong == bLong) return false
        return a.removeSuffix("ː") == b.removeSuffix("ː")
    }

    fun normalize(ipa: String): String {
        return getNormalizedPhoneList(ipa).joinToString("")
    }

    /**
     * Parses IPA into the flat phone list the scorer compares, in order. Exposed
     * so callers (forced alignment) can index the same phones the score used.
     */
    fun phonesOf(ipa: String): List<String> = getNormalizedPhoneList(ipa)

    /**
     * Phoneme error rate of [actual] against [expected]: the Levenshtein distance
     * between the two normalized phone sequences (substitutions + deletions +
     * insertions) divided by the number of reference phones. 0.0 means an exact
     * match; above 1.0 means more edits than reference phones (many insertions).
     *
     * This is a plain sequence metric for model comparison, deliberately separate
     * from [calculateScoringResult]: the tolerated-allophone matrix can score a
     * substitution as a near match, which is right for a learner but hides how
     * close the raw transcription is. Comparing two recognizers on the same audio
     * wants the unflattering number.
     */
    fun phonemeErrorRate(expected: String, actual: String): Double {
        val reference = phonesOf(expected)
        val hypothesis = phonesOf(actual)
        if (reference.isEmpty()) return if (hypothesis.isEmpty()) 0.0 else 1.0
        val m = hypothesis.size
        var previous = IntArray(m + 1) { it }
        var current = IntArray(m + 1)
        for (i in 1..reference.size) {
            current[0] = i
            for (j in 1..m) {
                val substitution = previous[j - 1] + if (reference[i - 1] == hypothesis[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[m].toDouble() / reference.size
    }

    /**
     * IPA phones as authored, for content checks (drills). Unlike [phonesOf],
     * which splits diphthongs the way the acoustic model does for scoring, this
     * keeps a diphthong or affricate as one phone so a drill that targets /eɪ/
     * or /oʊ/ can be verified against the symbol it declares. Stress marks and
     * spaces are dropped.
     */
    fun contentPhones(ipa: String, language: String? = null): List<String> {
        val affricates = CONTENT_AFFRICATES[language?.lowercase()?.substringBefore('-')?.substringBefore('_')].orEmpty()
        val out = mutableListOf<String>()
        var i = 0
        while (i < ipa.length) {
            val c = ipa[i]
            if (c == '\u02c8' || c == '\u02cc' || c.isWhitespace() || c in CONTENT_SKIP) { i++; continue }
            val two = if (i + 1 < ipa.length) ipa.substring(i, i + 2) else ""
            var next = i + if (two in CONTENT_TWO_CHAR || two in affricates) 2 else 1
            if (next < ipa.length && ipa[next] == '\u02d0') next++
            // A soft consonant is one phone for content ("tʲ" in мать vs "t" in
            // мат), the way the model emits it, even though the scorer compares
            // the softness as its own segment.
            while (next < ipa.length && ipa[next] in CONTENT_MODIFIERS) next++
            out.add(ipa.substring(i, next))
            i = next
        }
        return out
    }

    /** Punctuation the reference may carry; never a phone, so content checks skip it. */
    private val CONTENT_SKIP = setOf(
        '-', '.', ',', '?', '!', ';', ':', '(', ')', '"', '\u00ab', '\u00bb', '\u2019', '\'',
    )

    private val CONTENT_TWO_CHAR = setOf(
        "t\u0283", "d\u0292", "e\u026a", "o\u028a", "a\u026a", "a\u028a", "\u0254\u026a",
        // Nasal vowels are base + combining tilde, and the model emits them as one
        // token, so content validation must keep them whole.
        "\u0251\u0303", "\u025b\u0303", "\u0254\u0303", "\u0153\u0303",
        // The German "eu"/"äu" diphthong as espeak-ng writes it (ɔø).
        "\u0254\u00f8",
    )

    /**
     * Affricates that are one phone only in some languages. Russian Ц is one
     * sound, while the same two letters in English "cats" are a /t/ and an /s/.
     */
    private val CONTENT_AFFRICATES = mapOf("ru" to setOf("ts"))

    /** Marks that belong to the phone before them: softness (ʲ) and espeak's `^`. */
    private val CONTENT_MODIFIERS = setOf('\u02b2', '^')

    /** One parsed reference phone and whether it carries the word's primary stress. */
    data class ReferencePhone(val phone: String, val stressed: Boolean)

    /**
     * Reference phones grouped by word, keeping primary stress, for prosody
     * scoring. Uses the same parsing as [phonesOf], so index i here is index i
     * there (and therefore index i in the forced-alignment timings).
     */
    fun referenceWords(ipa: String): List<List<ReferencePhone>> {
        val groups = normalizeChunk(ipa, keepStress = true).split(" ").filter { it.isNotBlank() }
        return groups.map { parseReferencePhones(it) }.filter { it.isNotEmpty() }
    }

    /** Reference phones in order, keeping stress. Same order as [phonesOf]. */
    fun referencePhones(ipa: String): List<ReferencePhone> = referenceWords(ipa).flatten()
}
