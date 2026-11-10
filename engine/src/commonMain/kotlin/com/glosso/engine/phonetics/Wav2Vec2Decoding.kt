package com.glosso.engine.phonetics

import com.glosso.engine.log.EngineLog
import com.glosso.engine.model.PhoneTimingModel
import com.glosso.engine.model.PronunciationQualityModel
import com.glosso.engine.phonetics.quality.ModelInventory
import com.glosso.engine.phonetics.quality.PronunciationQuality
import kotlin.math.exp
import kotlin.math.sqrt
import kotlinx.serialization.json.Json

/*
 * Everything the wav2vec2 recognizer does around the ONNX call: WAV decoding and
 * normalisation before it, CTC decoding, confidences and forced alignment after
 * it. It is pure Kotlin so the JVM/Android recognizer (onnxMain, ONNX Runtime Java
 * API) and the iOS one (iosMain, ONNX Runtime C API) run the exact same code and
 * only the inference call differs. Any change here moves scores: it goes through
 * the bench (tools/engine-bench/).
 */

private const val TAG = "Wav2Vec2Recognizer"

/** Raw model output for one take: [timeSteps] x [vocabSize] logits, row-major. */
class Wav2Vec2Logits(val logits: FloatArray, val timeSteps: Int, val vocabSize: Int)

/** A take ready for the model: the trimmed PCM and its mean/variance-normalised copy. */
internal class Wav2Vec2Input(val pcm: FloatArray, val normalized: FloatArray)

internal object Wav2Vec2Audio {
    /** Sample rate the acoustic model expects and the audio is captured at. */
    const val SAMPLE_RATE = 16_000

    /** Decodes a WAV, rejects silence-too-short-to-score, and normalises it. */
    fun prepare(wavBytes: ByteArray): Wav2Vec2Input? {
        if (wavBytes.size <= 44) return null

        // Locate the PCM data chunk instead of assuming a 44-byte header. Real WAVs
        // carry extra chunks (LIST, filler), which would otherwise be decoded as
        // audio and shift every sample.
        val (pcmStart, pcmLength) = findWavDataChunk(wavBytes) ?: (44 to (wavBytes.size - 44))
        // The model hears 16 kHz PCM. Anything else decoded as if it were (44.1 kHz,
        // 8-bit, float) is played at the wrong speed or as noise and still gets a
        // confident, meaningless score, so it is refused as not evaluable instead.
        // A clip without a format chunk keeps the historical 16 kHz mono reading;
        // a truncated one is refused, since its channel count is unknown.
        val channels = when (val fmt = findWavFormat(wavBytes)) {
            WavFormatChunk.Missing -> 1
            WavFormatChunk.Truncated -> {
                EngineLog.e(TAG, "Truncated WAV fmt chunk; refusing to guess the format")
                return null
            }
            is WavFormatChunk.Present -> {
                if (!fmt.format.isDecodable) {
                    EngineLog.e(TAG, "Unsupported WAV format ${fmt.format}; expected 16-bit PCM at $SAMPLE_RATE Hz")
                    return null
                }
                fmt.format.channels
            }
        }
        val raw = decodePcm16(wavBytes, pcmStart, pcmLength, channels)
        if (raw.isEmpty()) return null

        // Trim the quiet room off both ends first. Otherwise leading/trailing
        // silence skews the mean/variance normalisation the model expects.
        val pcm = SpeechGate.trimSilence(raw)
        if (pcm.isEmpty()) {
            EngineLog.d(TAG, "No speech above the noise floor - not scoring")
            return null
        }
        // Length and loudness are judged on the speech itself: the wide margin
        // kept for the model would let a click pass as long enough and would
        // dilute a quiet word with the silence around it.
        val speech = SpeechGate.trimSilence(raw, marginMs = SpeechGate.GATE_MARGIN_MS)
        if (SpeechGate.isTooShort(speech.size)) {
            EngineLog.d(TAG, "Speech too short after trimming (${speech.size} samples)")
            return null
        }
        val speechRms = SpeechGate.rms(speech)
        if (SpeechGate.isTooQuiet(speechRms)) {
            EngineLog.d(TAG, "Recording is effectively silent (rms=$speechRms)")
            return null
        }

        val mean = pcm.average().toFloat()
        var variance = 0.0
        for (x in pcm) variance += (x - mean).toDouble() * (x - mean)
        variance /= pcm.size
        val std = sqrt(variance).toFloat().coerceAtLeast(1e-9f)
        val normalized = FloatArray(pcm.size) { i -> (pcm[i] - mean) / std }
        return Wav2Vec2Input(pcm, normalized)
    }
}

/**
 * The model's output vocabulary and everything decoded with it: greedy CTC with
 * the speech-likeness gate, per-token confidence, and forced alignment of a
 * reference for timings, prosody and the quality layer.
 */
internal class Wav2Vec2Decoder(
    private val idToToken: Map<Int, String>,
    private val padId: Int,
) {
    val tokenIds: Map<String, Int> = idToToken.entries.associate { (id, token) -> token to id }

    val vocabSize: Int get() = idToToken.size

    /** Recognition plus, when [referenceIpa] is given, its alignment, from one model pass. */
    fun analyze(model: Wav2Vec2Logits, input: Wav2Vec2Input, referenceIpa: String?): AudioAnalysis? {
        val recognition = decode(model) ?: return null
        // Alignment reuses the same model output, so the extra analysis is free.
        val alignment = referenceIpa?.let { align(model, input.pcm, it) }
        return AudioAnalysis(
            recognition = recognition,
            timings = alignment?.timings,
            pcm = input.pcm,
            sampleRate = Wav2Vec2Audio.SAMPLE_RATE,
            quality = alignment?.quality,
        )
    }

    /** Greedy CTC decode with the speech-likeness gate. */
    fun decode(model: Wav2Vec2Logits): Recognition? {
        return try {
            val logits = model.logits
            val timeSteps = model.timeSteps
            val vocabSize = model.vocabSize

            // Greedy decode, keeping the top-1 probability per frame.
            val rawIds = IntArray(timeSteps)
            val topProbs = FloatArray(timeSteps)
            for (t in 0 until timeSteps) {
                val base = t * vocabSize
                var maxIdx = 0
                var maxVal = Float.NEGATIVE_INFINITY
                for (v in 0 until vocabSize) {
                    val score = logits[base + v]
                    if (score > maxVal) {
                        maxVal = score
                        maxIdx = v
                    }
                }
                var expSum = 0.0
                for (v in 0 until vocabSize) expSum += exp((logits[base + v] - maxVal).toDouble())
                rawIds[t] = maxIdx
                topProbs[t] = (1.0 / expSum).toFloat()
            }

            var voicedFrames = 0
            var confidenceSum = 0.0
            for (t in 0 until timeSteps) {
                if (rawIds[t] == padId) continue
                voicedFrames++
                confidenceSum += topProbs[t]
            }
            if (voicedFrames == 0) {
                EngineLog.d(TAG, "No non-blank frames - not scoring")
                return null
            }
            val meanConfidence = (confidenceSum / voicedFrames).toFloat()
            if (!SpeechGate.isSpeechLike(voicedFrames, meanConfidence)) {
                EngineLog.d(
                    TAG,
                    "Not speech-like (voiced=$voicedFrames, confidence=$meanConfidence)",
                )
                return null
            }

            // CTC collapse: group consecutive identical frames into one run and
            // keep the run's peak probability as that phone's confidence. Taking
            // the peak (not the onset frame) matters because a phone's first frame
            // is often a transitional, low-confidence frame.
            val runs = mutableListOf<RecognizedPhone>()
            var t = 0
            while (t < timeSteps) {
                val id = rawIds[t]
                var end = t
                var peak = 0f
                while (end < timeSteps && rawIds[end] == id) {
                    if (topProbs[end] > peak) peak = topProbs[end]
                    end++
                }
                if (id != padId && peak >= SpeechGate.MIN_FRAME_CONFIDENCE) {
                    val token = idToToken[id]
                    if (token != null) runs.add(RecognizedPhone(token, peak))
                }
                t = end
            }

            val phones = mutableListOf<RecognizedPhone>()
            for (run in runs) {
                val token = run.phone
                // Drop special tokens (<pad>, <s>, </s>, <unk>) and Mandarin tone
                // markers (digit-suffixed) the multilingual model occasionally
                // hallucinates on non-Chinese audio.
                if (token.startsWith("<") && token.endsWith(">")) continue
                if (token.any { it.isDigit() }) continue
                if (token == WORD_DELIMITER) {
                    // Word boundary: kept in the IPA string, but it is not a phone
                    // and therefore carries no confidence.
                    phones.add(RecognizedPhone(" ", run.confidence))
                    continue
                }
                val previous = phones.lastOrNull()
                if (shouldFuseToPrevious(token) && previous != null && previous.phone != " ") {
                    phones[phones.lastIndex] =
                        RecognizedPhone(previous.phone + token, previous.confidence)
                } else {
                    phones.add(run)
                }
            }

            val ipa = phones.joinToString("") { it.phone }
                .trim()
                .replace(WHITESPACE, " ")
            Recognition(ipa, phones)
        } catch (e: Exception) {
            EngineLog.e(TAG, "Recognition failed", e)
            null
        }
    }

    /**
     * Places the reference phones on the timeline (forced alignment) and measures
     * duration, energy and pitch for each, so the feedback can talk about stress,
     * rhythm and intonation - not just which sound was wrong.
     */
    private class Alignment(
        val timings: List<PhoneTimingModel>,
        val quality: PronunciationQualityModel?,
    )

    private fun align(model: Wav2Vec2Logits, pcm: FloatArray, referenceIpa: String): Alignment? {
        val reference = resolveReference(referenceIpa) ?: run {
            EngineLog.d(TAG, "Forced alignment skipped: reference has no matching model tokens")
            return null
        }
        return try {
            val frames = SoftmaxFrames(
                logits = model.logits,
                timeSteps = model.timeSteps,
                vocabSize = model.vocabSize,
                blankId = padId,
                idToToken = idToToken,
            )
            val spans = ForcedAligner.align(reference, frames) ?: return null
            val sampleRate = Wav2Vec2Audio.SAMPLE_RATE
            val timings = spans.map { span ->
                val startSample = span.startFrame * ForcedAligner.FRAME_SAMPLES
                val endSample = (span.endFrame * ForcedAligner.FRAME_SAMPLES).coerceAtMost(pcm.size)
                val prosody = ProsodyAnalyzer.measure(pcm, sampleRate, startSample, endSample)
                PhoneTimingModel(
                    phone = span.phone,
                    startMs = span.startFrame * ForcedAligner.FRAME_SAMPLES * 1000 / sampleRate,
                    endMs = endSample * 1000 / sampleRate,
                    confidence = span.posterior,
                    energy = prosody.energy,
                    f0Hz = prosody.f0Hz,
                    voiced = prosody.voiced,
                )
            }
            Alignment(timings, qualityOf(referenceIpa, frames))
        } catch (e: Exception) {
            EngineLog.e(TAG, "Forced alignment failed", e)
            null
        }
    }

    /**
     * The quality decision layer consumes the reference in the *model vocabulary*
     * convention (a diphthong is one token), the convention it was trained and
     * measured on. The prosody timings above keep the phoneme scorer's split
     * convention, so this aligns its own reference over the same frames rather
     * than reusing theirs: feeding the head a different tokenisation would break
     * the parity between the reported AUC and production.
     */
    private fun qualityOf(referenceIpa: String, frames: AcousticFrames): PronunciationQualityModel? {
        val canonical = PhoneticComparator.modelPhones(referenceIpa)
        if (canonical.isEmpty()) return null
        val tokens = ModelInventory.resolveAll(canonical, tokenIds) ?: return null
        val spans = ForcedAligner.align(tokens, frames) ?: return null
        return PronunciationQuality.analyze(canonical, frames, spans)
    }

    /**
     * Turns reference IPA into model tokens. The phonemizer and the acoustic model
     * do not always spell a sound the same way (rhotic schwa, g vs ɡ, length), so
     * each phone falls back to the closest token the vocabulary actually has.
     * Returns null if any phone cannot be represented.
     */
    private fun resolveReference(referenceIpa: String): List<String>? {
        val phones = PhoneticComparator.phonesOf(referenceIpa)
        if (phones.isEmpty()) return null
        return ModelInventory.resolveAll(phones, tokenIds)
    }

    private fun shouldFuseToPrevious(token: String): Boolean {
        if (token.isEmpty() || token == WORD_DELIMITER) return false
        val first = token[0]
        if (first in MODIFIER_PREFIXES) return true
        return first.category == CharCategory.NON_SPACING_MARK
    }

    companion object {
        private const val WORD_DELIMITER = "|"

        private val WHITESPACE = Regex("\\s+")

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Spacing modifiers that belong to the preceding phoneme: length, stress,
         * aspiration, rhoticity, palatalization, labialization, nasalization,
         * voicelessness, release/hold marks. Combining (non-spacing) diacritics are
         * handled generically by the Unicode category.
         */
        private val MODIFIER_PREFIXES = setOf(
            'ː', 'ˑ', 'ʰ', 'ʼ', 'ˈ', 'ˌ', 'ʲ', 'ʷ', 'ˠ', 'ˤ', '˞', 'ˀ'
        )

        /** Builds the decoder from the HuggingFace `vocab.json` (token -> id). */
        fun fromVocabJson(text: String): Wav2Vec2Decoder {
            val tokenToId: Map<String, Int> = json.decodeFromString(text)
            val map = HashMap<Int, String>(tokenToId.size)
            var pad = 0
            for ((token, id) in tokenToId) {
                map[id] = token
                if (token == "<pad>") pad = id
            }
            return Wav2Vec2Decoder(map, pad)
        }
    }
}
