package com.glosso.engine.phonetics

import com.glosso.engine.g2p.CmuDictG2P
import com.glosso.engine.g2p.widenReferenceVariants
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check of the cloud/offline parity fix with the real on-device model:
 * recognize a spoken British "three thirty", then score it the way the cloud does
 * (reference widened with CMUdict) and the way the device will (widened set baked
 * into the content), and require the same verdict and no false /d/.
 *
 * The model (~600 MB) and the WAV are not committed, so this only runs when they
 * are pointed at:
 *
 *   GLOSSO_MODEL=.../wav2vec2_phoneme_int8_dyn_pertensor.onnx \
 *   GLOSSO_VOCAB=.../phoneme_vocab.json \
 *   GLOSSO_CMUDICT=.../cmudict.dict \
 *   GLOSSO_WAV=.../thirty.wav \
 *   ./gradlew :engine:jvmTest --tests '*OnDeviceReferenceParityTest*'
 *
 * It skips quietly when any of them is missing, so a plain `jvmTest` still works.
 */
class OnDeviceReferenceParityTest {

    private fun asset(env: String, default: String): File =
        File(System.getenv(env) ?: default)

    @Test
    fun `a british three thirty scores the same on the cloud and on the device, with no false flap`() {
        val model = asset("GLOSSO_MODEL", "../data/assets/wav2vec2_phoneme_int8_dyn_pertensor.onnx")
        val vocab = asset("GLOSSO_VOCAB", "../data/assets/phoneme_vocab.json")
        val cmudict = asset("GLOSSO_CMUDICT", "../data/assets/cmudict.dict")
        val wav = asset("GLOSSO_WAV", "/tmp/glosso-assets/thirty.wav")
        if (!model.isFile || !vocab.isFile || !cmudict.isFile || !wav.isFile) {
            // Gated like OnDeviceScoringTest: skipped by default so a plain jvmTest
            // works, but a CI job that stages the assets can force the gate.
            if (System.getenv("GLOSSO_REQUIRE_MODEL") != null) {
                error("GLOSSO_REQUIRE_MODEL is set but an asset is missing: model=$model vocab=$vocab cmudict=$cmudict wav=$wav")
            }
            println(
                "On-device reference parity test skipped: set GLOSSO_MODEL, GLOSSO_VOCAB, " +
                    "GLOSSO_CMUDICT and GLOSSO_WAV (or place them in data/assets + /tmp/glosso-assets)",
            )
            return
        }

        val recognizer = Wav2Vec2PhonemeRecognizer(model, vocab)
        recognizer.initialize()
        assertEquals(ModelState.READY, recognizer.state)

        val text = "The flight leaves at three thirty."
        // The catalogue's reference: the primary CMUdict reading (American flap).
        val expected = "ðə flˈaɪt lˈivz ˈæt θɹˈi θˈɝdˌi"
        val audio = Base64.getEncoder().encodeToString(wav.readBytes())

        val analysis = recognizer.analyze(audio, expected)
        assertNotNull(analysis, "the model should hear something in the speech audio")
        val actual = analysis.recognition.ipa
        val confidences = PhoneticComparator.phoneConfidencesOf(analysis.recognition.phones)
        println("PARITY_E2E|recognized=$actual")

        val g2p = CmuDictG2P.fromLines(cmudict.readLines())
        // The single source both paths use: the server scores with it, the
        // publisher bakes it into the drill, the device consumes that. The score
        // equality cloud vs offline is pinned by ScoreRouteTest (same function);
        // here we run it with the real model and check the offline verdict is clean.
        val published = widenReferenceVariants(expected, g2p.pronunciations(text).wordVariants)
        val offline = PhoneticComparator.calculateScoringResult(
            text = text,
            expected = expected,
            actual = actual,
            language = "en_GB",
            actualConfidence = confidences,
            expectedWordVariants = published,
        )
        val beforeFix = PhoneticComparator.calculateScoringResult(
            text = text,
            expected = expected,
            actual = actual,
            language = "en_GB",
            actualConfidence = confidences,
        )

        println(
            "PARITY_E2E|offline=${offline.score}|beforeFix=${beforeFix.score}" +
                "|offlineMissed=${offline.alignment.filter { it.status == MatchStatus.MISSED }}" +
                "|beforeFixMissed=${beforeFix.alignment.filter { it.status == MatchStatus.MISSED }}",
        )

        assertTrue(
            offline.alignment.none { it.expected == "d" && it.actual == "t" },
            "the british /t/ must not be charged as a missing american /d/ offline: ${offline.alignment}",
        )
        assertTrue(
            offline.score >= beforeFix.score,
            "published variants must not lower the offline score: ${beforeFix.score} -> ${offline.score}",
        )
        recognizer.close()
    }
}
