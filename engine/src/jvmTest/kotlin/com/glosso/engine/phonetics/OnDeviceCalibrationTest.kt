package com.glosso.engine.phonetics

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end calibration with the real acoustic model and real speech.
 *
 * The motivating report was a learner speaking English with a strong Spanish
 * accent who scored 98/100 and was told MASTERED. This test uses two real takes
 * of the same sentence - a native English one that must score high, and a
 * Spanish-accented one that must not be told MASTERED - runs both through the
 * real wav2vec2 model, and checks the scorer separates them.
 *
 * Assets and audio are not committed, so it skips unless pointed at them:
 *
 *   GLOSSO_MODEL=.../wav2vec2_phoneme_int8_dyn_pertensor.onnx \
 *   GLOSSO_VOCAB=.../phoneme_vocab.json \
 *   GLOSSO_WAV_NATIVE=.../native.wav \
 *   GLOSSO_WAV_ACCENT=.../spanish-accent.wav \
 *   ./gradlew :engine:jvmTest --tests '*OnDeviceCalibrationTest*'
 *
 * The two clips are rendered with the project's Kokoro TTS:
 *
 *   tools/content/.venv/bin/python tools/content/calibration_tts.py /tmp/glosso-calibration
 *
 * (a native American voice and a Spanish voice reading English, i.e. exactly the
 * accent in the report). Set GLOSSO_KOKORO_PYTHON to the venv interpreter and
 * GLOSSO_KOKORO_SCRIPT to calibration_tts.py to let the test render them itself.
 */
class OnDeviceCalibrationTest {

    private val text = "I think my brother is thirsty."
    private val expected = "ˈaɪ θˈɪŋk mˈaɪ bɹˈʌðɚ ˈɪz θˈɝsti"

    private data class Take(
        val name: String,
        val ipa: String,
        val score: Int,
        val level: String,
        val mastery: Boolean,
        val accuracy: Int,
        val completeness: Int,
    )

    private fun scoreTake(recognizer: Wav2Vec2PhonemeRecognizer, name: String, wav: File): Take {
        val audio = Base64.getEncoder().encodeToString(wav.readBytes())
        val analysis = recognizer.analyze(audio, expected)
        assertNotNull(analysis, "$name: the model should hear speech")
        val confidences = PhoneticComparator.phoneConfidencesOf(analysis.recognition.phones)
        val result = PhoneticComparator.calculateScoringResult(
            text = text,
            expected = expected,
            actual = analysis.recognition.ipa,
            language = "en_GB",
            actualConfidence = confidences,
        )
        return Take(
            name = name,
            ipa = analysis.recognition.ipa,
            score = result.score,
            level = result.level.name,
            mastery = result.isMastery,
            accuracy = result.accuracy,
            completeness = result.completeness,
        )
    }

    /** Renders the two takes with the project's Kokoro script when configured. */
    private fun kokoroTakes(dir: File): List<File>? {
        val python = System.getenv("GLOSSO_KOKORO_PYTHON") ?: return null
        val script = System.getenv("GLOSSO_KOKORO_SCRIPT") ?: return null
        if (!File(python).isFile || !File(script).isFile) return null
        val ok = ProcessBuilder(python, script, dir.absolutePath)
            .redirectErrorStream(true).start().waitFor() == 0
        if (!ok) return null
        val native = File(dir, "native.wav")
        val accent = File(dir, "spanish-accent.wav")
        return if (native.isFile && accent.isFile) listOf(native, accent) else null
    }

    @Test
    fun `native speech outranks spanish-accented speech`() {
        val model = File(System.getenv("GLOSSO_MODEL") ?: "")
        val vocab = File(System.getenv("GLOSSO_VOCAB") ?: "")
        if (!model.isFile || !vocab.isFile) {
            println("On-device calibration skipped: set GLOSSO_MODEL and GLOSSO_VOCAB")
            return
        }

        val nativeEnv = File(System.getenv("GLOSSO_WAV_NATIVE") ?: "")
        val accentEnv = File(System.getenv("GLOSSO_WAV_ACCENT") ?: "")
        val provided = if (nativeEnv.isFile && accentEnv.isFile) listOf(nativeEnv, accentEnv) else null
        val rendered = provided ?: kokoroTakes(
            File(System.getProperty("java.io.tmpdir"), "glosso-calibration"),
        )
        if (rendered == null) {
            println(
                "On-device calibration skipped: set GLOSSO_WAV_NATIVE/GLOSSO_WAV_ACCENT " +
                    "or GLOSSO_KOKORO_PYTHON/GLOSSO_KOKORO_SCRIPT",
            )
            return
        }

        val recognizer = Wav2Vec2PhonemeRecognizer(model, vocab)
        recognizer.initialize()
        assertEquals(ModelState.READY, recognizer.state)

        val takes = listOf(
            scoreTake(recognizer, "native", rendered[0]),
            scoreTake(recognizer, "spanish-accent", rendered[1]),
        )
        for (take in takes) {
            println(
                "CALIBRATION|${take.name}|score=${take.score}|level=${take.level}|" +
                    "mastery=${take.mastery}|accuracy=${take.accuracy}|completeness=${take.completeness}",
            )
            println("CALIBRATION|${take.name}|ipa=${take.ipa}")
        }
        recognizer.close()

        val nativeTake = takes[0]
        val accentTake = takes[1]
        assertTrue(
            nativeTake.score > accentTake.score,
            "native speech must outrank the accented take: ${nativeTake.score} vs ${accentTake.score}",
        )
        // The stricter scorer must not over-correct: real native speech with only
        // allophones (flap, unreleased stop, an extra linking segment) still
        // masters, or the calibration has drifted into false negatives.
        assertTrue(
            nativeTake.mastery,
            "native speech must still be MASTERED, got ${nativeTake.level} (${nativeTake.score})",
        )
        assertFalse(
            accentTake.mastery,
            "a strong Spanish accent with real substitutions must not be MASTERED",
        )
    }
}
