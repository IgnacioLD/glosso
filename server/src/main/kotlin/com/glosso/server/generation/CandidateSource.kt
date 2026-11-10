package com.glosso.server.generation

/**
 * One candidate text for a Need Class, before it becomes content. This is the
 * seam between "what should the learner practise" and "what text do we have".
 *
 * Phase 3 plugs the LLM `TextGenerationProvider` in here. Phase 2 ships a
 * deterministic, template-based source so the pool, the queue and the TTS
 * worker can be proven end to end without any model: it implements the brief's
 * "variacion parametrizada (plantillas)" step, which comes before controlled or
 * free generation, and it never calls an LLM.
 */
data class ContentCandidate(
    val text: String,
    val kind: String = "DRILL",
    val objective: String? = null,
    val exerciseType: String? = null,
    val answerJson: String? = null,
    val ipa: String? = null,
    val voice: String = "",
    val speakerIndex: Int? = null,
)

/**
 * Produces the candidate texts for a Need Class. Implementations must be
 * deterministic: the same Need Class and count always yield the same candidates,
 * so the pool and the queue stay idempotent.
 */
interface CandidateSource {

    /** Stable id, mixed into the variant hash so sources cannot collide. */
    val id: String

    fun candidates(needClass: NeedClass, count: Int): List<ContentCandidate>
}

/**
 * Deterministic template source. It combines a small set of frames with a word
 * bank per phoneme, in a fixed order, so candidate N is always the same string.
 * When no phrase is known for a phoneme it falls back to [fallbackPhrases]; an
 * empty result means "we have nothing to say for this class", and the scheduler
 * simply does not enqueue (the gap stays open for phase 3).
 *
 * The default bank is a placeholder, not curriculum: phase 3 replaces this
 * source with a real text provider and a curated bank.
 */
class TemplateCandidateSource(
    private val phrasesByPhoneme: Map<String, List<String>> = DEFAULT_BANK,
    private val fallbackPhrases: List<String> = DEFAULT_FALLBACK,
    private val frames: List<String> = DEFAULT_FRAMES,
) : CandidateSource {

    override val id: String = "template-v1"

    override fun candidates(needClass: NeedClass, count: Int): List<ContentCandidate> {
        if (count <= 0) return emptyList()
        val phoneme = needClass.key.phoneme
        val phrases = (phoneme?.let { phrasesByPhoneme[it] } ?: emptyList()).ifEmpty { fallbackPhrases }
        if (phrases.isEmpty() || frames.isEmpty()) return emptyList()

        val key = needClass.key
        return (0 until count).map { index ->
            val phrase = phrases[index % phrases.size]
            val frame = frames[(index / phrases.size) % frames.size]
            ContentCandidate(
                text = frame.replace("%s", phrase),
                kind = "DRILL",
                objective = "Practice ${phoneme ?: key.skill} at ${key.position ?: "any"} position",
                exerciseType = "repeat",
                answerJson = """{"target":"${escape(phrase)}"}""",
            )
        }
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        val DEFAULT_FRAMES = listOf(
            "I said %s.",
            "Please say %s.",
            "Can you repeat %s?",
            "The phrase is %s.",
        )

        /** A tiny placeholder bank for the most common English targets. */
        val DEFAULT_BANK: Map<String, List<String>> = mapOf(
            "θ" to listOf("think", "three", "thank you", "the weather"),
            "ð" to listOf("this", "that", "those", "the weather"),
            "ɹ" to listOf("right", "red", "around", "very"),
            "s" to listOf("see", "sun", "message", "this"),
            "ʃ" to listOf("she", "shop", "nation", "special"),
            "tʃ" to listOf("chair", "watch", "teacher", "catch"),
            "v" to listOf("very", "voice", "seven", "love"),
            "w" to listOf("we", "would", "away", "one"),
        )

        val DEFAULT_FALLBACK = listOf("this", "that", "the", "a")
    }
}
