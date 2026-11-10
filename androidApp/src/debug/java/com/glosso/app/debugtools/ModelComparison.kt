package com.glosso.app.debugtools

import android.content.Context
import com.glosso.app.OnDeviceScorer
import com.glosso.app.api.GlossoApi
import com.glosso.app.api.downloadTo
import com.glosso.engine.phonetics.PhoneticComparator
import com.glosso.engine.phonetics.Wav2Vec2PhonemeRecognizer
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One model's verdict on one recording. */
internal data class ModelRun(
    val label: String,
    val transcription: String,
    val per: Double?,
    val score: Int?,
    val accuracy: Int?,
    val completeness: Int?,
    val level: String?,
    /** True when the model was silent, failed to load, or ran out of memory. */
    val failed: Boolean = false,
)

/** Both models' verdicts on the same recording against the same reference. */
internal data class CompareOutcome(
    val referenceText: String,
    val referenceIpa: String,
    val base: ModelRun,
    val ft: ModelRun,
)

/**
 * Debug-only: runs the shipped recognizer and the fine-tuned one over the same
 * audio and scores both against the real reference, so the difference is a
 * number, not an impression.
 *
 * The two models are loaded one at a time and closed before the next starts: each
 * fp16 model is hundreds of MB in memory and an emulator cannot hold both at
 * once. Heavy work never runs on the main thread.
 */
internal object ModelComparison {

    const val FT_MODEL_FILE = "wav2vec2_phoneme_fp16_ft.onnx"
    private const val FT_VOCAB_FILE = "phoneme_vocab.json"
    private const val FT_ASSET_DIR = "content/en/models/ft_v1"
    private const val FT_DIR = "scoring/ft_v1"

    private fun ftDir(context: Context) = File(context.filesDir, FT_DIR)

    fun ftPresent(context: Context): Boolean =
        File(ftDir(context), FT_MODEL_FILE).isFile && File(ftDir(context), FT_VOCAB_FILE).isFile

    fun ftBytes(context: Context): Long =
        listOf(File(ftDir(context), FT_MODEL_FILE), File(ftDir(context), FT_VOCAB_FILE))
            .filter { it.isFile }
            .sumOf { it.length() }

    /** Downloads the fine-tuned model pack on demand; it is never bundled in the APK. */
    suspend fun downloadFt(
        context: Context,
        api: GlossoApi,
        onProgress: (Float) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = ftDir(context)
            dir.mkdirs()
            val model = File(dir, FT_MODEL_FILE)
            val vocab = File(dir, FT_VOCAB_FILE)
            if (!model.isFile) {
                api.downloadTo(api.sharedAssetUrl("$FT_ASSET_DIR/$FT_MODEL_FILE"), model) { done, total ->
                    if (total != null && total > 0) onProgress((done.toFloat() / total) * 0.98f)
                }
            }
            if (!vocab.isFile) {
                api.downloadTo(api.sharedAssetUrl("$FT_ASSET_DIR/$FT_VOCAB_FILE"), vocab) { done, total ->
                    val fraction = if (total != null && total > 0) done.toFloat() / total else 1f
                    onProgress(0.98f + fraction * 0.02f)
                }
            }
            onProgress(1f)
            ftPresent(context)
        } catch (e: Exception) {
            android.util.Log.w("GlossoModelCompare", "fine-tuned pack download failed", e)
            false
        }
    }

    suspend fun compare(
        context: Context,
        audioWav: ByteArray,
        text: String,
        referenceIpa: String,
        language: String,
        onStatus: (String) -> Unit,
    ): CompareOutcome = withContext(Dispatchers.Default) {
        val audioBase64 = Base64.getEncoder().encodeToString(audioWav)
        // Any model the app already holds must be released: there is only room for
        // one at a time on a phone, and the ft model is loaded next.
        OnDeviceScorer.unload()

        onStatus("Cargando modelo actual")
        val baseRecognizer = Wav2Vec2PhonemeRecognizer(
            OnDeviceScorer.modelPath(),
            OnDeviceScorer.vocabPath(),
        )
        val base = run("Actual", baseRecognizer, audioBase64, text, referenceIpa, language)
        releaseNativeMemory()

        onStatus("Cargando modelo fine-tuned")
        val ftRecognizer = Wav2Vec2PhonemeRecognizer.fromPack(ftDir(context), FT_MODEL_FILE)
        val ft = if (ftRecognizer == null) {
            ModelRun("Fine-tuned", "", null, null, null, null, null, failed = true)
        } else {
            run("Fine-tuned", ftRecognizer, audioBase64, text, referenceIpa, language)
        }
        releaseNativeMemory()

        CompareOutcome(text, referenceIpa, base, ft)
    }

    /**
     * Closes the first model and nudges the runtime to hand its native memory
     * back before the second model is loaded. Two fp16 wav2vec2 models exceed the
     * memory budget of a small device, and the low-memory killer takes the whole
     * process; a short pause after the session closes lets the allocator return
     * the pages first.
     */
    private fun releaseNativeMemory() {
        System.gc()
        System.runFinalization()
        Thread.sleep(1500)
    }

    private fun run(
        label: String,
        recognizer: Wav2Vec2PhonemeRecognizer,
        audioBase64: String,
        text: String,
        referenceIpa: String,
        language: String,
    ): ModelRun {
        return try {
            recognizer.initialize()
            val recognition = recognizer.recognizeDetailed(audioBase64)
                ?: return ModelRun(label, "", null, null, null, null, null, failed = true)
            val per = PhoneticComparator.phonemeErrorRate(referenceIpa, recognition.ipa)
            val confidences = PhoneticComparator.phoneConfidencesOf(recognition.phones)
            val result = PhoneticComparator.calculateScoringResult(
                text = text,
                expected = referenceIpa,
                actual = recognition.ipa,
                language = language,
                actualConfidence = confidences,
            )
            ModelRun(
                label = label,
                transcription = recognition.ipa,
                per = per,
                score = result.score,
                accuracy = result.accuracy,
                completeness = result.completeness,
                level = result.level.name,
            )
        } catch (e: OutOfMemoryError) {
            android.util.Log.w("GlossoModelCompare", "$label ran out of memory", e)
            ModelRun(label, "", null, null, null, null, null, failed = true)
        } catch (e: Exception) {
            android.util.Log.w("GlossoModelCompare", "$label failed", e)
            ModelRun(label, "", null, null, null, null, null, failed = true)
        } finally {
            recognizer.close()
        }
    }
}
