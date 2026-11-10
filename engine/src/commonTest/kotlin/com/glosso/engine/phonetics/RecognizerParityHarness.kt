package com.glosso.engine.phonetics

import com.glosso.engine.g2p.CmuDictG2P
import com.glosso.engine.g2p.G2P
import com.glosso.engine.g2p.widenReferenceVariants
import com.glosso.engine.model.LanguagePacks
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.engine.phonetics.cheap.CheapScorer
import kotlin.time.TimeSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** File and environment access the parity harness needs from each platform's tests. */
internal interface ParityIo {
    val name: String

    fun env(name: String): String?

    fun readBytes(path: String): ByteArray

    fun readText(path: String): String

    fun writeText(path: String, text: String)

    fun writeBytes(path: String, bytes: ByteArray)

    fun mkdirs(path: String)

    /** Peak memory footprint of this process so far, in bytes (null when unknown). */
    fun peakMemoryBytes(): Long?

    /** Current memory footprint of this process, in bytes (null when unknown). */
    fun footprintBytes(): Long?
}

internal expect val ParityPlatform: ParityIo

/**
 * Cross-platform recognizer parity: runs the same clips through one platform's
 * real recognizer (JVM/Android in onnxMain, iOS in iosMain) and the server's
 * scoring chain, and writes one JSON line per clip with everything that could
 * drift: the transcript, every token confidence (as raw float bits), the forced
 * alignment, the final score and the full feedback the server would return.
 * `tools/score-parity/compare-ios.py` diffs the JVM and iOS outputs.
 *
 * Skips unless GLOSSO_PARITY_MANIFESTS (comma-separated `make_samples.py`
 * manifests) and GLOSSO_PARITY_OUT are set; `tools/score-parity/run-ios-parity.sh`
 * stages both sides.
 */
internal object RecognizerParityHarness {

    private val json = Json { encodeDefaults = true }

    class Clip(val id: String, val text: String, val ipa: String, val wav: String)

    /** What a platform test plugs in: its recognizer, behind the shared contract. */
    interface Subject {
        fun initialize(): Boolean

        fun analyze(base64Wav: String, referenceIpa: String): AudioAnalysis?

        fun logits(wavBytes: ByteArray): Wav2Vec2Logits?

        fun close()
    }

    fun runIfConfigured(open: (model: String, vocab: String) -> Subject) {
        val manifests = ParityPlatform.env("GLOSSO_PARITY_MANIFESTS")?.takeIf { it.isNotBlank() }
        val out = ParityPlatform.env("GLOSSO_PARITY_OUT")?.takeIf { it.isNotBlank() }
        val model = ParityPlatform.env("GLOSSO_MODEL")
        val vocab = ParityPlatform.env("GLOSSO_VOCAB")
        val cmudict = ParityPlatform.env("GLOSSO_CMUDICT")
        if (manifests == null || out == null || model == null || vocab == null || cmudict == null) {
            println(
                "Recognizer parity (${ParityPlatform.name}) skipped: set GLOSSO_PARITY_MANIFESTS, " +
                    "GLOSSO_PARITY_OUT, GLOSSO_MODEL, GLOSSO_VOCAB and GLOSSO_CMUDICT " +
                    "(tools/score-parity/run-ios-parity.sh)",
            )
            return
        }
        val clips = manifests.split(',').filter { it.isNotBlank() }.flatMap { clipsOf(it.trim()) }
        check(clips.isNotEmpty()) { "no clips in $manifests" }
        val g2p = CmuDictG2P.fromLines(ParityPlatform.readText(cmudict).lines())
        val logitsDir = "$out.logits"
        ParityPlatform.mkdirs(logitsDir)

        val memBefore = ParityPlatform.footprintBytes()
        val loadStart = TimeSource.Monotonic.markNow()
        val subject = open(model, vocab)
        check(subject.initialize()) { "recognizer failed to load $model" }
        val loadMs = loadStart.elapsedNow().inWholeMilliseconds
        val memLoaded = ParityPlatform.footprintBytes()
        val peakLoaded = ParityPlatform.peakMemoryBytes()

        var memScored: Long? = null
        val lines = StringBuilder()
        val inferenceMs = ArrayList<Long>()
        try {
            for (clip in clips) {
                val wav = ParityPlatform.readBytes(clip.wav)
                val base64 = kotlin.io.encoding.Base64.encode(wav)
                val start = TimeSource.Monotonic.markNow()
                val analysis = subject.analyze(base64, clip.ipa)
                val took = start.elapsedNow().inWholeMilliseconds
                if (analysis != null) inferenceMs += took
                val logits = subject.logits(wav)
                if (logits != null) {
                    ParityPlatform.writeBytes("$logitsDir/${clip.id}.f32", floatsToBytes(logits.logits))
                }
                lines.append(json.encodeToString(JsonObject.serializer(), record(clip, analysis, logits, g2p, took)))
                    .append('\n')
            }
        } finally {
            memScored = ParityPlatform.footprintBytes()
            subject.close()
        }
        ParityPlatform.writeText(out, lines.toString())

        val sorted = inferenceMs.sorted()
        val summary = buildJsonObject {
            put("platform", ParityPlatform.name)
            put("clips", clips.size)
            put("load_ms", loadMs)
            put("analyze_ms_median", sorted.getOrNull(sorted.size / 2) ?: -1)
            put("analyze_ms_max", sorted.lastOrNull() ?: -1)
            put("analyze_ms_total", sorted.sum())
            put("footprint_before_load", memBefore ?: -1)
            put("footprint_after_load", memLoaded ?: -1)
            put("peak_after_load", peakLoaded ?: -1)
            put("footprint_after_scoring", memScored ?: -1)
            put("peak_after_scoring", ParityPlatform.peakMemoryBytes() ?: -1)
        }
        ParityPlatform.writeText("$out.summary.json", summary.toString())
        println("RECOGNIZER_PARITY|${ParityPlatform.name}|$summary")
    }

    private fun clipsOf(manifest: String): List<Clip> =
        Json.parseToJsonElement(ParityPlatform.readText(manifest)).jsonArray.map {
            val o = it.jsonObject
            Clip(
                id = o.getValue("id").jsonPrimitive.content,
                text = o.getValue("text").jsonPrimitive.content,
                ipa = o.getValue("ipa").jsonPrimitive.content,
                wav = o.getValue("wav").jsonPrimitive.content,
            )
        }

    /** The server's /v1/score chain (multi-accent and prosody bonus off, their defaults). */
    private fun feedbackOf(clip: Clip, analysis: AudioAnalysis, g2p: G2P): PronunciationFeedback {
        val pack = LanguagePacks.forScoring(LANGUAGE)
        val variants = widenReferenceVariants(clip.ipa, g2p.pronunciations(clip.text).wordVariants)
        val result = PhoneticComparator.calculateScoringResult(
            text = clip.text,
            expected = clip.ipa,
            actual = analysis.recognition.ipa,
            language = pack.code,
            actualConfidence = PhoneticComparator.phoneConfidencesOf(analysis.recognition.phones),
            expectedWordVariants = variants,
        )
        val timings = analysis.timings.orEmpty()
        val stress = StressAnalyzer.analyze(
            timings = timings,
            referenceWords = PhoneticComparator.referenceWords(clip.ipa),
        )
        val cheap = analysis.pcm?.let { pcm ->
            CheapScorer.analyze(DoubleArray(pcm.size) { pcm[it].toDouble() }, analysis.sampleRate, timings)
        }
        return FeedbackAssembler.assemble(
            text = clip.text,
            expectedIpa = clip.ipa,
            result = result,
            targetLanguage = pack.code,
            uiLanguage = "en",
            timings = timings,
            profile = PhoneticProfile.fold(null, result.alignment),
            stress = stress,
            fluency = cheap?.fluency,
            acoustics = cheap?.acoustics.orEmpty(),
            quality = analysis.quality,
        )
    }

    private fun record(clip: Clip, analysis: AudioAnalysis?, logits: Wav2Vec2Logits?, g2p: G2P, ms: Long): JsonObject =
        buildJsonObject {
            put("id", clip.id)
            put("analyze_ms", ms)
            if (logits != null) {
                put("time_steps", logits.timeSteps)
                put("vocab_size", logits.vocabSize)
            }
            if (analysis == null) {
                put("evaluated", false)
                return@buildJsonObject
            }
            val feedback = feedbackOf(clip, analysis, g2p)
            put("evaluated", feedback.evaluated)
            put("ipa", analysis.recognition.ipa)
            put("phones", buildJsonArray {
                analysis.recognition.phones.forEach { p ->
                    add(buildJsonArray {
                        add(JsonPrimitive(p.phone))
                        add(JsonPrimitive(p.confidence.toRawBits()))
                    })
                }
            })
            put("timings", buildJsonArray {
                analysis.timings.orEmpty().forEach { t ->
                    add(buildJsonArray {
                        add(JsonPrimitive(t.phone))
                        add(JsonPrimitive(t.startMs))
                        add(JsonPrimitive(t.endMs))
                        add(JsonPrimitive(t.confidence.toRawBits()))
                        add(JsonPrimitive(t.energy.toRawBits()))
                        add(JsonPrimitive(t.f0Hz?.toRawBits()))
                        add(JsonPrimitive(t.voiced))
                    })
                }
            })
            put("score", feedback.score)
            put("feedback", json.encodeToJsonElement(PronunciationFeedback.serializer(), feedback))
        }

    private fun floatsToBytes(values: FloatArray): ByteArray {
        val out = ByteArray(values.size * 4)
        for (i in values.indices) {
            val bits = values[i].toRawBits()
            out[i * 4] = bits.toByte()
            out[i * 4 + 1] = (bits shr 8).toByte()
            out[i * 4 + 2] = (bits shr 16).toByte()
            out[i * 4 + 3] = (bits shr 24).toByte()
        }
        return out
    }

    private const val LANGUAGE = "en_GB"
}
