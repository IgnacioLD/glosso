package com.glosso.engine.phonetics

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.util.Base64
import com.glosso.engine.log.EngineLog
import com.glosso.engine.model.PhonemeModelConfig

/**
 * Phoneme recognizer backed by the multilingual wav2vec2 model (selective int8 ONNX).
 *
 * Shared between the JVM service and the Android app: ONNX Runtime exposes the
 * same Java API on both, and `java.util.Base64` is available from API 26. Only the
 * dependency artifact differs. Pre-processing and decoding live in common code
 * ([Wav2Vec2Audio], [Wav2Vec2Decoder]) and are shared with the iOS recognizer,
 * so this class only loads the model and runs it.
 *
 * The model emits IPA phonemes regardless of the spoken language. It is loaded
 * from disk by [initialize], which is heavy (~600 MB) and must run off the main
 * thread.
 */
class Wav2Vec2PhonemeRecognizer(
    private val modelFile: File,
    private val vocabFile: File,
) : PhonemeRecognizer {

    private val TAG = "Wav2Vec2Recognizer"

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var decoder: Wav2Vec2Decoder? = null

    @Volatile
    private var _state: ModelState = ModelState.UNINITIALIZED

    @Volatile
    override var error: String? = null
        private set

    override val state: ModelState get() = _state

    /**
     * Builds the ONNX session. Safe to call repeatedly and from several threads;
     * an in-flight or finished load is a no-op.
     */
    @Synchronized
    override fun initialize() {
        if (ortSession != null) {
            _state = ModelState.READY
            return
        }
        _state = ModelState.LOADING
        try {
            if (!modelFile.exists() || !vocabFile.exists()) {
                EngineLog.d(
                    TAG,
                    "Model assets missing (model=${modelFile.exists()}, vocab=${vocabFile.exists()})",
                )
                _state = ModelState.UNINITIALIZED
                return
            }

            val env = OrtEnvironment.getEnvironment()
            ortEnv = env
            // Load straight from the file: createSession(String) avoids copying the
            // whole model into a ByteArray on the heap first.
            ortSession = OrtSession.SessionOptions().use { options ->
                memorySavingOptions(options)
                env.createSession(modelFile.path, options)
            }
            val vocab = Wav2Vec2Decoder.fromVocabJson(vocabFile.readText())
            decoder = vocab
            EngineLog.d(TAG, "Model initialized. Vocab size: ${vocab.vocabSize}")
            _state = ModelState.READY
        } catch (e: OutOfMemoryError) {
            EngineLog.e(TAG, "Out of memory while loading phoneme model", e)
            error = "Not enough memory to load the pronunciation model."
            _state = ModelState.FAILED
        } catch (e: Exception) {
            EngineLog.e(TAG, "Failed to initialize recognizer", e)
            error = e.message ?: "Unknown error loading phoneme model."
            _state = ModelState.FAILED
        }
    }

    override fun recognize(base64Wav: String): String? = analyze(base64Wav)?.recognition?.ipa

    override fun recognizeDetailed(base64Wav: String): Recognition? = analyze(base64Wav)?.recognition

    override fun analyze(base64Wav: String, referenceIpa: String?): AudioAnalysis? {
        val wavBytes = try {
            Base64.getDecoder().decode(base64Wav)
        } catch (e: Exception) {
            EngineLog.e(TAG, "Invalid base64 audio", e)
            return null
        }
        val input = Wav2Vec2Audio.prepare(wavBytes) ?: return null
        val model = runModel(input.normalized) ?: return null
        return decoder?.analyze(model, input, referenceIpa)
    }

    /** The raw logits for a WAV, for the iOS parity harness. Null when the take is not evaluable. */
    internal fun logitsOf(wavBytes: ByteArray): Wav2Vec2Logits? {
        val input = Wav2Vec2Audio.prepare(wavBytes) ?: return null
        return runModel(input.normalized)
    }

    private fun runModel(normalized: FloatArray): Wav2Vec2Logits? {
        val session = ortSession ?: run {
            if (_state == ModelState.UNINITIALIZED) initialize()
            ortSession
        } ?: return null
        val env = ortEnv ?: return null

        return try {
            val inputTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(normalized),
                longArrayOf(1L, normalized.size.toLong()),
            )
            // Close the input tensor and the run result: the result keeps the
            // session alive and its output buffers mapped until it is closed, so
            // never closing it pins the model in memory for the process lifetime.
            // That matters when a second recognizer has to be loaded afterwards.
            inputTensor.use { input ->
                session.run(mapOf("input_values" to input)).use { output ->
                    val outTensor = output[0] as OnnxTensor
                    val shape = outTensor.info.shape
                    Wav2Vec2Logits(
                        logits = outTensor.floatBuffer.array(),
                        timeSteps = shape[1].toInt(),
                        vocabSize = shape[2].toInt(),
                    )
                }
            }
        } catch (e: Exception) {
            EngineLog.e(TAG, "Model run failed", e)
            null
        }
    }

    /**
     * Where tensors live, never what is computed, so scores are unchanged. Without
     * the arena, the activations of a take are freed when its run ends instead of
     * staying reserved at the size of the longest take for the life of the session;
     * and the weights are allocated once, outside the arena, at load.
     */
    private fun memorySavingOptions(options: OrtSession.SessionOptions) {
        options.setCPUArenaAllocator(false)
        options.addConfigEntry("session.use_device_allocator_for_initializers", "1")
    }

    fun close() {
        try {
            ortSession?.close()
            ortSession = null
            ortEnv = null
            decoder = null
            _state = ModelState.UNINITIALIZED
        } catch (e: Exception) {
            EngineLog.e(TAG, "Error closing recognizer", e)
        }
    }

    companion object {
        /** Sample rate the acoustic model expects and the audio is captured at. */
        const val SAMPLE_RATE = Wav2Vec2Audio.SAMPLE_RATE

        /** Vocabulary names a HuggingFace model pack may ship. */
        private val VOCAB_NAMES = listOf("vocab.json", "phoneme_vocab.json")

        /**
         * Builds a recognizer from a model pack directory: the ONNX file plus the
         * vocabulary that maps its output ids to phonemes. The vocabulary may sit
         * next to the model or inside the `processor/` directory the HF export
         * ships, so a second (fine-tuned) model can be loaded from its own pack
         * with the exact same code path as the default one. Returns null when the
         * pack has no model file or no vocabulary, so a caller can fall back
         * instead of loading a broken recognizer.
         */
        fun fromPack(
            packDir: File,
            modelFileName: String = PhonemeModelConfig.MODEL_FILE,
        ): Wav2Vec2PhonemeRecognizer? {
            val model = File(packDir, modelFileName)
            if (!model.isFile) return null
            val vocab = findVocab(packDir) ?: return null
            return Wav2Vec2PhonemeRecognizer(model, vocab)
        }

        private fun findVocab(packDir: File): File? {
            val processor = File(packDir, "processor")
            VOCAB_NAMES.forEach { name ->
                val candidate = File(processor, name)
                if (candidate.isFile) return candidate
            }
            VOCAB_NAMES.forEach { name ->
                val candidate = File(packDir, name)
                if (candidate.isFile) return candidate
            }
            return null
        }
    }
}
