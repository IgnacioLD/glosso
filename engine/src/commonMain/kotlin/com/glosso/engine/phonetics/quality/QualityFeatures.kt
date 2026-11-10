package com.glosso.engine.phonetics.quality

import com.glosso.engine.phonetics.AcousticFrames
import com.glosso.engine.phonetics.PhoneSpan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Per-reference-phone acoustic features for the quality head.
 *
 * Port of `phone_features` in `benchmark/phon-multilingual/align.py` (PR #52).
 * The head consumes a subset; the rest ([posterior], [maxpost], [gopt]) are kept
 * because the parity test pins the whole reference implementation, not just the
 * columns the current head happens to use.
 */
internal object QualityFeatures {

    private const val LOG_FLOOR = 1e-10

    data class Raw(
        val phoneIndex: Int,
        val tokenId: Int,
        val startFrame: Int,
        val endFrame: Int,
        val frames: Int,
        val gop: Double,
        val gopt: Double,
        val gopRank: Double,
        val posterior: Double,
        val maxpost: Double,
        val margin: Double,
        val entropy: Double,
        val producedId: Int,
    )

    fun extract(frames: AcousticFrames, spans: List<PhoneSpan>): List<Raw> {
        val vocabSize = frames.vocabSize
        if (vocabSize <= 0) return emptyList()
        val blankId = frames.blankId
        val t = frames.timeSteps

        val bestLogp = DoubleArray(t)
        val bestId = IntArray(t)
        val entropy = DoubleArray(t)
        for (frame in 0 until t) {
            var best = Double.NEGATIVE_INFINITY
            var bestV = 0
            var ent = 0.0
            for (v in 0 until vocabSize) {
                val p = frames.probability(frame, v).toDouble()
                if (p <= 0.0) continue
                val lp = ln(max(p, LOG_FLOOR))
                if (v != blankId && lp > best) {
                    best = lp
                    bestV = v
                }
                ent += p * lp
            }
            bestLogp[frame] = best
            bestId[frame] = bestV
            entropy[frame] = -ent
        }

        val rows = ArrayList<Raw>(spans.size)
        for (spanIndex in spans.indices) {
            val span = spans[spanIndex]
            val start = span.startFrame
            val end = span.endFrame
            if (end <= start) continue
            val target = span.tokenId
            var logpTarget = 0.0
            var post = 0.0
            var bestLogpSum = 0.0
            var bestProbSum = 0.0
            var entSum = 0.0
            val counts = IntArray(vocabSize)
            for (frame in start until end) {
                val pt = frames.probability(frame, target).toDouble()
                if (pt > 0.0) logpTarget += ln(max(pt, LOG_FLOOR))
                post += pt
                // GOP compares log probabilities; maxpost/margin convert the best
                // non-blank log probability back to a probability, like align.py.
                bestLogpSum += bestLogp[frame]
                bestProbSum += exp(bestLogp[frame])
                entSum += entropy[frame]
                counts[bestId[frame]]++
            }
            val n = end - start
            var produced = 0
            for (v in 0 until vocabSize) if (counts[v] > counts[produced]) produced = v
            rows.add(
                Raw(
                    phoneIndex = spanIndex,
                    tokenId = target,
                    startFrame = start,
                    endFrame = end,
                    frames = n,
                    gop = logpTarget / n - bestLogpSum / n,
                    gopt = 0.0,
                    gopRank = 0.0,
                    posterior = post / n,
                    maxpost = bestProbSum / n,
                    margin = post / n - bestProbSum / n,
                    entropy = entSum / n,
                    producedId = produced,
                ),
            )
        }

        val count = rows.size
        if (count == 0) return emptyList()
        val gops = DoubleArray(count) { rows[it].gop }
        val gopMean: Double
        val gopStd: Double
        if (count >= 2 && std(gops) > 1e-9) {
            gopMean = gops.average()
            gopStd = std(gops)
        } else {
            gopMean = 0.0
            gopStd = 1.0
        }
        val order = (0 until count).sortedWith(compareBy({ gops[it] }, { it }))
        val rank = IntArray(count)
        order.forEachIndexed { position, index -> rank[index] = position }
        val denom = max(1, count - 1)

        return rows.mapIndexed { i, raw ->
            raw.copy(
                gopt = (raw.gop - gopMean) / gopStd,
                gopRank = rank[i].toDouble() / denom,
            )
        }
    }

    private fun std(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }
}
