package com.glosso.server

import com.glosso.engine.model.DrillPlanModel
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.server.api.DrillRequest
import com.glosso.server.api.ScoreRequest
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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

/**
 * A French bank alongside the English fallback, to prove content, drills and
 * scoring are selected by the target language and never bleed into each other.
 */
class MultiLanguageContentTest {

    private val frenchDrillJson =
        """{"id":"fr.an.on.a1.001","language":"fr","level":"A1","theme":"family","targets":["ɑ̃"],""" +
            """"target":"ɑ̃","contrast":"ɔ̃","ruleId":"fr.an.on","focus":["dans","don"],""" +
            """"text":"Jean danse dans le salon.","ipa":"ʒɑ̃ dɑ̃s dɑ̃ lə salɔ̃","audio":{},""" +
            """"translations":{},"cue":{},"buildup":[]}"""

    /** English flat at the root (legacy layout) + a scoped French content dir. */
    private fun assets(): File {
        val dir = Files.createTempDirectory("glosso-ml-assets").toFile()
        File(dir, "cmudict.dict").writeText(
            """
            ;;; minimal test dictionary
            THINK  TH IH1 NG K
            SINK  S IH1 NG K
            """.trimIndent(),
        )
        val fr = File(dir, "content/fr")
        fr.mkdirs()
        File(fr, "manifest.json").writeText(
            """{"version":1,"language":"fr","themes":[{"id":"family","labels":{"en":"Family"}}],"banks":[]}""",
        )
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(fr, "content.db").absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("create table content (id text primary key, kind text, language text, " +
                    "level text, theme text, text text, data text)")
                st.execute(
                    "insert into content values ('fr.an.on.a1.001','DRILL','fr','A1','family'," +
                        "'Jean danse dans le salon.', '${frenchDrillJson.replace("'", "''")}')",
                )
            }
        }
        return dir
    }

    private val weakNasal = PhoneticProfileModel(
        stats = listOf(PhonemeStatModel("ɑ̃", attempts = 5, correct = 0, errors = mapOf("ɔ̃" to 5))),
    )

    @Test
    fun `french content is scoped to the language parameter`() = testApplication {
        application { module(assets()) }

        val fr = client.get("/v1/content/DRILL") { url { parameters.append("language", "fr") } }
        assertEquals(HttpStatusCode.OK, fr.status)
        assertTrue(fr.body<String>().contains("fr.an.on.a1.001"))

        val en = client.get("/v1/content/DRILL")
        assertEquals(HttpStatusCode.ServiceUnavailable, en.status)
    }

    @Test
    fun `french manifest is served for the language`() = testApplication {
        application { module(assets()) }

        val fr = client.get("/v1/content/manifest") { url { parameters.append("language", "fr") } }
        assertEquals(HttpStatusCode.OK, fr.status)
        assertTrue(fr.body<String>().contains("\"language\": \"fr\""))

        val en = client.get("/v1/content/manifest")
        assertEquals(HttpStatusCode.ServiceUnavailable, en.status)
    }

    @Test
    fun `drill planning uses the requested language bank`() = testApplication {
        application { module(assets()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/drills") {
            contentType(ContentType.Application.Json)
            setBody(DrillRequest(profile = weakNasal, language = "fr"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("fr.an.on.a1.001", response.body<DrillPlanModel>().drills.single().id)
    }

    @Test
    fun `scoring uses the french rules and matrix`() = testApplication {
        application { module(assets()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(
                ScoreRequest(
                    text = "dans",
                    expectedIpa = "dɑ̃",
                    actualIpa = "dɔ̃",
                    language = "fr",
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val feedback = response.body<PronunciationFeedback>()
        assertTrue(feedback.errors.any { it.ruleId == "fr.an.on" }, "expected the French rule to fire")
    }
}
