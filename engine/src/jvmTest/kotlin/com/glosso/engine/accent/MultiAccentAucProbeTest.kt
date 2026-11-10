package com.glosso.engine.accent

import com.glosso.engine.phonetics.AucProbe
import com.glosso.engine.phonetics.PhoneticComparator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The guard for multi-accent scoring: more accepted accents must not buy native
 * clips at the price of forgiving learner errors (an Irish θ -> t is also the
 * classic Spanish-speaker error). Same fixed set and metric as
 * `ReferenceVariantAucProbeTest`: native > non-native AUC of the shown score on
 * the 810 committed probe rows (216 native / 594 non-native, Common Voice).
 *
 * Reports `current` (production today), `multi` (every system) and `multi_gb_us`
 * (en-GB + en-US only), plus which systems the native and non-native rows
 * choose. Report-only; `GLOSSO_MULTI_ACCENT_AUC_STRICT=1` fails when `multi`
 * falls below `current`. Needs `GLOSSO_CMUDICT` for the dictionary variants
 * (without it both sides are scored without them).
 *
 *   GLOSSO_CMUDICT=/path/cmudict.dict ./gradlew :engine:jvmTest \
 *     --tests 'com.glosso.engine.accent.MultiAccentAucProbeTest' -i
 */
class MultiAccentAucProbeTest {

    @Test
    fun `multi-accent scoring keeps native and non-native apart`() {
        val probe = File(AucProbe.PROBE_PATH)
        assertTrue(probe.isFile, "the fixed probe is missing at $probe")
        val cmudict = System.getenv("GLOSSO_CMUDICT")?.let(::File)?.takeIf { it.isFile }
        val variantsByPrimary = cmudict?.let(AucProbe::variantsByPrimaryIpa)

        val all = AccentCatalog.ENGLISH
        val gbUs = all.filter { it.accent == "en-GB" || it.accent == "en-US" }
        val current = ArrayList<Pair<Double, Boolean>>()
        val multi = ArrayList<Pair<Double, Boolean>>()
        val multiGbUs = ArrayList<Pair<Double, Boolean>>()
        val chosenNative = HashMap<String, Int>()
        val chosenNonNative = HashMap<String, Int>()

        for (element in Json.parseToJsonElement(probe.readText()).jsonObject.getValue("rows").jsonArray) {
            val row = element.jsonObject
            val role = row.getValue("role").jsonPrimitive.content
            if (role != "native" && role != "nonnative") continue
            val isNative = role == "native"
            val canon = row.getValue("canon").jsonPrimitive.content
            val hyp = row.getValue("hyp").jsonPrimitive.content
            if (hyp.isBlank()) continue
            val variants = variantsByPrimary?.let { AucProbe.wordVariants(canon, it) }.orEmpty()

            current += PhoneticComparator.calculateScoringResult(
                text = "probe",
                expected = canon,
                actual = hyp,
                expectedWordVariants = variants,
            ).score.toDouble() to isNative
            val best = MultiAccentScorer.score("probe", canon, hyp, all, wordVariants = variants)
            multi += best.result.score.toDouble() to isNative
            (if (isNative) chosenNative else chosenNonNative).merge(best.system.accent, 1, Int::plus)
            multiGbUs += MultiAccentScorer.score("probe", canon, hyp, gbUs, wordVariants = variants)
                .result.score.toDouble() to isNative
        }

        fun hist(map: Map<String, Int>) = map.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" }
        fun mean(list: List<Pair<Double, Boolean>>, native: Boolean) = list.filter { it.second == native }.map { it.first }.average()
        val aucCurrent = AucProbe.auc(current)
        val aucMulti = AucProbe.auc(multi)
        val aucGbUs = AucProbe.auc(multiGbUs)
        println(
            "MULTI_AUC_PROBE set=${AucProbe.PROBE_PATH} rows=${current.size} variants=${variantsByPrimary != null}" +
                " current=$aucCurrent multi=$aucMulti multi_gb_us=$aucGbUs",
        )
        println(
            "MULTI_AUC_PROBE mean native current=${mean(current, true)} multi=${mean(multi, true)}" +
                " | nonnative current=${mean(current, false)} multi=${mean(multi, false)}",
        )
        println("MULTI_AUC_PROBE chosen native=${hist(chosenNative)} nonnative=${hist(chosenNonNative)}")
        if (System.getenv("GLOSSO_MULTI_ACCENT_AUC_STRICT") != null) {
            assertTrue(aucMulti >= aucCurrent, "multi-accent AUC $aucMulti < current $aucCurrent")
        }
    }
}
