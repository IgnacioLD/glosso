package com.glosso.engine.phonetics

import kotlin.test.Test
import kotlin.time.TimeSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS recognizer on the simulator. The model tests are asset-gated like the
 * JVM ones: they read GLOSSO_MODEL, GLOSSO_VOCAB and GLOSSO_WAV (forward them to
 * the simulator as SIMCTL_CHILD_GLOSSO_*) and skip when unset.
 */
class IosRecognizerTest {

    @Test
    fun `a missing model leaves the recognizer uninitialized`() {
        val recognizer = Wav2Vec2PhonemeRecognizer("/nonexistent/model.onnx", "/nonexistent/vocab.json")
        recognizer.initialize()
        assertEquals(ModelState.UNINITIALIZED, recognizer.state)
        assertNull(recognizer.analyze("UklGRg==", null))
        assertNull(Wav2Vec2PhonemeRecognizer.fromPack("/nonexistent"))
    }

    @Test
    fun `scores speech with the iOS recognizer`() {
        val model = ParityPlatform.env("GLOSSO_MODEL")
        val vocab = ParityPlatform.env("GLOSSO_VOCAB")
        val wav = ParityPlatform.env("GLOSSO_WAV")
        if (model == null || vocab == null || wav == null) {
            println("iOS recognizer test skipped: set GLOSSO_MODEL, GLOSSO_VOCAB and GLOSSO_WAV")
            return
        }
        val before = ParityPlatform.footprintBytes()
        val loadStart = TimeSource.Monotonic.markNow()
        val recognizer = Wav2Vec2PhonemeRecognizer(model, vocab)
        recognizer.initialize()
        val loadMs = loadStart.elapsedNow().inWholeMilliseconds
        val loaded = ParityPlatform.footprintBytes()
        try {
            assertEquals(ModelState.READY, recognizer.state, recognizer.error)
            val bytes = ParityPlatform.readBytes(wav)
            val runStart = TimeSource.Monotonic.markNow()
            val analysis = assertNotNull(recognizer.analyzeWav(bytes, null))
            val runMs = runStart.elapsedNow().inWholeMilliseconds
            // One take in a fresh process: the load and memory numbers an app sees.
            println(
                "IOS_RECOGNIZER|ipa=${analysis.recognition.ipa}|phones=${analysis.recognition.phones.size}" +
                    "|load_ms=$loadMs|analyze_ms=$runMs|footprint_before=$before|footprint_loaded=$loaded" +
                    "|footprint_scored=${ParityPlatform.footprintBytes()}|footprint_peak=${ParityPlatform.peakMemoryBytes()}",
            )
            assertTrue(analysis.recognition.ipa.isNotBlank())
            assertTrue(analysis.recognition.phones.all { it.confidence in 0f..1f })
        } finally {
            recognizer.close()
        }
    }

    @Test
    fun `writes the iOS recognizer parity records`() {
        RecognizerParityHarness.runIfConfigured { model, vocab ->
            val recognizer = Wav2Vec2PhonemeRecognizer(model, vocab)
            object : RecognizerParityHarness.Subject {
                override fun initialize(): Boolean {
                    recognizer.initialize()
                    return recognizer.state == ModelState.READY
                }

                override fun analyze(base64Wav: String, referenceIpa: String) =
                    recognizer.analyze(base64Wav, referenceIpa)

                override fun logits(wavBytes: ByteArray) = recognizer.logitsOf(wavBytes)

                override fun close() = recognizer.close()
            }
        }
    }
}
