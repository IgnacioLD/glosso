package com.glosso.engine.phonetics

import com.glosso.engine.g2p.Arpabet
import java.io.File

/** Shared helpers for the probes over `engine/src/jvmTest/resources/probe_tts_ref.json`. */
internal object AucProbe {

    const val PROBE_PATH = "src/jvmTest/resources/probe_tts_ref.json"

    /** primary IPA -> the word's variant IPAs, read straight from a cmudict file. */
    fun variantsByPrimaryIpa(file: File): Map<String, List<String>> {
        val entries = LinkedHashMap<String, MutableList<String>>()
        file.forEachLine { line ->
            if (line.isBlank() || line.startsWith(";;;")) return@forEachLine
            val parts = line.substringBefore('#').trim().split(Regex("\\s+"))
            if (parts.size < 2) return@forEachLine
            val base = parts[0].substringBefore('(').lowercase()
            entries.getOrPut(base) { mutableListOf() }.add(parts.drop(1).joinToString(" "))
        }
        val map = HashMap<String, List<String>>()
        for (arpabet in entries.values) {
            val primary = Arpabet.word(arpabet.first())
            if (primary.isEmpty()) continue
            map.putIfAbsent(primary, arpabet.map { Arpabet.word(it) })
        }
        return map
    }

    fun wordVariants(
        canon: String,
        variantsByPrimary: Map<String, List<String>>,
    ): List<List<String>> =
        canon.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .map { word -> variantsByPrimary[word] ?: listOf(word) }

    fun auc(samples: List<Pair<Double, Boolean>>): Double {
        val positives = samples.filter { it.second }.map { it.first }
        val negatives = samples.filter { !it.second }.map { it.first }
        if (positives.isEmpty() || negatives.isEmpty()) return Double.NaN
        var wins = 0.0
        for (p in positives) {
            for (n in negatives) {
                wins += when {
                    p > n -> 1.0
                    p == n -> 0.5
                    else -> 0.0
                }
            }
        }
        return wins / (positives.size * negatives.size)
    }
}
