package com.glosso.engine.phonetics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pins the underlying score against a golden produced by the pre-refactor
 * comparator, so "no change to the deployed score" is a re-runnable check rather
 * than a claim.
 *
 * SET: every [STRIDE]th row of `engine/src/jvmTest/resources/probe_tts_ref.json` with a
 * transcript, re-scored IPA -> IPA in four modes (single reference, variants path
 * without and with coda widening, and en-GB). Per scoring it pins score,
 * accuracy, completeness and the full phone alignment. Deliberately out: the per
 * word breakdown, which the elision fix changes on purpose (`ElisionWordIndexTest`),
 * and the verdict (level, mastery), which is a band over this score and moved on
 * purpose with the slip tolerance (`ScoringConfigTest`, `VerdictBandProbeTest`).
 *
 * The golden (`parity/comparator-golden.tsv`) was written by this same test on
 * the commit named in its first line. To regenerate it on any commit, copy this
 * file there and run:
 *
 *   GLOSSO_PARITY_WRITE=/abs/path/comparator-golden.tsv ./gradlew :engine:jvmTest \
 *     --tests "com.glosso.engine.phonetics.ComparatorParityTest" --rerun-tasks
 */
class ComparatorParityTest {

    private val probePath = "src/jvmTest/resources/probe_tts_ref.json"

    @Test
    fun `the shown score is identical to the pre-refactor golden`() {
        val probe = File(probePath)
        assertTrue(probe.isFile, "the fixed probe is missing at $probe")
        val actual = score(probe)

        System.getenv("GLOSSO_PARITY_WRITE")?.let { path ->
            File(path).writeText(actual.joinToString("\n", postfix = "\n"))
            println("PARITY_WRITE $path lines=${actual.size}")
            return
        }

        val golden = javaClass.getResource("/parity/comparator-golden.tsv")!!.readText()
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(golden.size == actual.size, "golden has ${golden.size} scorings, this run ${actual.size}")
        val diffs = golden.indices.filter { golden[it] != actual[it] }
        println("PARITY scorings=${actual.size} diffs=${diffs.size}")
        assertTrue(
            diffs.isEmpty(),
            "${diffs.size} scorings differ from the golden, first:\n" +
                diffs.take(5).joinToString("\n") { "golden: ${golden[it]}\nactual: ${actual[it]}" },
        )
    }

    private fun score(probe: File): List<String> {
        val out = ArrayList<String>()
        val rows = Json.parseToJsonElement(probe.readText()).jsonObject.getValue("rows").jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("hyp").jsonPrimitive.content.isNotBlank() }
        for ((i, row) in rows.withIndex()) {
            if (i % STRIDE != 0) continue
            val id = row.getValue("id").jsonPrimitive.content
            val canon = row.getValue("canon").jsonPrimitive.content
            val hyp = row.getValue("hyp").jsonPrimitive.content
            val results = listOf(
                "single" to PhoneticComparator.scoreSingleReference("probe", canon, hyp),
                "deployed" to PhoneticComparator.calculateScoringResult(
                    text = "probe", expected = canon, actual = hyp, dialectWidening = false,
                ),
                "current" to PhoneticComparator.calculateScoringResult(
                    text = "probe", expected = canon, actual = hyp, dialectWidening = true,
                ),
                "en-GB" to PhoneticComparator.calculateScoringResult(
                    text = "probe", expected = canon, actual = hyp, language = "en-GB", dialectWidening = true,
                ),
            )
            for ((mode, r) in results) {
                val alignment = r.alignment.joinToString(" ") { "${it.expected}/${it.actual}/${it.status.name[0]}" }
                out.add("$id\t$mode\t${r.score}\t${r.accuracy}\t${r.completeness}\t$alignment")
            }
        }
        return out
    }

    private companion object {
        const val STRIDE = 4
    }
}
