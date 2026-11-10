package com.glosso.engine.phonetics

import java.io.File
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bench of a language's comparator against what the acoustic model heard in
 * native reference clips: how many of them reach 98. The metric that rules is
 * "a native scores at least 98" (AGENTS.md); this is that number for a language
 * without re-running the model, so a change to its matrix or folds can be
 * measured in seconds.
 *
 * `GLOSSO_NATIVE_TRANSCRIPTS` is a JSON list of rows
 * `[id, voice, serverScore, evaluated, text, referenceIpa, heardIpa]`, as written
 * by a scoring run of the reference clips (the Russian one came from the Cartesia
 * voices through `/v1/score`). `GLOSSO_NATIVE_LANGUAGE` is the language (default
 * `ru`). `GLOSSO_NATIVE_PERTURB` (`ɨ>i,ʂ>s,ʲ>`) lists learner substitutions for
 * the guard. Skips silently when unset, like the other asset-gated tests.
 */
class NativeTranscriptBenchTest {

    @Test
    fun `native reference clips against the comparator`() {
        val file = System.getenv("GLOSSO_NATIVE_TRANSCRIPTS")?.let(::File)?.takeIf { it.isFile } ?: return
        val language = System.getenv("GLOSSO_NATIVE_LANGUAGE") ?: "ru"
        val rows = Json.parseToJsonElement(file.readText()).jsonArray.map { row ->
            row.jsonArray.map { it.jsonPrimitive.content }
        }.filter { it[6].isNotBlank() }

        data class Scored(val id: String, val voice: String, val score: Int, val text: String, val ipa: String, val heard: String)
        val scored = rows.map {
            val result = PhoneticComparator.calculateScoringResult(it[4], it[5], it[6], language)
            Scored(it[0], it[1], result.score, it[4], it[5], it[6])
        }
        fun kindOf(id: String) = when {
            ".word." in id -> "word"
            ".dialogue." in id -> "dialogue"
            else -> "drill"
        }
        for ((kind, clips) in scored.groupBy { kindOf(it.id) }) {
            val native = clips.count { it.score >= 98 }
            println(
                "NATIVE98|language=$language|kind=$kind|clips=${clips.size}|ge98=$native|" +
                    "rate=${"%.3f".format(native.toDouble() / clips.size)}|ge90=${clips.count { it.score >= 90 }}|" +
                    "mean=${"%.1f".format(clips.map { it.score }.average())}",
            )
        }
        val sentences = scored.filter { kindOf(it.id) != "word" }
        println(
            "NATIVE98_SENTENCES|language=$language|clips=${sentences.size}|ge98=${sentences.count { it.score >= 98 }}|" +
                "rate=${"%.3f".format(sentences.count { it.score >= 98 }.toDouble() / sentences.size.coerceAtLeast(1))}",
        )
        // The guard: a native clip with one typical learner substitution written
        // into what was heard must score lower. Leniency that lifts natives to 98
        // by no longer hearing these is not an improvement.
        val rules = System.getenv("GLOSSO_NATIVE_PERTURB").orEmpty().split(',').mapNotNull { rule ->
            val parts = rule.split('>')
            if (parts.size == 2 && parts[0].isNotEmpty()) parts[0] to parts[1] else null
        }
        for ((from, to) in rules) {
            val hit = sentences.filter { from in it.heard }
            val drops = hit.map {
                it.score - PhoneticComparator.calculateScoringResult(it.text, it.ipa, it.heard.replace(from, to), language).score
            }
            println(
                "NATIVE_GUARD|language=$language|rule=$from>$to|clips=${hit.size}|" +
                    "mean_drop=${"%.1f".format(drops.average())}|detected=${drops.count { it >= 2 }}",
            )
        }
        scored.filter { kindOf(it.id) != "word" && it.score < 98 }.sortedBy { it.score }.take(60).forEach {
            println("NATIVE_LOW|${it.score}|${it.id}|${it.voice}|${it.ipa}|${it.heard}")
        }
    }
}
