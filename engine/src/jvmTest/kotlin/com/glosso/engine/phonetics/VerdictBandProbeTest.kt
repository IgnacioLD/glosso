package com.glosso.engine.phonetics

import com.glosso.engine.model.MasteryLevel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How the verdict the learner sees (the sentence [MasteryLevel]) is distributed
 * over the fixed arbiter probe, split by native and non-native speakers. It
 * reports, it does not gate: the bands are a product decision, and this is the
 * number that decision is made on.
 *
 * SET: the `native` / `nonnative` rows of `engine/src/jvmTest/resources/probe_tts_ref.json`
 * (Common Voice test, recognizer transcript `hyp` vs production reference
 * `canon`), scored as production does without variants. Same set as
 * [ReferenceVariantAucProbeTest].
 *
 *   ./gradlew :engine:jvmTest --tests "com.glosso.engine.phonetics.VerdictBandProbeTest" -i | grep VERDICT
 */
class VerdictBandProbeTest {

    private val probePath = "src/jvmTest/resources/probe_tts_ref.json"

    @Test
    fun `print the verdict distribution by speaker group`() {
        val probe = File(probePath)
        assertTrue(probe.isFile, "the fixed probe is missing at $probe")
        val levels = mapOf("native" to IntArray(MasteryLevel.entries.size), "nonnative" to IntArray(MasteryLevel.entries.size))
        for (element in Json.parseToJsonElement(probe.readText()).jsonObject.getValue("rows").jsonArray) {
            val row = element.jsonObject
            val counts = levels[row.getValue("role").jsonPrimitive.content] ?: continue
            val hyp = row.getValue("hyp").jsonPrimitive.content
            if (hyp.isBlank()) continue
            val canon = row.getValue("canon").jsonPrimitive.content
            counts[PhoneticComparator.calculateScoringResult("probe", canon, hyp).level.ordinal]++
        }
        for ((role, counts) in levels) {
            val total = counts.sum()
            val good = counts[MasteryLevel.PERFECT.ordinal] + counts[MasteryLevel.MASTERED.ordinal]
            val breakdown = MasteryLevel.entries.joinToString(" ") { "${it.name}=${counts[it.ordinal]}" }
            println("VERDICT $role n=$total good=$good (${"%.1f".format(100.0 * good / total)}%) $breakdown")
        }
    }
}
