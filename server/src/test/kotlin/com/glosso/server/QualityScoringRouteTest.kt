package com.glosso.server

import com.glosso.engine.model.PhoneQualityModel
import com.glosso.engine.model.PhoneTimingModel
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.engine.model.PronunciationQualityModel
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The quality decision layer on the audio scoring path: attached additively,
 * never touching the phoneme score, and absent when there was no alignment. The
 * recognizer is faked so the test runs without the model; the response assembly
 * and JSON contract are the production ones.
 */
class QualityScoringRouteTest {

    private fun emptyAssets(): File = Files.createTempDirectory("glosso-quality-assets").toFile()

    private fun quality(): PronunciationQualityModel {
        val worst = listOf(
            PhoneQualityModel("ɹ", "l", "approximant", gop = -2.1f, gopRank = 0f, margin = -0.3f, entropy = 5.1f, frames = 4, quality = 0.21f),
            PhoneQualityModel("ɪ", "i", "vowel", gop = -1.4f, gopRank = 0.3f, margin = -0.2f, entropy = 4.9f, frames = 5, quality = 0.48f),
            PhoneQualityModel("ʃ", "ʃ", "fricative", gop = -0.5f, gopRank = 0.6f, margin = -0.1f, entropy = 4.4f, frames = 3, quality = 0.66f),
        )
        return PronunciationQualityModel(
            score = worst.map { it.quality.toDouble() }.average().toFloat(),
            worst = worst,
            phones = worst,
            head = "shared-inventory-logreg-worst3-v1",
        )
    }

    private class FakeRecognizer(
        private val analysis: AudioAnalysis?,
    ) : PhonemeRecognizer {
        private var _state: ModelState = ModelState.UNINITIALIZED
        override var error: String? = null
        override val state: ModelState get() = _state
        override fun initialize() { _state = ModelState.READY }
        override fun recognize(base64Wav: String): String = analysis?.recognition?.ipa.orEmpty()
        override fun analyze(base64Wav: String, referenceIpa: String?): AudioAnalysis? = analysis
    }

    @Test
    fun `audio scoring carries the quality signal and the worst-3, without changing the score`() = testApplication {
        val recognizer = FakeRecognizer(
            AudioAnalysis(
                recognition = Recognition("ʃɪp"),
                timings = listOf(PhoneTimingModel(phone = "ɪ", startMs = 0, endMs = 400, confidence = 1f)),
                pcm = FloatArray(1600) { 0.1f },
                sampleRate = 16_000,
                quality = quality(),
            ),
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
        assertEquals(100, feedback.score, "the decision layer must not touch the phoneme score")
        val quality = feedback.quality
        assertTrue(quality != null, "the quality signal must be exposed")
        assertEquals("shared-inventory-logreg-worst3-v1", quality.head)
        assertEquals(3, quality.worst.size)
        assertEquals("ɹ", quality.worst[0].phone, "worst phone first")
        assertTrue(quality.score in 0f..1f)
    }

    @Test
    fun `no alignment means no quality signal`() = testApplication {
        val recognizer = FakeRecognizer(AudioAnalysis(Recognition("ʃɪp")))
        recognizer.initialize()
        application { module(emptyAssets(), null, null, recognizer = recognizer) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "ship", expectedIpa = "ʃɪp", audioBase64 = "AAAA"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.body<PronunciationFeedback>().quality)
    }

    @Test
    fun `scoring by ipa keeps the quality signal empty`() = testApplication {
        application { module(emptyAssets()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "cat", expectedIpa = "kæt", actualIpa = "kæt"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(100, response.body<PronunciationFeedback>().score)
        assertNull(response.body<PronunciationFeedback>().quality)
    }
}
