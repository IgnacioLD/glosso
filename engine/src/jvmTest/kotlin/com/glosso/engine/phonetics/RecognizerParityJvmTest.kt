package com.glosso.engine.phonetics

import java.io.File
import kotlin.test.Test

/**
 * The JVM (server) side of the iOS recognizer parity. Skips unless staged by
 * `tools/score-parity/run-ios-parity.sh`; see [RecognizerParityHarness].
 */
class RecognizerParityJvmTest {
    @Test
    fun `writes the JVM recognizer parity records`() {
        RecognizerParityHarness.runIfConfigured { model, vocab ->
            val recognizer = Wav2Vec2PhonemeRecognizer(File(model), File(vocab))
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
