package com.glosso.engine.phonetics

import com.glosso.engine.g2p.CmuDictG2P
import com.glosso.engine.g2p.G2P
import com.glosso.engine.g2p.widenReferenceVariants
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The reference self-score oracle: the engine's own reference audio, scored
 * against the expected IPA of its own text, must come out at ~100.
 *
 * This is the floor under every claim the app makes. If the clip the learner
 * hears in "Normal / Lenta" does not score perfect against the reference the
 * scorer grades with, then the score is measuring the accent mismatch between
 * the TTS and the dictionary, not the learner, and neither praise nor
 * correction can be trusted.
 *
 * Two criteria, two fixed sets, both built locally (they are content, not code,
 * and do not live in git) with `tools/reference-selfscore/make_samples.py`:
 *
 *  1. `GLOSSO_REFERENCE_MANIFEST` (curated `golden-ids.txt`): **every** clip
 *     reaches 98. The regression floor for the oracle.
 *  2. `GLOSSO_NATIVE_MANIFEST` (broad `tools/engine-bench/native-ids.txt`): the
 *     share of clips reaching 98 must be at least `minReach98Rate`. This is the
 *     owner's metric - "lo importante es que un nativo vaya bien, le de 98 como
 *     minimo" - measured on a set that is NOT curated to pass, so the clips the
 *     model mis-hears count against it.
 *
 * The tests skip unless pointed at a manifest (the fallback is the default output
 * path, so a staged run needs no env at all):
 *
 *   GLOSSO_REFERENCE_MANIFEST=/tmp/glosso-refscore/manifest.json \
 *   GLOSSO_MODEL=.../wav2vec2_phoneme_int8_dyn_pertensor.onnx \
 *   GLOSSO_VOCAB=.../phoneme_vocab.json \
 *   GLOSSO_CMUDICT=.../cmudict.dict \
 *       ./gradlew :engine:jvmTest --tests '*ReferenceSelfScoreTest*'
 *
 * `GLOSSO_REQUIRE_REFERENCE_SAMPLE=1` turns a missing manifest or asset into a
 * hard failure, so a run that stages them (`tools/engine-bench/run.sh`) cannot
 * pass by skipping.
 */
class ReferenceSelfScoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Every clip in the curated oracle must reach this score. */
    private val minScore = 98

    /**
     * The owner's metric wall: at least this share of the broad native set must
     * reach [minScore]. "98 no necesariamente pero mas de 90 o 95 si"; 95% is the
     * stretch, 90% is the gate.
     */
    private val minReach98Rate = 0.90

    private fun asset(env: String, default: String): File = File(System.getenv(env) ?: default)

    private data class Sample(
        val id: String,
        val kind: String,
        val text: String,
        val ipa: String,
        val wav: File,
    )

    private data class Scored(
        val sample: Sample,
        val actual: String?,
        val result: ScoringResult?,
    )

    private fun samples(env: String): List<Sample>? {
        val path = System.getenv(env) ?: return null
        val manifestFile = File(path)
        if (!manifestFile.isFile) return null
        val root = manifestFile.parentFile
        return json.parseToJsonElement(manifestFile.readText()).jsonArray.map { element ->
            val obj = element.jsonObject
            Sample(
                id = obj.getValue("id").jsonPrimitive.content,
                kind = obj.getValue("kind").jsonPrimitive.content,
                text = obj.getValue("text").jsonPrimitive.content,
                ipa = obj.getValue("ipa").jsonPrimitive.content,
                wav = File(obj["wav"]?.jsonPrimitive?.content ?: File(root, "${obj.getValue("id").jsonPrimitive.content}.wav").path),
            )
        }
    }

    /** Null when the run is fully staged; otherwise why it must be skipped. */
    private fun skipReason(sampleList: List<Sample>?, manifestEnv: String, model: File, vocab: File, cmudict: File): String? =
        when {
            System.getenv(manifestEnv).isNullOrBlank() ->
                "$manifestEnv is not set (build the sample with tools/reference-selfscore)"
            sampleList == null -> "reference sample manifest not found at ${System.getenv(manifestEnv)}"
            sampleList.isEmpty() -> "reference sample manifest is empty"
            !model.isFile -> "model not found at $model"
            !vocab.isFile -> "vocab not found at $vocab"
            !cmudict.isFile -> "cmudict not found at $cmudict"
            else -> null
        }

    private fun <T> withScorer(model: File, vocab: File, cmudict: File, block: (Wav2Vec2PhonemeRecognizer, G2P) -> T): T {
        val recognizer = Wav2Vec2PhonemeRecognizer(model, vocab)
        recognizer.initialize()
        if (System.getenv("GLOSSO_DEBUG") != null) {
            com.glosso.engine.log.EngineLog.enabled = true
        }
        try {
            assertEquals(ModelState.READY, recognizer.state)
            return block(recognizer, CmuDictG2P.fromLines(cmudict.readLines()))
        } finally {
            recognizer.close()
        }
    }

    private fun scoreAll(
        sampleList: List<Sample>,
        recognizer: Wav2Vec2PhonemeRecognizer,
        g2p: G2P,
    ): List<Scored> = sampleList.map { sample ->
        val audio = Base64.getEncoder().encodeToString(sample.wav.readBytes())
        val analysis = recognizer.analyze(audio, sample.ipa)
        if (analysis == null) {
            Scored(sample, null, null)
        } else {
            val actual = analysis.recognition.ipa
            val confidences = PhoneticComparator.phoneConfidencesOf(analysis.recognition.phones)
            val variants = widenReferenceVariants(sample.ipa, g2p.pronunciations(sample.text).wordVariants)
            val result = PhoneticComparator.calculateScoringResult(
                text = sample.text,
                expected = sample.ipa,
                actual = actual,
                language = "en_GB",
                actualConfidence = confidences,
                expectedWordVariants = variants,
                dialectWidening = true,
                distinctVariantCap = BenchFlags.distinctVariantCap,
            )
            Scored(sample, actual, result)
        }
    }

    @Test
    fun `the reference audio scores perfect against its own expected IPA`() {
        val model = asset("GLOSSO_MODEL", "../data/assets/wav2vec2_phoneme_int8_dyn_pertensor.onnx")
        val vocab = asset("GLOSSO_VOCAB", "../data/assets/phoneme_vocab.json")
        val cmudict = asset("GLOSSO_CMUDICT", "../data/assets/cmudict.dict")
        val require = System.getenv("GLOSSO_REQUIRE_REFERENCE_SAMPLE") != null

        val sampleList = samples("GLOSSO_REFERENCE_MANIFEST")
        val reason = skipReason(sampleList, "GLOSSO_REFERENCE_MANIFEST", model, vocab, cmudict)
        if (reason != null) {
            if (require) error("GLOSSO_REQUIRE_REFERENCE_SAMPLE is set but $reason")
            println("Reference self-score oracle skipped: $reason")
            return
        }

        withScorer(model, vocab, cmudict) { recognizer, g2p ->
            val failures = mutableListOf<String>()
            var total = 0
            var sum = 0
            var perfect = 0
            var worst: Triple<String, Int, String>? = null

            scoreAll(sampleList!!, recognizer, g2p).forEach { r ->
                if (r.actual == null || r.result == null) {
                    failures += "${r.sample.id}: no usable speech (gate rejected the reference clip)"
                    return@forEach
                }
                total++
                sum += r.result.score
                if (r.result.score >= minScore) perfect++
                if (worst == null || r.result.score < worst.second) {
                    worst = Triple(r.sample.id, r.result.score, r.actual)
                }
                println(
                    "REFSCORE|${r.sample.kind}|${r.sample.id}|${r.result.score}|${r.result.level}" +
                        "|mastery=${r.result.isMastery}|expected=${r.sample.ipa}|actual=${r.actual}",
                )
                if (r.result.score < minScore) {
                    val missed = r.result.alignment.filter { it.status != MatchStatus.PERFECT }
                    failures += "${r.sample.id} (${r.sample.kind}) scored ${r.result.score} < $minScore\n" +
                        "    text     : ${r.sample.text}\n" +
                        "    expected : ${r.sample.ipa}\n" +
                        "    actual   : ${r.actual}\n" +
                        "    divergent: $missed"
                }
            }

            val mean = if (total == 0) 0 else sum / total
            println("REFSCORE_SUMMARY|clips=$total|>=${minScore}=$perfect|mean=$mean|worst=$worst|${BenchFlags.describe()}")

            if (failures.isNotEmpty()) {
                error(
                    "The engine fails its own reference audio: ${failures.size}/$total clips " +
                        "below $minScore (mean $mean).\n" + failures.joinToString("\n")
                )
            }
        }
    }

    /**
     * The owner's metric: of the fixed, non-curated native reference set, how many
     * reach 98. This is the number to bring down the failure rate of, so it is a
     * hard gate, not an oracle over an already-passing set.
     */
    @Test
    fun `native reference clips reach 98 at the required rate`() {
        val model = asset("GLOSSO_MODEL", "../data/assets/wav2vec2_phoneme_int8_dyn_pertensor.onnx")
        val vocab = asset("GLOSSO_VOCAB", "../data/assets/phoneme_vocab.json")
        val cmudict = asset("GLOSSO_CMUDICT", "../data/assets/cmudict.dict")
        val require = System.getenv("GLOSSO_REQUIRE_REFERENCE_SAMPLE") != null

        val sampleList = samples("GLOSSO_NATIVE_MANIFEST")
        val reason = skipReason(sampleList, "GLOSSO_NATIVE_MANIFEST", model, vocab, cmudict)
        if (reason != null) {
            if (require) error("GLOSSO_REQUIRE_REFERENCE_SAMPLE is set but $reason")
            println("Native-98 benchmark skipped: $reason")
            return
        }

        withScorer(model, vocab, cmudict) { recognizer, g2p ->
            val scored = scoreAll(sampleList!!, recognizer, g2p)
            val reach98 = scored.count { it.result != null && it.result.score >= minScore }
            val n = scored.size
            val rate = if (n == 0) 0.0 else reach98.toDouble() / n
            val rejected = scored.count { it.actual == null }
            // One line per clip that misses, so a failing run says which clips and
            // which sounds to look at instead of only a rate.
            scored.filter { it.result == null || it.result.score < minScore }.forEach { r ->
                val divergent = r.result?.alignment
                    ?.filter { it.status != MatchStatus.PERFECT }
                    ?.joinToString(" ") { "${it.expected}>${it.actual}" }
                println(
                    "NATIVE98_MISS|${r.sample.kind}|${r.sample.id}|${r.result?.score ?: "rejected"}|${r.result?.level}" +
                        "|expected=${r.sample.ipa}|actual=${r.actual}|divergent=$divergent",
                )
            }
            // The verdict the learner would see for each native clip, next to the
            // score: a native take should read as good, not only score high.
            val verdicts = scored.groupingBy { it.result?.level?.name ?: "REJECTED" }.eachCount()
            println("NATIVE_VERDICT|clips=$n|${verdicts.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }}")
            println("NATIVE98|set=${System.getenv("GLOSSO_NATIVE_MANIFEST")}|clips=$n|reach98=$reach98|rejected=$rejected|rate=$rate|wall=$minReach98Rate|${BenchFlags.describe()}")
            assertTrue(
                rate >= minReach98Rate,
                "native reference clips reaching $minScore: $reach98/$n = $rate < $minReach98Rate",
            )
        }
    }
}
