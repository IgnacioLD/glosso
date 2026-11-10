package com.glosso.engine.accent

import com.glosso.engine.g2p.CmuDictG2P
import com.glosso.engine.g2p.widenReferenceVariants
import com.glosso.engine.phonetics.ModelState
import com.glosso.engine.phonetics.PhoneticComparator
import com.glosso.engine.phonetics.Wav2Vec2PhonemeRecognizer
import java.io.File
import java.util.Base64
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One accent set's outcome, for the wall check. */
internal data class AccentSetReport(
    val key: String,
    val clips: Int,
    val own98: Int,
    val rejected: Int,
    val accent: String = "",
)

/**
 * Why a set is not evidence: no clip was scored at all (an empty manifest or
 * every clip rejected), or the own-system reach-98 rate is below the wall.
 *
 * A set with no scored clip is always a failure, wall or not: the bench turns an
 * empty set into `ACCENT98 ... clips=0` and a PASS indistinguishable from real
 * evidence, and that metric is what turns the multi-accent flag on.
 */
internal fun accentSetFailures(reports: List<AccentSetReport>, wall: Double?): List<String> =
    reports.flatMap { report ->
        val scored = report.clips - report.rejected
        when {
            scored <= 0 -> listOf(
                "${report.key}: no scored clip (clips=${report.clips}, rejected=${report.rejected}): " +
                    "an empty set is not evidence, check the voice key has audio",
            )
            wall != null && report.own98.toDouble() / report.clips < wall ->
                listOf("${report.key} own-system reach-98 ${report.own98}/${report.clips} = ${report.own98.toDouble() / report.clips} < $wall")
            else -> emptyList()
        }
    }

/**
 * Native per accent: each accent's own native clips, scored with multi-accent
 * scoring, must reach 98 against their own system. The owner's metric
 * ("un nativo saca >=98"), split by accent.
 *
 * Each set is one accent's clips, built with
 * `tools/reference-selfscore/make_samples.py --voice <accent>-<gender>`;
 * `tools/multi-accent-bench/run.sh` stages them and runs this test:
 *
 *   GLOSSO_ACCENT_MANIFESTS='en-GB-female=/w/en-GB-female/manifest.json;en-US-male=/w/en-US-male/manifest.json'
 *
 * A key is a voice key ("<accent>-<gender>") or a bare accent. For every clip it
 * reports the score against its own accent's system (`own`), the multi-accent
 * score and the winning system (`best`), and today's production score
 * (`legacy`). The published content IPA is the primary CMUdict reading, the
 * canonical every system realizes, exactly as `/v1/score` does with the flag on.
 *
 * Report-only by default: `GLOSSO_ACCENT_MIN_RATE=0.9` makes the own-system
 * reach-98 rate a wall. A set that scores no clip at all (empty manifest or every
 * clip rejected) always fails, wall or not: it is not evidence. Skips without
 * manifests unless `GLOSSO_REQUIRE_REFERENCE_SAMPLE` is set.
 */
class MultiAccentNativeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val minScore = 98

    private data class Clip(
        val id: String,
        val text: String,
        val ipa: String,
        val wav: File,
        val voice: String,
        /** Whole-sentence references per accent published with the item, when the manifest carries them. */
        val accentIpa: Map<String, String>,
    )

    /**
     * A set key is a voice key ("<accent>-<gender>") or a bare accent, optionally
     * suffixed with "@<tag>" to tell several voices of one system apart in the
     * synthetic auto-consistency bench; the part before "@" is what resolves the
     * accent.
     */
    private fun sets(): List<Pair<String, File>> =
        System.getenv("GLOSSO_ACCENT_MANIFESTS").orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { entry ->
                val (accent, path) = entry.split('=', limit = 2).also {
                    require(it.size == 2) { "GLOSSO_ACCENT_MANIFESTS entry must be accent=path: $entry" }
                }
                accent.trim() to File(path.trim())
            }

    private fun clips(manifest: File): List<Clip> =
        json.parseToJsonElement(manifest.readText()).jsonArray.map { element ->
            val obj = element.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            Clip(
                id = id,
                text = obj.getValue("text").jsonPrimitive.content,
                ipa = obj.getValue("ipa").jsonPrimitive.content,
                wav = File(obj["wav"]?.jsonPrimitive?.content ?: File(manifest.parentFile, "$id.wav").path),
                voice = obj["voice"]?.jsonPrimitive?.content.orEmpty(),
                accentIpa = obj["accentIpa"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
            )
        }

    @Test
    fun `native clips of each accent reach 98 against their own system`() {
        val model = File(System.getenv("GLOSSO_MODEL") ?: "../data/assets/wav2vec2_phoneme_int8_dyn_pertensor.onnx")
        val vocab = File(System.getenv("GLOSSO_VOCAB") ?: "../data/assets/phoneme_vocab.json")
        val cmudict = File(System.getenv("GLOSSO_CMUDICT") ?: "../data/assets/cmudict.dict")
        val require = System.getenv("GLOSSO_REQUIRE_REFERENCE_SAMPLE") != null
        val wall = System.getenv("GLOSSO_ACCENT_MIN_RATE")?.toDoubleOrNull()

        val sets = sets()
        val reason = when {
            sets.isEmpty() -> "GLOSSO_ACCENT_MANIFESTS is not set (tools/multi-accent-bench/run.sh)"
            sets.any { !it.second.isFile } -> "manifest not found: ${sets.first { !it.second.isFile }.second}"
            !model.isFile -> "model not found at $model"
            !vocab.isFile -> "vocab not found at $vocab"
            !cmudict.isFile -> "cmudict not found at $cmudict"
            else -> null
        }
        if (reason != null) {
            if (require) error("GLOSSO_REQUIRE_REFERENCE_SAMPLE is set but $reason")
            println("Multi-accent native bench skipped: $reason")
            return
        }

        val g2p = CmuDictG2P.fromLines(cmudict.readLines())
        val systems = AccentCatalog.ENGLISH
        val recognizer = Wav2Vec2PhonemeRecognizer(model, vocab)
        recognizer.initialize()
        val reports = mutableListOf<AccentSetReport>()
        try {
            assertEquals(ModelState.READY, recognizer.state)
            for ((key, manifest) in sets) {
                val lookup = key.substringBefore('@')
                val accent = AccentCatalog.system(lookup)?.accent ?: lookup
                assertTrue(systems.any { it.accent == accent }, "no system for accent $accent")
                val list = clips(manifest)
                var own98 = 0
                var best98 = 0
                var legacy98 = 0
                var rejected = 0
                val chosen = HashMap<String, Int>()
                for (clip in list) {
                    val analysis = recognizer.analyze(Base64.getEncoder().encodeToString(clip.wav.readBytes()), clip.ipa)
                    if (analysis == null) {
                        rejected++
                        println("ACCENT_NATIVE|$key|${clip.id}|rejected")
                        continue
                    }
                    val actual = analysis.recognition.ipa
                    val confidences = PhoneticComparator.phoneConfidencesOf(analysis.recognition.phones)
                    val pronunciations = g2p.pronunciations(clip.text)
                    val variants = widenReferenceVariants(clip.ipa, pronunciations.wordVariants)
                    val result = MultiAccentScorer.score(
                        text = clip.text,
                        canonicalIpa = clip.ipa,
                        actual = actual,
                        systems = systems,
                        actualConfidence = confidences,
                        wordVariants = variants,
                        accentReferences = clip.accentIpa,
                        target = AccentTarget(accent),
                    )
                    val own = result.candidates.filter { it.system.accent == accent }.maxOf { it.result.score }
                    val legacy = PhoneticComparator.calculateScoringResult(
                        text = clip.text,
                        expected = clip.ipa,
                        actual = actual,
                        language = "en_GB",
                        actualConfidence = confidences,
                        expectedWordVariants = variants,
                    ).score
                    if (own >= minScore) own98++
                    if (result.result.score >= minScore) best98++
                    if (legacy >= minScore) legacy98++
                    chosen.merge(result.system.accent, 1, Int::plus)
                    println(
                        "ACCENT_NATIVE|$key|${clip.id}|own=$own|best=${result.result.score}|system=${result.system.id}" +
                            "|legacy=$legacy|actual=$actual|reference=${result.candidates.first { it.system.accent == accent }.reference}",
                    )
                }
                val n = list.size
                val rate = if (n == 0) 0.0 else own98.toDouble() / n
                println(
                    "ACCENT98|set=$key|accent=$accent|voice=${list.firstOrNull()?.voice.orEmpty()}|clips=$n|rejected=$rejected" +
                        "|own98=$own98|best98=$best98|legacy98=$legacy98" +
                        "|own_rate=${"%.3f".format(Locale.ROOT, rate)}|chosen=${chosen.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" }}" +
                        "|calibrated=${systems.first { it.accent == accent }.phonology.calibrated}",
                )
                reports += AccentSetReport(key, n, own98, rejected, accent)
            }
        } finally {
            recognizer.close()
        }
        for ((accent, accentReports) in reports.groupBy { it.accent }.toSortedMap()) {
            val clips = accentReports.sumOf { it.clips }
            val own = accentReports.sumOf { it.own98 }
            val rejected = accentReports.sumOf { it.rejected }
            val rate = if (clips == 0) 0.0 else own.toDouble() / clips
            println(
                "ACCENT98_ACCENT|accent=$accent|voices=${accentReports.size}|clips=$clips|rejected=$rejected|own98=$own" +
                    "|own_rate=${"%.3f".format(Locale.ROOT, rate)}",
            )
        }
        val failures = accentSetFailures(reports, wall)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `a set with no scored clip can never pass, wall or not`() {
        val empty = listOf(AccentSetReport("en-IE-male", clips = 0, own98 = 0, rejected = 0))
        assertTrue(accentSetFailures(empty, wall = null).isNotEmpty(), "0 clips must fail without a wall")
        assertTrue(accentSetFailures(empty, wall = 0.9).isNotEmpty())

        val allRejected = listOf(AccentSetReport("en-IE-male", clips = 5, own98 = 0, rejected = 5))
        assertTrue(accentSetFailures(allRejected, wall = null).isNotEmpty(), "every clip rejected is not evidence")

        val report = listOf(AccentSetReport("en-GB-female", clips = 42, own98 = 30, rejected = 0))
        assertTrue(accentSetFailures(report, wall = null).isEmpty(), "report-only does not gate a set with scored clips")
        assertTrue(accentSetFailures(report, wall = 0.9).isNotEmpty())
        assertTrue(accentSetFailures(report, wall = 0.7).isEmpty())
    }
}
