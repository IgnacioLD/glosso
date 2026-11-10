package com.glosso.engine.phonetics

import kotlin.math.ln

/**
 * Frame-level output of an acoustic model: the probability of every vocabulary
 * token at every time frame.
 *
 * This is the raw material for forced alignment. The recognizer normally throws
 * it away after greedy decoding, but knowing *when* each reference phone was
 * spoken (rather than just what was heard) is what enables per-syllable timing,
 * prosody and a real error diagnosis.
 */
interface AcousticFrames {
    /** Number of frames the model produced. */
    val timeSteps: Int

    /** Vocabulary index of the CTC blank token. */
    val blankId: Int

    /** Vocabulary index of [token], or -1 when the token is unknown to the model. */
    fun tokenIndex(token: String): Int

    /** Probability (0..1) of vocabulary index [tokenId] at [frame]. */
    fun probability(frame: Int, tokenId: Int): Float

    /**
     * Size of the model vocabulary, or 0 when unknown. The quality decision layer
     * needs to sweep every token per frame (entropy, best non-blank), so impls
     * that want it exposed must report it; the default keeps older fakes valid.
     */
    val vocabSize: Int get() = 0

    /** Token string for a vocabulary index, or null when unknown. */
    fun tokenOf(id: Int): String? = null
}

/** A reference phone located on the model's frame timeline. */
data class PhoneSpan(
    val phone: String,
    val tokenId: Int,
    /** First frame of the phone (inclusive). */
    val startFrame: Int,
    /** One past the last frame of the phone (exclusive). */
    val endFrame: Int,
    /** Mean model probability across the phone's frames, 0..1. */
    val posterior: Float,
) {
    val frameCount: Int get() = endFrame - startFrame
}

/**
 * CTC forced alignment (Viterbi over the blank-extended reference sequence).
 *
 * Unlike recognition, which asks "what was said", this asks "given that the
 * learner was supposed to say [reference], when did each sound happen". That is
 * the difference between knowing a phone was wrong and knowing it was too short,
 * too weak, or missing entirely.
 */
object ForcedAligner {

    /** wav2vec2's feature stride: 320 samples at 16 kHz, i.e. one frame per 20 ms. */
    const val FRAME_SAMPLES = 320

    private const val LOG_FLOOR = 1e-10f

    /**
     * Returns one [PhoneSpan] per reference phone, or null when alignment is
     * impossible: an empty reference, fewer frames than phones, or a reference
     * phone the model vocabulary does not contain.
     */
    fun align(reference: List<String>, frames: AcousticFrames): List<PhoneSpan>? {
        if (reference.isEmpty()) return null
        val t = frames.timeSteps
        if (t < reference.size) return null

        val ids = IntArray(reference.size) { frames.tokenIndex(reference[it]) }
        if (ids.any { it < 0 }) return null

        // Blank-extended sequence: [blank, p0, blank, p1, ..., blank].
        val stateCount = 2 * ids.size + 1
        val ext = IntArray(stateCount) { if (it % 2 == 0) frames.blankId else ids[(it - 1) / 2] }

        val negative = Float.NEGATIVE_INFINITY
        var prev = FloatArray(stateCount) { negative }
        prev[0] = logProb(frames, 0, ext[0])
        if (stateCount > 1) prev[1] = logProb(frames, 0, ext[1])

        val back = Array(t) { IntArray(stateCount) }
        for (frame in 1 until t) {
            val current = FloatArray(stateCount) { negative }
            for (s in 0 until stateCount) {
                var best = prev[s]
                var arg = s
                if (s - 1 >= 0 && prev[s - 1] > best) {
                    best = prev[s - 1]
                    arg = s - 1
                }
                // Skip a blank only between two different phones.
                if (s - 2 >= 0 && ext[s] != frames.blankId && ext[s] != ext[s - 2] && prev[s - 2] > best) {
                    best = prev[s - 2]
                    arg = s - 2
                }
                val emission = logProb(frames, frame, ext[s])
                current[s] = if (best == negative || emission == negative) negative else best + emission
                back[frame][s] = arg
            }
            prev = current
        }

        var last = stateCount - 1
        if (stateCount >= 2 && prev[stateCount - 2] > prev[last]) last = stateCount - 2
        if (prev[last] == negative) return null

        val path = IntArray(t)
        path[t - 1] = last
        for (frame in t - 1 downTo 1) path[frame - 1] = back[frame][path[frame]]

        val spans = ArrayList<PhoneSpan>(reference.size)
        var frame = 0
        while (frame < t) {
            val state = path[frame]
            if (state % 2 == 0) {
                frame++
                continue
            }
            val phoneIndex = (state - 1) / 2
            var end = frame
            var sum = 0f
            while (end < t && path[end] == state) {
                sum += frames.probability(end, ids[phoneIndex])
                end++
            }
            spans.add(
                PhoneSpan(
                    phone = reference[phoneIndex],
                    tokenId = ids[phoneIndex],
                    startFrame = frame,
                    endFrame = end,
                    posterior = sum / (end - frame),
                ),
            )
            frame = end
        }

        // A valid path visits every phone exactly once, in order.
        if (spans.size != reference.size) return null
        return splitGaps(spans)
    }

    /**
     * The model marks the transition between phones with blank frames, so a raw
     * path leaves silence between every pair of phones and every phone lasts one
     * frame. Dividing each run of blanks at its midpoint gives every phone its
     * share of the transition, which is what makes the durations mean something.
     * Leading and trailing silence stay unassigned.
     */
    private fun splitGaps(spans: List<PhoneSpan>): List<PhoneSpan> {
        if (spans.size < 2) return spans
        val result = spans.toMutableList()
        for (i in 1 until result.size) {
            val gapStart = result[i - 1].endFrame
            val gapEnd = result[i].startFrame
            if (gapEnd > gapStart) {
                val middle = (gapStart + gapEnd) / 2
                result[i - 1] = result[i - 1].copy(endFrame = middle)
                result[i] = result[i].copy(startFrame = middle)
            }
        }
        return result
    }

    private fun logProb(frames: AcousticFrames, frame: Int, tokenId: Int): Float {
        val p = frames.probability(frame, tokenId)
        return if (p <= 0f) Float.NEGATIVE_INFINITY else ln(p.coerceAtLeast(LOG_FLOOR))
    }
}

/**
 * [AcousticFrames] backed by a raw logits matrix; probabilities are computed
 * once (softmax per frame) so Viterbi can read them cheaply.
 */
class SoftmaxFrames(
    logits: FloatArray,
    override val timeSteps: Int,
    override val vocabSize: Int,
    override val blankId: Int,
    private val idToToken: Map<Int, String>,
) : AcousticFrames {

    private val probabilities = FloatArray(timeSteps * vocabSize)
    private val tokenToId: Map<String, Int> = idToToken.entries.associate { (id, token) -> token to id }

    init {
        for (t in 0 until timeSteps) {
            val base = t * vocabSize
            var max = Float.NEGATIVE_INFINITY
            for (v in 0 until vocabSize) if (logits[base + v] > max) max = logits[base + v]
            var sum = 0.0
            for (v in 0 until vocabSize) sum += kotlin.math.exp((logits[base + v] - max).toDouble())
            val inv = (1.0 / sum).toFloat()
            for (v in 0 until vocabSize) {
                probabilities[base + v] = (kotlin.math.exp((logits[base + v] - max).toDouble()) * inv).toFloat()
            }
        }
    }

    override fun tokenIndex(token: String): Int = tokenToId[token] ?: -1

    override fun probability(frame: Int, tokenId: Int): Float = probabilities[frame * vocabSize + tokenId]

    override fun tokenOf(id: Int): String? = idToToken[id]
}
