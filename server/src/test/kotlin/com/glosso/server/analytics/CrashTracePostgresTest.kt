package com.glosso.server.analytics

import com.glosso.server.api.CrashCauseDto
import com.glosso.server.api.CrashTraceDto
import com.glosso.server.api.CrashTraceResponse
import com.glosso.server.api.ErrorResponse
import com.glosso.server.api.StackFrameDto
import com.glosso.server.module
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrashTracePostgresTest {

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var store: AnalyticsStore

    @BeforeTest
    fun start() {
        postgres = EmbeddedPostgres.builder().start()
        val config = HikariConfig().apply {
            jdbcUrl = postgres.getJdbcUrl("postgres", "postgres")
            maximumPoolSize = 2
        }
        store = AnalyticsStore(HikariDataSource(config))
        store.ensureSchema()
    }

    @AfterTest
    fun stop() {
        store.close()
        postgres.close()
    }

    private fun rows(sql: String): List<List<String?>> =
        postgres.postgresDatabase.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    val columns = rs.metaData.columnCount
                    buildList { while (rs.next()) add((1..columns).map { rs.getString(it) }) }
                }
            }
        }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json() }
    }

    private fun ApplicationTestBuilder.app(storeInstance: AnalyticsStore? = store) {
        application { module(Files.createTempDirectory("glosso-crashes-test").toFile(), "admin-secret", storeInstance) }
    }

    @Test
    fun `valid crash trace returns 202 and is stored with causes and frames`() = testApplication {
        app()
        val client = jsonClient()
        val response = client.post("/v1/crash-traces") {
            contentType(ContentType.Application.Json)
            header("X-Device-Id", "dev-test-1")
            setBody(
                CrashTraceDto(
                    traceId = "tr-valid-01",
                    appVersion = "2.5.4",
                    platform = "android",
                    fatal = true,
                    kind = "crash",
                    exception = "java.lang.NullPointerException",
                    where = "LessonViewModel.stepTo",
                    causes = listOf(
                        CrashCauseDto(
                            type = "java.lang.NullPointerException",
                            frames = listOf(
                                StackFrameDto(
                                    className = "com.glosso.app.LessonViewModel",
                                    methodName = "stepTo",
                                    fileName = "LessonViewModel.kt",
                                    line = 42,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = response.body<CrashTraceResponse>()
        assertTrue(body.accepted)
        assertEquals("tr-valid-01", body.traceId)

        val stored = rows(
            """select trace_id, device_id, app_version, platform, fatal, kind, exception, "where", stack::text from glosso.crash_traces where trace_id = 'tr-valid-01'""",
        ).single()

        assertEquals("tr-valid-01", stored[0])
        assertEquals("dev-test-1", stored[1])
        assertEquals("2.5.4", stored[2])
        assertEquals("android", stored[3])
        assertEquals("t", stored[4])
        assertEquals("crash", stored[5])
        assertEquals("java.lang.NullPointerException", stored[6])
        assertEquals("LessonViewModel.stepTo", stored[7])
        assertTrue(stored[8]!!.contains("LessonViewModel.kt"))
        assertTrue(stored[8]!!.contains("42"))
    }

    @Test
    fun `free text in fields is discarded or rejected and exception message is never saved`() = testApplication {
        app()
        val client = jsonClient()

        // 1. Exception with free text / spaces / @ is rejected
        val badException = client.post("/v1/crash-traces") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "trace_id": "tr-bad-ex",
                    "platform": "android",
                    "exception": "Error with user maria@example.com"
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, badException.status)

        // 2. Trace with message and free text in frame fields: message is not saved, invalid frame fields discarded
        val response = client.post("/v1/crash-traces") {
            contentType(ContentType.Application.Json)
            header("X-Device-Id", "dev-sanitized")
            setBody(
                """
                {
                    "trace_id": "tr-sanitized-02",
                    "platform": "ios",
                    "exception": "FatalError",
                    "where": "AudioPlayer with maria@example.com",
                    "message": "secret student email maria@example.com",
                    "causes": [
                        {
                            "type": "FatalError",
                            "message": "secret password 12345",
                            "frames": [
                                {
                                    "className": "com.glosso.app.AudioPlayer",
                                    "methodName": "play for maria@example.com",
                                    "fileName": "http://evil.com/Audio.swift",
                                    "line": 100
                                }
                            ]
                        }
                    ]
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Accepted, response.status)

        val stored = rows(
            """select "where", stack::text from glosso.crash_traces where trace_id = 'tr-sanitized-02'""",
        ).single()

        // where had free text with spaces and @, so it was discarded
        assertEquals(null, stored[0])
        val stackText = stored[1]!!

        // Verify message is never stored
        assertFalse(stackText.contains("secret student email"))
        assertFalse(stackText.contains("secret password"))
        // Verify free text in methodName and fileName were discarded
        assertFalse(stackText.contains("maria@example.com"))
        assertFalse(stackText.contains("http://evil.com"))
        // But valid className and line were kept
        assertTrue(stackText.contains("com.glosso.app.AudioPlayer"))
        assertTrue(stackText.contains("100"))
    }

    @Test
    fun `body above limit is rejected`() = testApplication {
        app()
        val client = jsonClient()
        val largePadding = "A".repeat(70_000)
        val payload = """{"trace_id":"tr-huge","platform":"android","exception":"Exception","padding":"$largePadding"}"""
        val response = client.post("/v1/crash-traces") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `more than 4 causes and 64 frames are deterministically trimmed`() = testApplication {
        app()
        val client = jsonClient()
        val causes = (1..6).map { causeIdx ->
            CrashCauseDto(
                type = "Exception$causeIdx",
                frames = (1..70).map { frameIdx ->
                    StackFrameDto(
                        className = "com.glosso.Class$frameIdx",
                        methodName = "method$frameIdx",
                        fileName = "File$frameIdx.kt",
                        line = frameIdx,
                    )
                },
            )
        }

        val response = client.post("/v1/crash-traces") {
            contentType(ContentType.Application.Json)
            setBody(
                CrashTraceDto(
                    traceId = "tr-trimmed",
                    platform = "android",
                    exception = "TopException",
                    causes = causes,
                ),
            )
        }
        assertEquals(HttpStatusCode.Accepted, response.status)

        val record = store.crashTraces.get("tr-trimmed")
        assertNotNull(record)
        assertEquals(4, record.causes.size)
        for (cause in record.causes) {
            assertEquals(64, cause.frames.size)
        }
    }

    @Test
    fun `DELETE events deletes crash traces for that device`() = testApplication {
        app()
        val client = jsonClient()

        client.post("/v1/crash-traces") {
            contentType(ContentType.Application.Json)
            header("X-Device-Id", "device-erase")
            setBody(CrashTraceDto(traceId = "tr-erase", platform = "android", exception = "Ex"))
        }

        client.post("/v1/crash-traces") {
            contentType(ContentType.Application.Json)
            header("X-Device-Id", "device-keep")
            setBody(CrashTraceDto(traceId = "tr-keep", platform = "android", exception = "Ex"))
        }

        assertEquals(2, rows("select count(*) from glosso.crash_traces").single()[0]!!.toInt())

        val deleteResponse = client.delete("/v1/events") {
            header("X-Device-Id", "device-erase")
        }
        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        val remaining = rows("select trace_id, device_id from glosso.crash_traces")
        assertEquals(listOf(listOf("tr-keep", "device-keep")), remaining)
    }

    @Test
    fun `purgeOlderThan purges old crash traces`() {
        postgres.postgresDatabase.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    insert into glosso.crash_traces
                        (trace_id, platform, kind, exception, received_at)
                    values
                        ('tr-old', 'android', 'crash', 'OldEx', now() - interval '200 days'),
                        ('tr-new', 'android', 'crash', 'NewEx', now())
                    """.trimIndent(),
                )
            }
        }

        assertEquals(2, rows("select count(*) from glosso.crash_traces").single()[0]!!.toInt())

        val purged = store.purgeOlderThan(180)
        assertTrue(purged >= 1)

        val remaining = rows("select trace_id from glosso.crash_traces")
        assertEquals(listOf(listOf("tr-new")), remaining)
    }

    @Test
    fun `admin crash endpoints require auth`() = testApplication {
        app()
        val client = jsonClient()

        // Insert a trace for viewing
        client.post("/v1/crash-traces") {
            contentType(ContentType.Application.Json)
            setBody(
                CrashTraceDto(
                    traceId = "tr-admin-view",
                    platform = "android",
                    appVersion = "2.5.4",
                    exception = "ArithmeticException",
                    where = "Calculator.divide",
                    causes = listOf(
                        CrashCauseDto(
                            type = "ArithmeticException",
                            frames = listOf(
                                StackFrameDto(
                                    className = "com.glosso.Calculator",
                                    methodName = "divide",
                                    fileName = "Calculator.kt",
                                    line = 13,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        // 1. GET /admin/crashes without token -> 401
        val unauthList = client.get("/admin/crashes")
        assertEquals(HttpStatusCode.Unauthorized, unauthList.status)

        // 2. GET /admin/crashes with token in query -> 200 HTML
        val authList = client.get("/admin/crashes?token=admin-secret")
        assertEquals(HttpStatusCode.OK, authList.status)
        val listHtml = authList.bodyAsText()
        assertTrue(listHtml.contains("Glosso crash traces"))
        assertTrue(listHtml.contains("ArithmeticException"))

        // 3. GET /admin/crashes/{traceId} without token -> 401
        val unauthDetail = client.get("/admin/crashes/tr-admin-view")
        assertEquals(HttpStatusCode.Unauthorized, unauthDetail.status)

        // 4. GET /admin/crashes/{traceId} with token -> 200 HTML
        val authDetail = client.get("/admin/crashes/tr-admin-view?token=admin-secret")
        assertEquals(HttpStatusCode.OK, authDetail.status)
        val detailHtml = authDetail.bodyAsText()
        assertTrue(detailHtml.contains("tr-admin-view"))
        assertTrue(detailHtml.contains("Calculator.kt:13"))

        // 5. GET /admin/crashes/{traceId} for non-existent -> 404
        val notFound = client.get("/admin/crashes/tr-non-existent?token=admin-secret")
        assertEquals(HttpStatusCode.NotFound, notFound.status)

        // 6. GET /admin/crashes.json without token -> 401
        val unauthJson = client.get("/admin/crashes.json")
        assertEquals(HttpStatusCode.Unauthorized, unauthJson.status)

        // 7. GET /admin/crashes.json with query token -> 401 (only header auth allowed!)
        val queryTokenJson = client.get("/admin/crashes.json?token=admin-secret")
        assertEquals(HttpStatusCode.Unauthorized, queryTokenJson.status)

        // 8. GET /admin/crashes.json with Bearer header -> 200 JSON
        val authJson = client.get("/admin/crashes.json?days=7") {
            header(HttpHeaders.Authorization, "Bearer admin-secret")
        }
        assertEquals(HttpStatusCode.OK, authJson.status)
        val records = authJson.body<List<CrashTraceRecord>>()
        assertEquals(1, records.size)
        assertEquals("tr-admin-view", records[0].traceId)
        assertEquals("ArithmeticException", records[0].exception)
    }

    @Test
    fun `endpoint returns 503 when analytics is not configured`() = testApplication {
        app(storeInstance = null)
        val client = jsonClient()

        val response = client.post("/v1/crash-traces") {
            contentType(ContentType.Application.Json)
            setBody(CrashTraceDto(traceId = "tr-no-db", platform = "android", exception = "Ex"))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }
}
