package com.glosso.engine.phonetics

import com.glosso.engine.g2p.CmuDictG2P
import com.glosso.engine.g2p.widenReferenceVariants
import com.glosso.engine.model.ProsodyBonusModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The prosody-bonus probe: real audio, same item on both sides. It reports how
 * many extra points each kind of take earns against the reference the app plays,
 * and checks the contract that the bonus never changes the sound score.
 *
 * Conditions (see `tools/prosody-bonus/make_pairs.py`):
 *  - `self`: the reference against itself (must be the maximum).
 *  - `other_voice`: another native voice of the same text, normal speed.
 *  - `slow`: the reference voice at slow speed.
 *  - `halting`: `other_voice` with [HALT_MS] of silence spliced between words,
 *    a synthetic word-by-word reading.
 *
 * No learner (L2) audio exists locally, so this measures how the bonus treats
 * natives and a disfluency, not the native/non-native separation. Skips unless
 * `GLOSSO_PROSODY_PAIRS` points at `pairs.json`:
 *
 *   python3 tools/prosody-bonus/make_pairs.py --db .../content.db \
 *       --audio-root .../content/en/audio --out build/prosody-bonus
 *   GLOSSO_PROSODY_PAIRS=$PWD/build/prosody-bonus/pairs.json \
 *   GLOSSO_MODEL=... GLOSSO_VOCAB=... GLOSSO_CMUDICT=... \
 *       ./gradlew :engine:jvmTest --tests '*ProsodyBonusProbeTest*'
 */
class ProsodyBonusProbeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private data class TakeFile(val condition: String, val wav: File)
    private data class Pair(val id: String, val text: String, val ipa: String, val reference: File, val takes: List<TakeFile>)
    private data class Row(val id: String, val condition: String, val score: Int, val bonus: ProsodyBonusModel?)

    private fun pairs(path: String): List<Pair> =
        json.parseToJsonElement(File(path).readText()).jsonArray.map { e ->
            val o = e.jsonObject
            Pair(
                id = o.getValue("id").jsonPrimitive.content,
                text = o.getValue("text").jsonPrimitive.content,
                ipa = o.getValue("ipa").jsonPrimitive.content,
                reference = File(o.getValue("reference").jsonPrimitive.content),
                takes = o.getValue("takes").jsonArray.map { t ->
                    val to = t.jsonObject
                    TakeFile(to.getValue("condition").jsonPrimitive.content, File(to.getValue("wav").jsonPrimitive.content))
                },
            )
        }

    @Test
    fun `prosody bonus on native pairs`() {
        val path = System.getenv("GLOSSO_PROSODY_PAIRS")
        val model = File(System.getenv("GLOSSO_MODEL") ?: "../data/assets/wav2vec2_phoneme_int8_dyn_pertensor.onnx")
        val vocab = File(System.getenv("GLOSSO_VOCAB") ?: "../data/assets/phoneme_vocab.json")
        val cmudict = File(System.getenv("GLOSSO_CMUDICT") ?: "../data/assets/cmudict.dict")
        if (path.isNullOrBlank() || !File(path).isFile || !model.isFile || !vocab.isFile || !cmudict.isFile) {
            println("Prosody bonus probe skipped: set GLOSSO_PROSODY_PAIRS and the model assets")
            return
        }
        val recognizer = Wav2Vec2PhonemeRecognizer(model, vocab)
        recognizer.initialize()
        val g2p = CmuDictG2P.fromLines(cmudict.readLines())
        val rows = mutableListOf<Row>()
        try {
            for (pair in pairs(path)) {
                val refAnalysis = recognizer.analyze(b64(pair.reference.readBytes()), pair.ipa) ?: continue
                val refTimings = refAnalysis.timings ?: continue
                val reference = ProsodyBonus.take(refTimings, refAnalysis.pcm, refAnalysis.sampleRate)

                fun score(wavBytes: ByteArray, condition: String): AudioAnalysis? {
                    val analysis = recognizer.analyze(b64(wavBytes), pair.ipa) ?: run {
                        rows += Row(pair.id, condition, -1, null)
                        return null
                    }
                    val result = PhoneticComparator.calculateScoringResult(
                        text = pair.text,
                        expected = pair.ipa,
                        actual = analysis.recognition.ipa,
                        language = "en_GB",
                        actualConfidence = PhoneticComparator.phoneConfidencesOf(analysis.recognition.phones),
                        expectedWordVariants = widenReferenceVariants(pair.ipa, g2p.pronunciations(pair.text).wordVariants),
                        dialectWidening = true,
                    )
                    val bonus = analysis.timings?.let {
                        ProsodyBonus.evaluate(reference, ProsodyBonus.take(it, analysis.pcm, analysis.sampleRate), result.completeness)
                    }
                    // The contract: the assembled feedback differs only in the prosody field.
                    val without = FeedbackAssembler.assemble(pair.text, pair.ipa, result)
                    val with = FeedbackAssembler.assemble(pair.text, pair.ipa, result, prosody = bonus)
                    assertEquals(without.score, with.score)
                    assertEquals(without, with.copy(prosody = null))
                    rows += Row(pair.id, condition, result.score, bonus)
                    return analysis
                }

                score(pair.reference.readBytes(), "self")
                for (take in pair.takes) {
                    val analysis = score(take.wav.readBytes(), take.condition) ?: continue
                    if (take.condition == "other_voice") {
                        halting(analysis, pair.ipa)?.let { score(it, "halting") }
                    }
                }
            }
        } finally {
            recognizer.close()
        }

        println("prosody bonus probe (max ${ProsodyBonus.MAX_POINTS}, floor ${ProsodyBonus.SHAPE_FLOOR}, full ${ProsodyBonus.SHAPE_FULL})")
        for (condition in listOf("self", "other_voice", "slow", "halting")) {
            val all = rows.filter { it.condition == condition }
            val measured = all.mapNotNull { it.bonus }
            if (all.isEmpty()) continue
            val hist = (0..ProsodyBonus.MAX_POINTS).joinToString(" ") { p -> "$p:${measured.count { it.points == p }}" }
            println(
                String.format(
                    Locale.ROOT,
                    "%-12s n=%2d applied=%2d points mean=%.2f [%s] shape=%.3f rhythm=%.3f tempo=%.3f pauses=%s stress=%.3f inton=%s",
                    condition, all.size, measured.size,
                    measured.map { it.points }.average(), hist,
                    measured.map { it.shape }.average(),
                    measured.map { it.rhythm }.average(),
                    measured.map { it.tempo }.average(),
                    avg(measured.mapNotNull { it.pauses }),
                    measured.map { it.stress }.average(),
                    avg(measured.mapNotNull { it.intonation }),
                ),
            )
        }
        System.getenv("GLOSSO_PROSODY_REPORT")?.let { out ->
            File(out).writeText(
                rows.joinToString("\n", prefix = "id\tcondition\tscore\tpoints\tshape\trhythm\ttempo\tpauses\tstress\tintonation\ttempo_ratio\textra_pause_ms\n") { r ->
                    val b = r.bonus
                    listOf(r.id, r.condition, r.score, b?.points, b?.shape, b?.rhythm, b?.tempo, b?.pauses, b?.stress, b?.intonation, b?.tempoRatio, b?.extraPauseMs)
                        .joinToString("\t") { it?.toString() ?: "" }
                } + "\n",
            )
        }
        val self = rows.filter { it.condition == "self" }.mapNotNull { it.bonus }
        assertTrue(self.isNotEmpty() && self.all { it.points == ProsodyBonus.MAX_POINTS }, "the reference against itself must earn the maximum")
    }

    private fun avg(values: List<Float>): String =
        if (values.isEmpty()) "-" else String.format(Locale.ROOT, "%.3f", values.average())

    /** [analysis]'s own PCM with [HALT_MS] of silence spliced at every word start after the first. */
    private fun halting(analysis: AudioAnalysis, ipa: String): ByteArray? {
        val pcm = analysis.pcm ?: return null
        val timings = analysis.timings ?: return null
        val words = PhoneticComparator.referenceWords(ipa).map { it.size }
        if (words.size < 2 || words.sum() != timings.size) return null
        val cuts = mutableListOf<Int>()
        var index = 0
        for (w in words.dropLast(1)) {
            index += w
            cuts += timings[index].startMs * analysis.sampleRate / 1000
        }
        val gap = FloatArray(HALT_MS * analysis.sampleRate / 1000)
        val out = ArrayList<Float>(pcm.size + gap.size * cuts.size)
        var from = 0
        for (cut in cuts) {
            for (i in from until cut.coerceIn(from, pcm.size)) out += pcm[i]
            gap.forEach { out += it }
            from = cut.coerceIn(from, pcm.size)
        }
        for (i in from until pcm.size) out += pcm[i]
        return wav(out.toFloatArray(), analysis.sampleRate)
    }

    private fun wav(pcm: FloatArray, sampleRate: Int): ByteArray {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort((it.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate)
            .putInt(sampleRate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size * 2)
        return ByteArrayOutputStream().apply { write(header.array()); write(data.array()) }.toByteArray()
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val HALT_MS = 500
    }
}
