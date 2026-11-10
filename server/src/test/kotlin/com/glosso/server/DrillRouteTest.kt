package com.glosso.server

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.glosso.engine.model.ContentManifest
import com.glosso.engine.model.DrillPlanModel
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.server.api.DrillRequest

class DrillRouteTest {

    private fun assetsWithContent(): File {
        val dir = Files.createTempDirectory("glosso-drills-assets").toFile()
        File(dir, "cmudict.dict").writeText(
            """
            ;;; minimal test dictionary
            THINK  TH IH1 NG K
            SINK  S IH1 NG K
            SISTER  S IH1 S T ER0
            THIRSTY  TH ER1 S T IY0
            MY  M AY1
            IS  IH1 Z
            """.trimIndent(),
        )
        File(dir, "drills").mkdirs()
        File(dir, "drills/family.json").writeText(
            """
            {
              "version": 1,
              "language": "en",
              "drills": [
                {
                  "id": "th.family.001",
                  "theme": "family",
                  "level": "A1",
                  "target": "θ",
                  "contrast": "s",
                  "focus": ["think", "sink"],
                  "text": "I think my sister is thirsty.",
                  "translations": {"es": "Creo que mi hermana tiene sed."}
                }
              ]
            }
            """.trimIndent(),
        )
        return dir
    }

    private fun emptyAssets(): File = Files.createTempDirectory("glosso-empty-drills").toFile()

    private val weakTheta = PhoneticProfileModel(
        stats = listOf(PhonemeStatModel("θ", attempts = 5, correct = 0, errors = mapOf("s" to 5))),
    )

    @Test
    fun `a weak sound yields a drill from the bank`() = testApplication {
        application { module(assetsWithContent()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/drills") {
            contentType(ContentType.Application.Json)
            setBody(DrillRequest(profile = weakTheta))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val plan = response.body<DrillPlanModel>()
        assertEquals("th.family.001", plan.drills.single().id)
        assertTrue(plan.themes.any { it.id == "family" })
    }

    @Test
    fun `a theme filter excludes other themes`() = testApplication {
        application { module(assetsWithContent()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/drills") {
            contentType(ContentType.Application.Json)
            setBody(DrillRequest(profile = weakTheta, themes = listOf("travel")))
        }

        assertTrue(response.body<DrillPlanModel>().drills.isEmpty())
    }

    @Test
    fun `without content the endpoint is unavailable`() = testApplication {
        application { module(emptyAssets()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/drills") {
            contentType(ContentType.Application.Json)
            setBody(DrillRequest(profile = weakTheta))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `an empty profile is a bad request`() = testApplication {
        application { module(assetsWithContent()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/drills") {
            contentType(ContentType.Application.Json)
            setBody(DrillRequest(profile = PhoneticProfileModel()))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `the manifest is served when present`() = testApplication {
        val dir = assetsWithContent()
        File(dir, "manifest.json").writeText(
            """{"version":1,"language":"en","themes":[{"id":"family","labels":{"es":"Familia"}}],"banks":[]}""",
        )
        application { module(dir) }

        val response = client.get("/v1/content/manifest")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `the shipped content db is served`() = testApplication {
        val dir = assetsWithContent()
        File(dir, "drills").deleteRecursively()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir, "content.db").absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("create table content (id text primary key, kind text, language text, " +
                    "level text, theme text, text text, data text)")
                st.execute(
                    "insert into content values ('th.s.family.db','DRILL','en','A1','family'," +
                        "'I think my sister is thirsty.', " +
                        "'{\"id\":\"th.s.family.db\",\"language\":\"en\",\"level\":\"A1\",\"theme\":\"family\"," +
                        "\"targets\":[\"θ\"],\"target\":\"θ\",\"contrast\":\"s\",\"ruleId\":\"en.th.stopping\"," +
                        "\"focus\":[\"think\",\"sink\"],\"text\":\"I think my sister is thirsty.\"," +
                        "\"ipa\":\"ˈaɪ θˈɪŋk mˈaɪ sˈɪstɚ ˈɪz θˈɝsti.\"," +
                        "\"audio\":{\"en-US-female\":\"audio/x.ogg\",\"en-US-female:slow\":\"audio/x.slow.ogg\"}," +
                        "\"translations\":{},\"cue\":{},\"buildup\":[]}')",
                )
                st.execute(
                    "insert into content values ('story.db','STORY','en','A1','family','A Morning'," +
                        "'{\"id\":\"story.db\",\"language\":\"en\",\"level\":\"A1\",\"theme\":\"family\"," +
                        "\"title\":\"A Morning\",\"paragraphs\":[\"Anna wakes up.\"],\"translations\":{}}')",
                )
            }
        }
        application { module(dir) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.post("/v1/drills") {
            contentType(ContentType.Application.Json)
            setBody(DrillRequest(profile = weakTheta))
        }

        val plan = response.body<DrillPlanModel>()
        assertEquals("th.s.family.db", plan.drills.single().id)
        assertEquals("audio/x.ogg", plan.drills.single().audio["en-US-female"])
    }

    @Test
    fun `the manifest derives levels and themes from the content db`() = testApplication {
        val dir = assetsWithContent()
        File(dir, "drills").deleteRecursively()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir, "content.db").absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("create table content (id text primary key, kind text, language text, " +
                    "level text, theme text, text text, data text)")
                st.execute(
                    "insert into content values ('lesson.a1','LESSON','en','A1','city','A'," +
                        "'{\"id\":\"lesson.a1\",\"level\":\"A1\",\"theme\":\"city\"}')",
                )
                st.execute(
                    "insert into content values ('lesson.c3','LESSON','en','C3','news-science','B'," +
                        "'{\"id\":\"lesson.c3\",\"level\":\"C3\",\"theme\":\"news-science\"}')",
                )
            }
        }
        // A stored manifest that predates the new level/theme.
        File(dir, "manifest.json").writeText(
            """{"version":1,"language":"en","levels":["A1"],"themes":[{"id":"city","labels":{"en":"City"}}],"banks":[]}""",
        )
        application { module(dir) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val manifest = client.get("/v1/content/manifest").body<com.glosso.engine.model.ContentManifest>()

        assertEquals(listOf("A1", "C3"), manifest.levels)
        assertTrue(manifest.themes.any { it.id == "news-science" })
        assertEquals("City", manifest.themes.first { it.id == "city" }.labels["en"])
    }

    @Test
    fun `other content kinds are served raw`() = testApplication {
        val dir = assetsWithContent()
        File(dir, "drills").deleteRecursively()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir, "content.db").absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("create table content (id text primary key, kind text, language text, " +
                    "level text, theme text, text text, data text)")
                st.execute(
                    "insert into content values ('story.db','STORY','en','A1','family','A Morning'," +
                        "'{\"id\":\"story.db\",\"title\":\"A Morning\",\"paragraphs\":[\"Anna wakes up.\"]}')",
                )
            }
        }
        application { module(dir) }

        val response = client.get("/v1/content/STORY")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body<String>().contains("A Morning"))
    }

    @Test
    fun `words can be looked up by lemma`() = testApplication {
        val dir = assetsWithContent()
        File(dir, "drills").deleteRecursively()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir, "content.db").absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("create table content (id text primary key, kind text, language text, " +
                    "level text, theme text, text text, data text)")
                st.execute(
                    "insert into content values ('w.cat','WORD','en','A1','pets','cat'," +
                        "'{\"id\":\"w.cat\",\"language\":\"en\",\"level\":\"A1\",\"theme\":\"pets\",\"lemma\":\"cat\"}')",
                )
                st.execute(
                    "insert into content values ('w.dog','WORD','en','A1','pets','dog'," +
                        "'{\"id\":\"w.dog\",\"language\":\"en\",\"level\":\"A1\",\"theme\":\"pets\",\"lemma\":\"dog\"}')",
                )
            }
        }
        application { module(dir) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // Case-insensitive, and it never leaks a word that was not asked for.
        val response = client.get("/v1/content/WORD?lemmas=Cat")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<String>()
        assertTrue(body.contains("\"lemma\":\"cat\""))
        assertFalse(body.contains("\"lemma\":\"dog\""))
    }

    @Test
    fun `versioned content is cacheable while unversioned content is not`() = testApplication {
        application { module(assetsWithStory()) }

        val versioned = client.get("/v1/content/STORY?v=v1")
        assertTrue(
            versioned.headers[HttpHeaders.CacheControl].orEmpty().contains("max-age=31536000"),
        )

        val plain = client.get("/v1/content/STORY")
        assertEquals("no-store", plain.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `the manifest revalidates with an etag`() = testApplication {
        val dir = assetsWithContent()
        File(dir, "manifest.json").writeText(
            """{"version":1,"language":"en","themes":[],"banks":[]}""",
        )
        application { module(dir) }

        val first = client.get("/v1/content/manifest")
        val etag = first.headers[HttpHeaders.ETag]
        assertNotNull(etag)
        assertEquals("no-cache", first.headers[HttpHeaders.CacheControl])

        val revalidated = client.get("/v1/content/manifest") {
            header(HttpHeaders.IfNoneMatch, etag)
        }

        assertEquals(HttpStatusCode.NotModified, revalidated.status)
    }

    @Test
    fun `the asset version changes when an audio file changes`() {
        val dir = assetsWithContent()
        File(dir, "manifest.json").writeText(
            """{"version":1,"language":"en","themes":[],"banks":[]}""",
        )
        val clip = File(dir, "audio").apply { mkdirs() }.let { File(it, "city.ogg") }
        clip.writeBytes(byteArrayOf(1, 2, 3))

        val before = manifestAssetVersion(dir)
        // A redeployed clip keeps its name: same path, new bytes and mtime.
        clip.writeBytes(byteArrayOf(4, 5, 6, 7))
        clip.setLastModified(clip.lastModified() + 5_000)
        val after = manifestAssetVersion(dir)

        assertTrue(before.isNotBlank(), "a fingerprint is served")
        assertTrue(before != after, "the fingerprint moves with the asset")
    }

    /** Loads the content dir in a throwaway server and reads the served version. */
    private fun manifestAssetVersion(dir: File): String {
        var version = ""
        testApplication {
            application { module(dir) }
            val client = createClient { install(ContentNegotiation) { json() } }
            version = client.get("/v1/content/manifest").body<ContentManifest>().assetVersion
        }
        return version
    }

    /** Assets whose content db holds one story, enough for the content route. */
    private fun assetsWithStory(): File {
        val dir = assetsWithContent()
        File(dir, "drills").deleteRecursively()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir, "content.db").absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("create table content (id text primary key, kind text, language text, " +
                    "level text, theme text, text text, data text)")
                st.execute(
                    "insert into content values ('story.db','STORY','en','A1','family','A Morning'," +
                        "'{\"id\":\"story.db\",\"title\":\"A Morning\",\"paragraphs\":[\"Anna wakes up.\"]}')",
                )
            }
        }
        return dir
    }
}
