package com.glosso.engine.phonetics

import com.glosso.engine.log.EngineLog
import com.glosso.engine.model.PhonemeModelConfig
import com.glosso.engine.ort.GlossoOrtSession
import cnames.structs.OrtValue
import com.glosso.engine.ort.glosso_ort_close
import com.glosso.engine.ort.glosso_ort_free_error
import com.glosso.engine.ort.glosso_ort_open
import com.glosso.engine.ort.glosso_ort_output_data
import com.glosso.engine.ort.glosso_ort_release_value
import com.glosso.engine.ort.glosso_ort_run
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSFileManager
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.memcpy

/**
 * The iOS twin of the JVM/Android recognizer in onnxMain: the same selective int8
 * wav2vec2 model, run by the same ONNX Runtime CPU kernels through its C API
 * (the official iOS build, linked by cinterop; see docs/IOS-ENGINE.md).
 *
 * Only loading and running the model is platform code. WAV decoding,
 * normalisation, CTC decoding, confidences and forced alignment are the shared
 * [Wav2Vec2Audio] and [Wav2Vec2Decoder], so for the same logits the result is the
 * one the server computes.
 *
 * The model is read from a file path (the app keeps it under Application
 * Support). [initialize] is heavy (~350 MB) and must run off the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
class Wav2Vec2PhonemeRecognizer(
    private val modelPath: String,
    private val vocabPath: String,
) : PhonemeRecognizer {

    private val lock = NSRecursiveLock()

    private var session: CPointer<GlossoOrtSession>? = null
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
    override fun initialize() {
        lock.lock()
        try {
            initializeLocked()
        } finally {
            lock.unlock()
        }
    }

    private fun initializeLocked() {
        if (session != null) {
            _state = ModelState.READY
            return
        }
        _state = ModelState.LOADING
        try {
            val files = NSFileManager.defaultManager
            val hasModel = files.fileExistsAtPath(modelPath)
            val hasVocab = files.fileExistsAtPath(vocabPath)
            if (!hasModel || !hasVocab) {
                EngineLog.d(TAG, "Model assets missing (model=$hasModel, vocab=$hasVocab)")
                _state = ModelState.UNINITIALIZED
                return
            }

            session = memScoped {
                val err = allocPointerTo<ByteVar>()
                val opened = glosso_ort_open(modelPath, err.ptr)
                err.value?.let { message ->
                    val text = message.toKString()
                    glosso_ort_free_error(message)
                    throw IllegalStateException(text)
                }
                opened
            }
            val vocabText = NSString.stringWithContentsOfFile(vocabPath, NSUTF8StringEncoding, null)
                ?: throw IllegalStateException("Cannot read vocabulary at $vocabPath")
            val vocab = Wav2Vec2Decoder.fromVocabJson(vocabText)
            decoder = vocab
            EngineLog.d(TAG, "Model initialized. Vocab size: ${vocab.vocabSize}")
            _state = ModelState.READY
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
            BASE64.decode(base64Wav)
        } catch (e: Exception) {
            EngineLog.e(TAG, "Invalid base64 audio", e)
            return null
        }
        return analyzeWav(wavBytes, referenceIpa)
    }

    /** [analyze] for WAV bytes already in memory, skipping the base64 round trip. */
    fun analyzeWav(wavBytes: ByteArray, referenceIpa: String? = null): AudioAnalysis? {
        val input = Wav2Vec2Audio.prepare(wavBytes) ?: return null
        val model = runModel(input.normalized) ?: return null
        return decoder?.analyze(model, input, referenceIpa)
    }

    /** The raw logits for a WAV, for the parity harness. Null when the take is not evaluable. */
    internal fun logitsOf(wavBytes: ByteArray): Wav2Vec2Logits? {
        val input = Wav2Vec2Audio.prepare(wavBytes) ?: return null
        return runModel(input.normalized)
    }

    private fun runModel(normalized: FloatArray): Wav2Vec2Logits? {
        val current = session ?: run {
            if (_state == ModelState.UNINITIALIZED) initialize()
            session
        } ?: return null

        return try {
            memScoped {
                val output = allocPointerTo<OrtValue>()
                val timeSteps = alloc<LongVar>()
                val vocabSize = alloc<LongVar>()
                val err = normalized.usePinned { pinned ->
                    glosso_ort_run(
                        current,
                        pinned.addressOf(0),
                        normalized.size.toLong(),
                        output.ptr,
                        timeSteps.ptr,
                        vocabSize.ptr,
                    )
                }
                if (err != null) {
                    val text = err.toKString()
                    glosso_ort_free_error(err)
                    throw IllegalStateException(text)
                }
                val value = output.value ?: throw IllegalStateException("ONNX Runtime returned no output")
                try {
                    val t = timeSteps.value.toInt()
                    val v = vocabSize.value.toInt()
                    val data = glosso_ort_output_data(current, value)
                        ?: throw IllegalStateException("ONNX Runtime output has no data")
                    val logits = FloatArray(t * v)
                    if (logits.isNotEmpty()) {
                        logits.usePinned { memcpy(it.addressOf(0), data, (logits.size * Float.SIZE_BYTES).toULong()) }
                    }
                    Wav2Vec2Logits(logits, t, v)
                } finally {
                    glosso_ort_release_value(current, value)
                }
            }
        } catch (e: Exception) {
            EngineLog.e(TAG, "Model run failed", e)
            null
        }
    }

    fun close() {
        lock.lock()
        try {
            glosso_ort_close(session)
            session = null
            decoder = null
            _state = ModelState.UNINITIALIZED
        } catch (e: Exception) {
            EngineLog.e(TAG, "Error closing recognizer", e)
        } finally {
            lock.unlock()
        }
    }

    companion object {
        private const val TAG = "Wav2Vec2Recognizer"

        /** Sample rate the acoustic model expects and the audio is captured at. */
        const val SAMPLE_RATE = Wav2Vec2Audio.SAMPLE_RATE

        /** java.util.Base64.getDecoder(): standard alphabet, padding optional. */
        private val BASE64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

        /** Vocabulary names a HuggingFace model pack may ship. */
        private val VOCAB_NAMES = listOf("vocab.json", "phoneme_vocab.json")

        /**
         * Builds a recognizer from a model pack directory, looking for the
         * vocabulary in `processor/` and then next to the model, like the
         * JVM/Android `fromPack`. Returns null when the model or vocabulary is
         * missing.
         */
        fun fromPack(
            packDir: String,
            modelFileName: String = PhonemeModelConfig.MODEL_FILE,
        ): Wav2Vec2PhonemeRecognizer? {
            val files = NSFileManager.defaultManager
            val dir = packDir.trimEnd('/')
            val model = "$dir/$modelFileName"
            if (!files.fileExistsAtPath(model)) return null
            val vocab = listOf("$dir/processor", dir)
                .flatMap { base -> VOCAB_NAMES.map { "$base/$it" } }
                .firstOrNull { files.fileExistsAtPath(it) }
                ?: return null
            return Wav2Vec2PhonemeRecognizer(model, vocab)
        }
    }
}
