package com.glosso.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlin.test.assertTrue
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.engine.g2p.CmuDictG2P
import com.glosso.engine.g2p.widenReferenceVariants
import com.glosso.engine.phonetics.PhoneticComparator
import com.glosso.server.api.ScoreRequest

class ScoreRouteTest {

    /** An empty assets directory: no model, no CMUdict. */
    private fun emptyAssets(): File =
        Files.createTempDirectory("glosso-empty-assets").toFile()

    /** An assets directory containing only a minimal CMUdict. */
    private fun assetsWithCmuDict(): File {
        val dir = Files.createTempDirectory("glosso-cmudict-assets").toFile()
        File(dir, "cmudict.dict").writeText(
            """
            ;;; minimal test dictionary
            CAT  K AE1 T
            DOG  D AO1 G
            """.trimIndent(),
        )
        return dir
    }

    /**
     * A CMUdict with the real "thirty" pair: the primary reading is the American
     * flap (`TH ER1 D IY2`) and the alternate is the British /t/
     * (`THIRTY(2) TH ER1 T IY2`). Mirrors the shipped dictionary.
     */
    private fun assetsWithThirtyVariants(): File {
        val dir = Files.createTempDirectory("glosso-thirty-assets").toFile()
        File(dir, "cmudict.dict").writeText(
            """
            ;;; minimal test dictionary
            THE  DH AH0
            FLIGHT  F L AY1 T
            LEAVES  L IY1 V Z
            AT  AE1 T
            THREE  TH R IY1
            THIRTY  TH ER1 D IY2
            THIRTY(2)  TH ER1 T IY2
            THIRTY'S  TH ER1 D IY2 Z
            THIRTY'S(2)  TH ER1 T IY2 Z
            """.trimIndent(),
        )
        return dir
    }

    /**
     * A CMUdict with the reported sentence's words. "near" is `N IH1 R`, the
     * American rhotic reading the shipped content.db carries; the British audio
     * does not say that /ɹ/.
     */
    private fun assetsWithNearVariants(): File {
        val dir = Files.createTempDirectory("glosso-near-assets").toFile()
        File(dir, "cmudict.dict").writeText(
            """
            ;;; minimal test dictionary
            THE  DH AH0
            BIN  B IH1 N
            IS  IH1 Z
            NEAR  N IH1 R
            GATE  G EY1 T
            """.trimIndent(),
        )
        return dir
    }

    @Test
    fun `health reports ok`() = testApplication {
        application { module(emptyAssets()) }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `identical ipa masters with a perfect score`() = testApplication {
        application { module(emptyAssets()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "cat", expectedIpa = "kæt", actualIpa = "kæt"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val feedback = response.body<PronunciationFeedback>()
        assertEquals(100, feedback.score)
        assertTrue(feedback.isMastery)
        assertEquals(3, feedback.letterFeedback.size)
    }

    @Test
    fun `a missing word is not mastered`() = testApplication {
        application { module(emptyAssets()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "cat dog", expectedIpa = "kæt dɒɡ", actualIpa = "kæt"))
        }

        val feedback = response.body<PronunciationFeedback>()
        assertEquals(50, feedback.score)
        assertEquals(50, feedback.completeness)
        assertEquals(false, feedback.isMastery)
    }

    @Test
    fun `score with cmudict generates reference ipa from text`() = testApplication {
        application { module(assetsWithCmuDict()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // No expectedIpa: the server phonemizes "cat" with its CMUdict asset.
        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "cat", actualIpa = "kˈæt"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val feedback = response.body<PronunciationFeedback>()
        assertEquals(100, feedback.score)
        assertTrue(feedback.isMastery)
    }

    @Test
    fun `british thirty is not penalised when the client sends the US reference ipa`() = testApplication {
        // The real app path: the lesson carries the primary CMUdict reading, which
        // spells the American flap as /d/ (`θˈɝdˌi`). A British learner says /t/.
        // The server must widen the provided reference with the dictionary's
        // alternate `TH IR1 T IY2` reading instead of charging a missing /d/.
        application { module(assetsWithThirtyVariants()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "thirty", expectedIpa = "θˈɝdˌi", actualIpa = "θˈɜːti"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val feedback = response.body<PronunciationFeedback>()
        assertEquals(100, feedback.score)
        assertTrue(feedback.isMastery)
        assertTrue(
            feedback.profile?.stats.orEmpty().none { it.phoneme == "d" },
            "the British /t/ must not create a /d/ sound error: ${feedback.profile?.stats}",
        )
    }

    @Test
    fun `server-side reference also accepts the british thirty`() = testApplication {
        application { module(assetsWithThirtyVariants()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // No expectedIpa: the server phonemizes "thirty" and applies its variants.
        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "thirty", actualIpa = "θˈɜːti"))
        }

        val feedback = response.body<PronunciationFeedback>()
        assertEquals(100, feedback.score)
    }

    @Test
    fun `a british reference ipa with an explicit t is not penalised`() = testApplication {
        application { module(assetsWithThirtyVariants()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // The client may already send the British reading; it must pass, and the
        // dictionary's American flap must remain an acceptable alternate too.
        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "thirty", expectedIpa = "θˈɝtˌi", actualIpa = "θˈɜːti"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val feedback = response.body<PronunciationFeedback>()
        assertEquals(100, feedback.score)
    }

    @Test
    fun `the reported sentence accepts the british thirty`() = testApplication {
        application { module(assetsWithThirtyVariants()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(
                ScoreRequest(
                    text = "The flight leaves at three thirty.",
                    expectedIpa = "ðə flˈaɪt lˈivz ˈæt θɹˈi θˈɝdˌi",
                    actualIpa = "ðə flaɪt liːvz æt θɹiː θɜːti",
                ),
            )
        }

        val feedback = response.body<PronunciationFeedback>()
        assertTrue(feedback.score >= 95, "score was ${feedback.score}")
        assertTrue(
            feedback.profile?.stats.orEmpty().none { it.phoneme == "d" },
            "no /d/ may be reported as an error for a British /t/: ${feedback.profile?.stats}",
        )
    }

    @Test
    fun `the reported near sentence is perfect without the american r`() = testApplication {
        // The exact production report: the lesson carries the American reference
        // `nˈɪɹ`, the British audio transcribes "near" as `niə`, and the missing
        // /ɹ/ used to cost 8 points (92/100, "/ɹ/ at 0"). The British reading is
        // accepted as a variant, so the sentence is PERFECT without the /ɹ/.
        application { module(assetsWithNearVariants()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(
                ScoreRequest(
                    text = "The bin is near the gate.",
                    expectedIpa = "ðə bˈɪn ˈɪz nˈɪɹ ðə ɡˈeɪt",
                    actualIpa = "ðəbɪnɪzniəðəɡeɪt",
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val feedback = response.body<PronunciationFeedback>()
        assertEquals(100, feedback.score)
        assertTrue(feedback.isMastery)
        assertTrue(
            feedback.profile?.stats.orEmpty().none { it.phoneme == "ɹ" && it.correct < it.attempts },
            "no /ɹ/ error may be reported for a British near: ${feedback.profile?.stats}",
        )
    }

    @Test
    fun `without a dictionary the british thirty keeps the old penalty`() = testApplication {
        // Control: with no CMUdict asset the server cannot know the British reading,
        // so it scores the provided US reference as-is. This is the bug the wiring
        // above fixes; keeping the control makes a regression visible.
        application { module(emptyAssets()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "thirty", expectedIpa = "θˈɝdˌi", actualIpa = "θˈɜːti"))
        }

        val feedback = response.body<PronunciationFeedback>()
        assertEquals(75, feedback.score)
    }

    @Test
    fun `cloud and offline agree on the british thirty`() = testApplication {
        // Parity: the cloud widens the client reference with CMUdict; the device
        // cannot (no dictionary on the phone), so the publisher bakes the same
        // widened set into the drill. Both must return the same verdict.
        val assets = assetsWithThirtyVariants()
        application { module(assets) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val text = "The flight leaves at three thirty."
        val expected = "ðə flˈaɪt lˈivz ˈæt θɹˈi θˈɝdˌi"
        val actual = "ðə flaɪt liːvz æt θɹiː θɜːti"

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = text, expectedIpa = expected, actualIpa = actual))
        }
        val cloud = response.body<PronunciationFeedback>()

        // What ContentPublisher publishes, and what OnDeviceScorer will consume.
        val g2p = CmuDictG2P.fromLines(File(assets, "cmudict.dict").readLines())
        val published = widenReferenceVariants(expected, g2p.pronunciations(text).wordVariants)
        val offline = PhoneticComparator.calculateScoringResult(
            text = text,
            expected = expected,
            actual = actual,
            language = "en",
            expectedWordVariants = published,
        )

        assertEquals(cloud.score, offline.score, "cloud and offline must match")
        assertTrue(
            offline.alignment.none { it.expected == "d" && it.actual == "t" },
            "offline must not line the british /t/ up against an american /d/: ${offline.alignment}",
        )
    }

    @Test
    fun `audio without a configured model is service unavailable`() = testApplication {
        application { module(emptyAssets()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "cat", expectedIpa = "kæt", audioBase64 = "AAAA"))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `missing both audio and ipa is a bad request`() = testApplication {
        application { module(emptyAssets()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "cat", expectedIpa = "kæt"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a th-for-s substitution produces a named error and a profile entry`() = testApplication {
        application { module(emptyAssets()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "think", expectedIpa = "θɪŋk", actualIpa = "sɪŋk"))
        }

        val feedback = response.body<PronunciationFeedback>()
        assertEquals("en.th.stopping", feedback.errors.firstOrNull()?.ruleId)
        val theta = feedback.profile?.stats?.firstOrNull { it.phoneme == "θ" }
        assertEquals(1, theta?.attempts)
        assertEquals(0, theta?.correct)
        assertEquals("s", theta?.typicalError)
    }

    @Test
    fun `a sent profile is folded with the new attempt`() = testApplication {
        application { module(emptyAssets()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val previous = PhoneticProfileModel(
            stats = listOf(PhonemeStatModel("θ", attempts = 2, correct = 1, errors = mapOf("s" to 1))),
        )
        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(
                ScoreRequest(
                    text = "think",
                    expectedIpa = "θɪŋk",
                    actualIpa = "sɪŋk",
                    profile = previous,
                ),
            )
        }

        val theta = response.body<PronunciationFeedback>().profile?.stats?.firstOrNull { it.phoneme == "θ" }
        assertEquals(3, theta?.attempts)
        assertEquals(1, theta?.correct)
        assertEquals(2, theta?.errors?.get("s"))
    }

    @Test
    fun `score without an api key is rejected when one is configured`() = testApplication {
        application { module(emptyAssets(), adminToken = null, analytics = null, apiKey = "s3cret") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "cat", expectedIpa = "kæt", actualIpa = "kæt"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `score with a wrong api key is rejected`() = testApplication {
        application { module(emptyAssets(), adminToken = null, analytics = null, apiKey = "s3cret") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "wrong")
            setBody(ScoreRequest(text = "cat", expectedIpa = "kæt", actualIpa = "kæt"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `score with the configured api key is unchanged`() = testApplication {
        application { module(emptyAssets(), adminToken = null, analytics = null, apiKey = "s3cret") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "s3cret")
            setBody(ScoreRequest(text = "cat", expectedIpa = "kæt", actualIpa = "kæt"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val feedback = response.body<PronunciationFeedback>()
        assertEquals(100, feedback.score)
        assertTrue(feedback.isMastery)
    }

    @Test
    fun `score stays open when no api key is configured`() = testApplication {
        application { module(emptyAssets(), adminToken = null, analytics = null, apiKey = null) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = "cat", expectedIpa = "kæt", actualIpa = "kæt"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
