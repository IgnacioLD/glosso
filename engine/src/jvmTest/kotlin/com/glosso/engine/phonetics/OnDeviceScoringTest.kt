package com.glosso.engine.phonetics

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check of the on-device scoring path: load the selective int8 model, recognize
 * a speech WAV, and produce a score with the same engine the app uses offline.
 *
 * The model (~600 MB) and the WAV are not committed, so this test only runs when
 * both are pointed at:
 *
 *   GLOSSO_MODEL=.../wav2vec2_phoneme_int8_dyn_pertensor.onnx \
 *   GLOSSO_VOCAB=.../phoneme_vocab.json \
 *   GLOSSO_WAV=.../speech.wav \
 *   ./gradlew :engine:jvmTest --tests '*OnDeviceScoringTest*'
 */
class OnDeviceScoringTest {

    @Test
    fun `scores speech with the on-device model`() {
        val model = File(System.getenv("GLOSSO_MODEL") ?: "")
        val vocab = File(System.getenv("GLOSSO_VOCAB") ?: "")
        val wav = File(System.getenv("GLOSSO_WAV") ?: "")
        if (!model.isFile || !vocab.isFile || !wav.isFile) {
            println("On-device scoring test skipped: set GLOSSO_MODEL, GLOSSO_VOCAB and GLOSSO_WAV")
            return
        }

        val recognizer = Wav2Vec2PhonemeRecognizer(model, vocab)
        recognizer.initialize()
        assertEquals(ModelState.READY, recognizer.state)

        val text = "I think my brother is thirsty."
        val expected = "ˈaɪ θˈɪŋk mˈaɪ bɹˈʌðɚ ˈɪz θˈɝsti"
        val audio = Base64.getEncoder().encodeToString(wav.readBytes())

        val analysis = recognizer.analyze(audio, expected)
        assertNotNull(analysis, "the model should hear something in speech audio")
        println("transcription=${analysis.recognition.ipa}")

        val confidences = PhoneticComparator.phoneConfidencesOf(analysis.recognition.phones)
        val result = PhoneticComparator.calculateScoringResult(
            text = text,
            expected = expected,
            actual = analysis.recognition.ipa,
            language = "en_GB",
            actualConfidence = confidences,
        )
        println("score=${result.score} accuracy=${result.accuracy} completeness=${result.completeness}")

        assertTrue(analysis.recognition.ipa.isNotBlank())
        assertTrue(result.score in 0..100)
        recognizer.close()
    }
}
