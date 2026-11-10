package com.glosso.server.accent

import com.glosso.engine.model.PronunciationFeedback
import com.glosso.server.api.ScoreRequest
import com.glosso.server.module
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
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
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiAccentRouteTest {

    private val adminToken = "test-admin-token"
    private val southernVoice = "d1d9c946-7cfc-4378-85a4-07d09827cb7e"

    private companion object {
        const val POUNDS_TEXT = "That is two pounds."
        const val POUNDS_CANONICAL = "ðˈæt ˈɪz tˈu pˈaʊndz"
        const val POUNDS_REFERENCE = "ðˈat ɪz tˈuː pˈaʊndz"
        const val POUNDS_HEARD = "ðatɪztuːpaʊndz"
    }

    private fun assets(): File {
        val dir = Files.createTempDirectory("glosso-accent-assets").toFile()
        File(dir, "cmudict.dict").writeText(
            """
            ;;; minimal test dictionary
            I  AY1
            THINK  TH IH1 NG K
            THE  DH AH0
            CAR  K AA1 R
            IS  IH1 Z
            NEAR  N IH1 R
            """.trimIndent(),
        )
        return dir
    }

    /**
     * A catalogue with one dialogue line that carries its accent reference, the
     * way `publishContent` bakes it into `content.db`.
     */
    private fun File.withPublishedReference(): File = apply {
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(this, "content.db").absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "create table content (id text primary key, kind text, language text, " +
                        "level text, theme text, text text, data text)",
                )
                st.execute(
                    "insert into content values ('en.dialogue.shop.001','DIALOGUE','en','A1','shopping','At the till'," +
                        "'{\"id\":\"en.dialogue.shop.001\",\"title\":\"At the till\",\"turns\":[{\"speaker\":\"A\"," +
                        "\"text\":\"$POUNDS_TEXT\",\"ipa\":\"$POUNDS_CANONICAL\"," +
                        "\"accentIpa\":{\"en-GB\":\"$POUNDS_REFERENCE\"}}]}')",
                )
            }
        }
    }

    private fun app(
        multiAccent: Boolean,
        fetcher: VoiceAudioFetcher = UnconfiguredVoiceFetcher,
        registry: VoiceRegistry? = null,
        assets: File = assets(),
        block: suspend (HttpClient, File) -> Unit,
    ) = testApplication {
        val voices = VoiceIngestService(registry ?: VoiceRegistry(File(assets, ".voices")), fetcher)
        application {
            module(
                assets,
                adminToken = adminToken,
                analytics = null,
                multiAccent = MultiAccentConfig(multiAccent),
                voiceIngest = voices,
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }
        block(client, assets)
    }

    private fun HttpRequestBuilder.admin() = header(HttpHeaders.Authorization, "Bearer $adminToken")

    private suspend fun HttpClient.score(request: ScoreRequest): PronunciationFeedback {
        val response = post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    /** A minimal RIFF/WAVE payload: enough for the container check, not real speech. */
    private fun wav(size: Int = 4096): ByteArray {
        val bytes = ByteArray(size)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WAVE".toByteArray().copyInto(bytes, 8)
        return bytes
    }

    @Test
    fun `with the flag off scoring is the legacy single reference and reports no accent`() =
        app(multiAccent = false) { client, _ ->
            val feedback = client.score(ScoreRequest(text = "I think", actualIpa = "aɪ θɪŋk"))
            assertEquals(100, feedback.score)
            assertNull(feedback.accentMatch)
        }

    @Test
    fun `with the flag on only the accents the course offers decide the score`() =
        app(multiAccent = true) { client, _ ->
            val british = client.score(
                ScoreRequest(text = "the car", expectedIpa = "ðə kˈɑɹ", actualIpa = "ðə kɑː", accent = "en-US"),
            )
            val match = checkNotNull(british.accentMatch)
            assertEquals(100, british.score)
            assertEquals("en-GB", match.accent)
            assertEquals("en-US", match.target, "the mode is reported but does not discriminate")
            assertEquals(setOf("en-GB", "en-US"), match.candidates.map { it.accent }.toSet())

            // TH-stopping belongs to a seed system the course does not offer: it
            // is a real error against both offered accents, not an Irish 100.
            val stopped = client.score(ScoreRequest(text = "I think", actualIpa = "aɪ tɪŋk"))
            assertTrue(stopped.score < 100, "th-stopping must not be forgiven: ${stopped.score}")
            assertTrue(stopped.accentMatch?.accent != "en-IE")
        }

    @Test
    fun `a native take reaches 100 against the published accent reference, sent or looked up`() {
        // What the model writes for a British voice saying it; the canonical
        // reading is the American dictionary one.
        val take = ScoreRequest(text = POUNDS_TEXT, expectedIpa = POUNDS_CANONICAL, actualIpa = POUNDS_HEARD)
        var single = 0

        app(multiAccent = false, assets = assets().withPublishedReference()) { client, _ ->
            val off = client.score(take)
            single = off.score
            assertTrue(single < 100, "the dictionary reading charges the accent: $single")
            assertNull(off.accentMatch)
        }
        // An app published before the content carried references sends none: the
        // server finds the sentence in its own catalogue.
        app(multiAccent = true, assets = assets().withPublishedReference()) { client, _ ->
            assertEquals(100, client.score(take).score)
        }
        // A current app sends the reference of the item it is scoring.
        app(multiAccent = true) { client, _ ->
            assertEquals(100, client.score(take.copy(accentIpa = mapOf("en-GB" to POUNDS_REFERENCE))).score)
        }
        // No reference anywhere: the flag never lowers the single-reference score.
        app(multiAccent = true) { client, _ ->
            assertTrue(client.score(take).score >= single)
        }
    }

    @Test
    fun `the published canonical reading is realized per accent and mixing r and no r is not rewarded`() =
        app(multiAccent = true) { client, _ ->
            val published = "ðə kˈɑɹ ˈɪz nˈɪɹ"
            val gb = client.score(ScoreRequest(text = "the car is near", expectedIpa = published, actualIpa = "ðə kɑː ɪz nɪə"))
            assertEquals(100, gb.score)
            assertEquals("en-GB", gb.accentMatch?.accent)

            val us = client.score(ScoreRequest(text = "the car is near", expectedIpa = published, actualIpa = "ðə kɑɹ ɪz nɪɹ"))
            assertEquals(100, us.score)
            assertEquals("en-US", us.accentMatch?.accent)

            val mixed = client.score(ScoreRequest(text = "the car is near", expectedIpa = published, actualIpa = "ðə kɑɹ ɪz nɪə"))
            assertTrue(mixed.score < 100, "rhotic car + non-rhotic near: ${mixed.score}")

        }

    @Test
    fun `the accent catalogue is public and says whether multi-accent scoring is on`() =
        app(multiAccent = false) { client, _ ->
            val catalogue: AccentCatalogResponse = client.get("/v1/accents").body()
            assertEquals(false, catalogue.multiAccentScoring)
            assertEquals(17, catalogue.systems.size)
            val south = catalogue.systems.single { it.id == "en-US-South-female" }
            assertEquals(listOf("price-monophthong", "pin-pen"), south.rules)
            assertTrue(south.rhotic)
        }

    @Test
    fun `the catalogue lists every accent the owner offers, new ones included and uncalibrated`() =
        app(multiAccent = false) { client, _ ->
            val systems: List<AccentSystemDto> = client.get("/v1/accents").body<AccentCatalogResponse>().systems
            assertEquals(
                listOf(
                    "en-GB-female", "en-GB-male",
                    "en-US-female", "en-US-male",
                    "en-US-CA-female",
                    "en-US-South-female", "en-US-South-male",
                    "en-US-NY-male",
                    "en-IE-female", "en-IE-male",
                    "en-AU-female", "en-AU-male",
                    "midwestern-american-female", "midwestern-american-male",
                    "canadian-english-female", "canadian-english-male",
                    "new-zealand-female",
                ),
                systems.map { it.id },
            )
            assertTrue(systems.none { it.calibrated }, "every accent is a seed until calibrated")
            val midwest = systems.first { it.id == "midwestern-american-male" }
            assertEquals("midwestern-american", midwest.accent)
            assertEquals(true, midwest.rhotic)
            assertEquals(listOf("cot-caught", "bag-raising"), midwest.rules)
            assertEquals(2, midwest.voices)

            val nz = systems.single { it.accent == "new-zealand" }
            assertEquals(false, nz.rhotic)
            assertEquals(listOf("nz-dress-raising"), nz.rules)
            assertEquals(2, nz.voices)
        }

    @Test
    fun `a voice of a new accent registers and joins its system`() =
        app(multiAccent = false) { client, assets ->
            val canadian = "996a8b96-4804-46f0-8e05-3fd4ef1a87cd"
            val response = client.post("/v1/admin/voices") {
                admin()
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterVoiceRequest(
                        voiceId = canadian,
                        accent = "canadian-english",
                        gender = "female",
                        audioBase64 = Base64.getEncoder().encodeToString(wav()),
                    ),
                )
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertTrue(response.body<RegisterVoiceResponse>().catalogued, "the new accent is in the catalogue")

            val list: VoiceListResponse = client.get("/v1/admin/voices") { admin() }.body()
            assertEquals(33, list.catalogue.size)
            assertTrue(list.uncatalogued.isEmpty(), "every catalogued voice joins a system")
            val voice = list.catalogue.single { it.voiceId == canadian }
            assertEquals("canadian-english-female", voice.system)
            assertTrue(voice.registered)
            assertTrue(File(assets, ".voices/$canadian.wav").isFile)
        }

    @Test
    fun `an operator registers a catalogued voice with inline audio`() =
        app(multiAccent = false) { client, assets ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/voices").status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.post("/v1/admin/voices") {
                    contentType(ContentType.Application.Json)
                    setBody(RegisterVoiceRequest(southernVoice, "en-US-South", "female"))
                }.status,
            )

            val response = client.post("/v1/admin/voices") {
                admin()
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterVoiceRequest(
                        voiceId = southernVoice.uppercase(),
                        accent = "en-us-south",
                        gender = "FEMALE",
                        audioBase64 = Base64.getEncoder().encodeToString(wav()),
                    ),
                )
            }
            assertEquals(HttpStatusCode.Created, response.status)
            val registered: RegisterVoiceResponse = response.body()
            assertTrue(registered.catalogued)
            assertEquals(southernVoice, registered.voice.voiceId)
            assertEquals("en-US-South", registered.voice.accent)
            assertEquals("inline", registered.voice.source)
            assertEquals("Southern US female 1", registered.voice.label)
            assertTrue(File(assets, ".voices/$southernVoice.wav").isFile)

            val list: VoiceListResponse = client.get("/v1/admin/voices") { admin() }.body()
            assertEquals("none", list.fetcher)
            assertEquals(33, list.catalogue.size)
            val south = list.catalogue.single { it.voiceId == southernVoice }
            assertTrue(south.registered)
            assertEquals("en-US-South-female", south.system)
            assertEquals(registered.voice.sha256, south.sha256)
            assertTrue(list.catalogue.filter { it.voiceId != southernVoice }.none { it.registered })
        }

    @Test
    fun `a voice is rejected when it contradicts the catalogue or has no usable audio`() =
        app(multiAccent = false) { client, _ ->
            suspend fun register(request: RegisterVoiceRequest): Pair<HttpStatusCode, String> {
                val response = client.post("/v1/admin/voices") {
                    admin()
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                val reason = if (response.status == HttpStatusCode.Created) "" else response.body<VoiceErrorResponse>().reason
                return response.status to reason
            }
            val audio = Base64.getEncoder().encodeToString(wav())
            assertEquals(
                HttpStatusCode.Conflict to "catalogue_mismatch",
                register(RegisterVoiceRequest(southernVoice, "en-IE", "female", audioBase64 = audio)),
            )
            assertEquals(
                HttpStatusCode.BadRequest to "unknown_accent",
                register(RegisterVoiceRequest(southernVoice, "en-XX", "female", audioBase64 = audio)),
            )
            assertEquals(
                HttpStatusCode.BadRequest to "invalid_voice_id",
                register(RegisterVoiceRequest("../../etc/passwd", "en-IE", "female", audioBase64 = audio)),
            )
            assertEquals(
                HttpStatusCode.ServiceUnavailable to "fetcher_not_configured",
                register(RegisterVoiceRequest(southernVoice, "en-US-South", "female")),
            )
            assertEquals(
                HttpStatusCode.UnprocessableEntity to "unsupported_audio",
                register(
                    RegisterVoiceRequest(
                        southernVoice,
                        "en-US-South",
                        "female",
                        audioBase64 = Base64.getEncoder().encodeToString(ByteArray(4096) { 'x'.code.toByte() }),
                    ),
                ),
            )
        }

    @Test
    fun `without inline audio the local-file fetcher supplies it`() {
        val local = Files.createTempDirectory("glosso-voice-local").toFile()
        File(local, "$southernVoice.wav").writeBytes(wav())
        app(multiAccent = false, fetcher = LocalFileVoiceFetcher(local)) { client, assets ->
            val response = client.post("/v1/admin/voices") {
                admin()
                contentType(ContentType.Application.Json)
                setBody(RegisterVoiceRequest(southernVoice, "en-US-South", "female"))
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("local", response.body<RegisterVoiceResponse>().voice.source)
            assertTrue(File(assets, ".voices/$southernVoice.wav").isFile)

            val missing = client.post("/v1/admin/voices") {
                admin()
                contentType(ContentType.Application.Json)
                setBody(RegisterVoiceRequest("630ed21c-2c5c-41cf-9d82-10a7fd668370", "en-US", "male"))
            }
            assertEquals(HttpStatusCode.BadGateway, missing.status)
            assertEquals("not_found", missing.body<VoiceErrorResponse>().reason)
        }
    }

    @Test
    fun `a storage failure answers with the reason, not a bare internal error`() {
        val blocker = Files.createTempFile("glosso-voice-blocked", ".file").toFile()
        app(multiAccent = false, registry = VoiceRegistry(File(blocker, ".voices"))) { client, _ ->
            val response = client.post("/v1/admin/voices") {
                admin()
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterVoiceRequest(
                        southernVoice,
                        "en-US-South",
                        "female",
                        audioBase64 = Base64.getEncoder().encodeToString(wav()),
                    ),
                )
            }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val error: VoiceErrorResponse = response.body()
            assertEquals("storage_error", error.reason)
            assertTrue(error.error.contains(".voices"), "the error names the failing path: ${error.error}")
        }
    }
}
