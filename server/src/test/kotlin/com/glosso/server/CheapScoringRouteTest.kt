package com.glosso.server

import com.glosso.engine.model.PhoneTimingModel
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.engine.phonetics.AudioAnalysis
import com.glosso.engine.phonetics.ModelState
import com.glosso.engine.phonetics.PhonemeRecognizer
import com.glosso.engine.phonetics.Recognition
import com.glosso.server.api.ScoreRequest
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check of the cheap signal on the audio scoring path: the model is
 * faked so the test runs without the ~600 MB asset, but the cheap scorer, the
 * response assembly and the JSON contract are the production ones.
 */
class CheapScoringRouteTest {

    private fun emptyAssets(): File = Files.createTempDirectory("glosso-cheap-assets").toFile()

    /** Voiced harmonic tone, F0 200 Hz, 0.4 s at 16 kHz. */
    private fun voicedPcm(): FloatArray {
        val sr = 16_000
        val n = (0.4 * sr).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            var v = 0.0
            for (k in 1..20) v += sin(2 * PI * 200 * k * t) / k
            (v / 4.0).toFloat()
        }
    }

    private class FakeRecognizer(
        private val pcm: FloatArray,
        private val ipa: String,
        private val timings: List<PhoneTimingModel>,
    ) : PhonemeRecognizer {
        private var _state: ModelState = ModelState.UNINITIALIZED
        override var error: String? = null

        override val state: ModelState get() = _state
        override fun initialize() { _state = ModelState.READY }
        override fun recognize(base64Wav: String): String = ipa
        override fun analyze(base64Wav: String, referenceIpa: String?): AudioAnalysis =
            AudioAnalysis(Recognition(ipa), timings, pcm, 16_000)
    }

    @Test
    fun `audio scoring attaches fluency and per-phone acoustics without changing the score`() = testApplication {
        val recognizer = FakeRecognizer(
            pcm = voicedPcm(),
            ipa = "ʃɪp",
            timings = listOf(PhoneTimingModel(phone = "ɪ", startMs = 0, endMs = 400, confidence = 1f)),
        )
        recognizer.initialize()
        application { module(emptyAssets(), null, null, recognizer = recognizer) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "ship", expectedIpa = "ʃɪp", audioBase64 = "AAAA"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val feedback = response.body<PronunciationFeedback>()
        assertEquals(100, feedback.score, "the cheap signal must not touch the phoneme score")
        assertEquals(true, feedback.isMastery)
        assertNotNull(feedback.fluency, "the accent/fluency signal must be exposed")
        assertTrue(
            feedback.acoustics.any { it.phoneme == "ɪ" },
            "the per-phone acoustics must be attached, was ${feedback.acoustics}",
        )
    }

    @Test
    fun `scoring by ipa keeps the new fields empty`() = testApplication {
        application { module(emptyAssets()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "cat", expectedIpa = "kæt", actualIpa = "kæt"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val feedback = response.body<PronunciationFeedback>()
        assertEquals(100, feedback.score)
        assertEquals(null, feedback.fluency, "no audio means no cheap signal")
        assertTrue(feedback.acoustics.isEmpty())
    }
}
