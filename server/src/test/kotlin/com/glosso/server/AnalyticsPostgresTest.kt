package com.glosso.server

import com.glosso.server.analytics.AnalyticsEvent
import com.glosso.server.analytics.AnalyticsMirror
import com.glosso.server.analytics.AnalyticsStore
import com.glosso.server.api.AnalyticsDeleteResponse
import com.glosso.server.api.AnalyticsEventDto
import com.glosso.server.api.AnalyticsIngestRequest
import com.glosso.server.api.AnalyticsIngestResponse
import com.glosso.server.api.ScoreRequest
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The client events contract against a real PostgreSQL: only catalogued events
 * and properties are stored, a queued event keeps its device time, the learner
 * can erase their events and opt out of the server-side score event, and the
 * engagement report counts the funnel and retention from what was stored.
 */
class AnalyticsPostgresTest {

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

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }

    private class FakeMirror(var failErase: Boolean = false) : AnalyticsMirror {
        val sent = java.util.concurrent.CopyOnWriteArrayList<AnalyticsEvent>()
        val erased = mutableListOf<String>()
        override fun send(events: List<AnalyticsEvent>) { sent.addAll(events) }
        override fun erase(deviceId: String): Int {
            check(!failErase) { "umami down" }
            erased.add(deviceId)
            return 3
        }
        override fun purgeOlderThan(days: Int) = 0
    }

    private fun ApplicationTestBuilder.app(mirror: AnalyticsMirror? = null) {
        application { module(Files.createTempDirectory("glosso-analytics").toFile(), "admin", store, mirror = mirror) }
    }

    @Test
    fun `only catalogued events and properties are stored`() = testApplication {
        app()
        val response = jsonClient().post("/v1/events") {
            contentType(ContentType.Application.Json)
            header("X-Device-Id", "device-a")
            setBody(
                AnalyticsIngestRequest(
                    listOf(
                        AnalyticsEventDto(
                            name = "lesson_started",
                            properties = buildJsonObject {
                                put("lesson_id", JsonPrimitive("ru.lesson.a1.07"))
                                put("cefr", JsonPrimitive("A1"))
                                put("note", JsonPrimitive("free text about me"))
                            },
                        ),
                        AnalyticsEventDto(
                            name = "exercise_completed",
                            properties = buildJsonObject {
                                put("kind", JsonPrimitive("drill"))
                                put("score_band", JsonPrimitive(500))
                                put("mastery", JsonPrimitive("true"))
                            },
                        ),
                        AnalyticsEventDto(name = "score_attempt", properties = buildJsonObject { put("score", JsonPrimitive(100)) }),
                        AnalyticsEventDto(name = "anything_else"),
                    ),
                ),
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(AnalyticsIngestResponse(accepted = 2, dropped = 2), response.body<AnalyticsIngestResponse>())
        assertEquals(
            listOf(
                listOf("exercise_completed", "device-a", """{"kind": "drill"}"""),
                listOf("lesson_started", "device-a", """{"cefr": "A1", "lesson_id": "ru.lesson.a1.07"}"""),
            ),
            rows("select name, device_id, properties::text from glosso.analytics_event order by name"),
        )
    }

    @Test
    fun `a queued event keeps its device time unless the clock is implausible`() = testApplication {
        app()
        val queued = Instant.now().minus(Duration.ofDays(2))
        jsonClient().post("/v1/events") {
            contentType(ContentType.Application.Json)
            header("X-Device-Id", "device-a")
            setBody(
                AnalyticsIngestRequest(
                    listOf(
                        AnalyticsEventDto(name = "app_open", occurredAt = queued.toEpochMilli()),
                        AnalyticsEventDto(name = "session_start", occurredAt = Instant.now().plus(Duration.ofDays(3)).toEpochMilli()),
                    ),
                ),
            )
        }

        val ages = rows(
            "select name, extract(epoch from now() - occurred_at)::int from glosso.analytics_event order by name",
        ).associate { it[0] to it[1]!!.toInt() }
        assertTrue(ages.getValue("app_open") in 2 * 86_400 - 60..2 * 86_400 + 60, "kept: $ages")
        assertTrue(ages.getValue("session_start") in -60..60, "future clock falls back to now: $ages")
    }

    @Test
    fun `with the send time a wrong device clock cancels out`() = testApplication {
        app()
        // The device clock is ten days behind; the event happened an hour before sending.
        val deviceNow = Instant.now().minus(Duration.ofDays(10))
        jsonClient().post("/v1/events") {
            contentType(ContentType.Application.Json)
            header("X-Device-Id", "device-a")
            setBody(
                AnalyticsIngestRequest(
                    events = listOf(
                        AnalyticsEventDto(name = "app_open", occurredAt = deviceNow.minus(Duration.ofHours(1)).toEpochMilli()),
                    ),
                    sentAt = deviceNow.toEpochMilli(),
                ),
            )
        }

        val age = rows("select extract(epoch from now() - occurred_at)::int from glosso.analytics_event").single()[0]!!.toInt()
        assertTrue(age in 3_600 - 60..3_600 + 60, "an hour ago by the server clock: $age")
    }

    @Test
    fun `a device can erase its own events and nobody else's`() = testApplication {
        app()
        val client = jsonClient()
        for (device in listOf("device-a", "device-b")) {
            client.post("/v1/events") {
                contentType(ContentType.Application.Json)
                header("X-Device-Id", device)
                setBody(AnalyticsIngestRequest(listOf(AnalyticsEventDto(name = "app_open"))))
            }
        }

        val response = client.delete("/v1/events") { header("X-Device-Id", "device-a") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, response.body<AnalyticsDeleteResponse>().deleted)
        assertEquals(listOf(listOf("device-b")), rows("select device_id from glosso.analytics_event"))
        assertEquals(HttpStatusCode.BadRequest, client.delete("/v1/events").status)
    }

    @Test
    fun `turning statistics off also stops the server-side score event`() = testApplication {
        app()
        val client = jsonClient()
        for (optOut in listOf(true, false)) {
            client.post("/v1/score") {
                contentType(ContentType.Application.Json)
                header("X-Device-Id", if (optOut) "device-off" else "device-on")
                if (optOut) header("X-Analytics", "off")
                setBody(ScoreRequest(text = "cat", expectedIpa = "kæt", actualIpa = "kæt"))
            }
        }

        val deadline = System.currentTimeMillis() + 5_000
        while (rows("select 1 from glosso.analytics_event").isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        Thread.sleep(200)
        assertEquals(
            listOf(listOf("score_attempt", "device-on")),
            rows("select name, device_id from glosso.analytics_event"),
        )
    }

    @Test
    fun `engagement counts the funnel of the window and day-N retention`() {
        val today = Instant.now()
        fun at(daysAgo: Long) = today.minus(Duration.ofDays(daysAgo))
        postgres.postgresDatabase.connection.use { connection ->
            connection.createStatement().use { statement ->
                val inserts = listOf(
                    // a: opened 8 days ago, came back the next day and a week later.
                    "('app_open', 'a', '${at(8)}')",
                    "('app_open', 'a', '${at(7)}')",
                    "('app_open', 'a', '${at(1)}')",
                    "('onboarding_completed', 'a', '${at(8)}')",
                    "('lesson_started', 'a', '${at(8)}')",
                    // b: opened 8 days ago and never again.
                    "('app_open', 'b', '${at(8)}')",
                    "('onboarding_completed', 'b', '${at(8)}')",
                    // c: a lesson without an open in the window is not in the cohort.
                    "('lesson_started', 'c', '${at(2)}')",
                )
                statement.execute(
                    "insert into glosso.analytics_event (name, device_id, occurred_at) values " +
                        inserts.joinToString(","),
                )
            }
        }

        val engagement = store.engagement(30)

        val funnel = engagement.funnel.associate { it.name to it.devices }
        assertEquals(2, funnel["app_open"])
        assertEquals(2, funnel["onboarding_completed"])
        assertEquals(1, funnel["lesson_started"])
        assertEquals(0, funnel["lesson_completed"])
        val retention = engagement.retention.associateBy { it.day }
        assertEquals(2, retention.getValue(1).eligible)
        assertEquals(1, retention.getValue(1).retained)
        assertEquals(2, retention.getValue(7).eligible)
        assertEquals(1, retention.getValue(7).retained)
        assertEquals(0, retention.getValue(30).eligible)
    }

    @Test
    fun `time in the app adds up the stretches of each session`() {
        val now = Instant.now()
        postgres.postgresDatabase.connection.use { connection ->
            connection.createStatement().use { statement ->
                // a: one session in two stretches (60 + 120 s) and another of 300 s, same day.
                // b: one session of 30 s. A malformed duration is ignored.
                statement.execute(
                    """
                    insert into glosso.analytics_event (name, device_id, session_id, properties, occurred_at) values
                      ('session_end', 'a', 's1', '{"duration_s": 60}', '$now'),
                      ('session_end', 'a', 's1', '{"duration_s": 120}', '$now'),
                      ('session_end', 'a', 's2', '{"duration_s": 300}', '$now'),
                      ('session_end', 'b', 's3', '{"duration_s": 30}', '$now'),
                      ('session_end', 'b', 's4', '{"duration_s": "x"}', '$now')
                    """.trimIndent(),
                )
            }
        }

        val time = store.engagement(30).time

        assertEquals(3, time.sessions)
        assertEquals(2, time.devices)
        assertEquals(180, time.medianSessionSeconds)
        assertEquals(170, time.averageSessionSeconds)
        // a: 480 s = 8 min that day; b: 30 s = 0.5 min. Mean 4.25 -> 4.3 (rounded to one decimal).
        assertEquals(4.3, time.minutesPerActiveDay)
        assertEquals(1.5, time.sessionsPerDevice)
    }

    @Test
    fun `stored events are copied to the dashboard and erased from it too`() = testApplication {
        val mirror = FakeMirror()
        app(mirror)
        val client = jsonClient()
        client.post("/v1/events") {
            contentType(ContentType.Application.Json)
            header("X-Device-Id", "device-a")
            setBody(AnalyticsIngestRequest(listOf(AnalyticsEventDto(name = "app_open"), AnalyticsEventDto(name = "nope"))))
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (mirror.sent.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("app_open"), mirror.sent.map { it.name }, "only what was stored is copied")
        assertEquals("device-a", mirror.sent.single().deviceId)

        val response = client.delete("/v1/events") { header("X-Device-Id", "device-a") }

        assertEquals(listOf("device-a"), mirror.erased)
        assertEquals(4, response.body<AnalyticsDeleteResponse>().deleted)
    }

    @Test
    fun `if the dashboard copy cannot be erased nothing is and the learner is told`() = testApplication {
        app(FakeMirror(failErase = true))
        val client = jsonClient()
        client.post("/v1/events") {
            contentType(ContentType.Application.Json)
            header("X-Device-Id", "device-a")
            setBody(AnalyticsIngestRequest(listOf(AnalyticsEventDto(name = "app_open"))))
        }

        val response = client.delete("/v1/events") { header("X-Device-Id", "device-a") }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(1, rows("select 1 from glosso.analytics_event").size, "kept, so a retry deletes both")
    }
}
