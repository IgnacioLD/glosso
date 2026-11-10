package com.glosso.server.analytics

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * What the Umami copy receives and what "delete my usage data" removes from it.
 * The erase runs against the subset of Umami 3.4.0's schema it touches.
 */
class UmamiMirrorTest {

    private val app = "e5cd45e5-36e3-4f7e-a489-b521c995f059"
    private val other = "3deadfa7-03f4-4bc1-a86d-fb72c1f9f5bf"
    private lateinit var postgres: EmbeddedPostgres

    @BeforeTest
    fun start() {
        postgres = EmbeddedPostgres.builder().start()
        postgres.postgresDatabase.connection.use { c ->
            c.createStatement().use { s ->
                s.execute("create table session (session_id uuid primary key, website_id uuid, distinct_id varchar(50))")
                s.execute("create table website_event (event_id uuid primary key, website_id uuid, session_id uuid, created_at timestamptz default now())")
                s.execute("create table event_data (event_data_id uuid primary key, website_id uuid, website_event_id uuid)")
                s.execute(
                    "create table session_data (session_data_id uuid primary key, website_id uuid, " +
                        "session_id uuid, distinct_id varchar(50))",
                )
                s.execute("create table revenue (revenue_id uuid primary key, website_id uuid, session_id uuid)")
                s.execute("create table session_replay (replay_id uuid primary key, website_id uuid, session_id uuid)")
                s.execute("create table session_link (website_id uuid, session_id uuid, distinct_id varchar(50))")
            }
        }
    }

    @AfterTest
    fun stop() = postgres.close()

    private fun mirror() = UmamiMirror("http://umami.test", app, postgres.postgresDatabase)

    private fun payload(body: String): JsonObject =
        Json.parseToJsonElement(body).jsonObject.getValue("payload").jsonObject

    private fun count(table: String): Int = postgres.postgresDatabase.connection.use { c ->
        c.createStatement().use { s -> s.executeQuery("select count(*) from $table").use { it.next(); it.getInt(1) } }
    }

    @Test
    fun `app_open is a page view and an event, with the install as the visitor and its own time`() {
        val at = Instant.parse("2026-10-05T10:00:00Z")
        val bodies = mirror().payloads(
            AnalyticsEvent(
                name = "app_open",
                deviceId = "device-a",
                appVersion = "2.5.3",
                language = "ru",
                propertiesJson = """{"cold":true}""",
                occurredAt = at,
            ),
        )

        assertEquals(2, bodies.size)
        val view = payload(bodies[0])
        assertEquals(null, view["name"])
        assertEquals("/", view.getValue("url").jsonPrimitive.content)
        val event = payload(bodies[1])
        assertEquals("app_open", event.getValue("name").jsonPrimitive.content)
        assertEquals("device-a", event.getValue("id").jsonPrimitive.content)
        assertEquals(at.epochSecond, event.getValue("timestamp").jsonPrimitive.long)
        assertEquals(app, event.getValue("website").jsonPrimitive.content)
        val data = event.getValue("data").jsonObject
        assertEquals("true", data.getValue("cold").jsonPrimitive.content)
        assertEquals("2.5.3", data.getValue("app_version").jsonPrimitive.content)
        assertEquals("ru", data.getValue("course").jsonPrimitive.content)
        assertEquals(null, event["ip"], "the learner's IP never reaches Umami")
    }

    @Test
    fun `a lesson start is a page view of that lesson and other events are not`() {
        val lesson = mirror().payloads(
            AnalyticsEvent(name = "lesson_started", deviceId = "d", propertiesJson = """{"lesson_id":"ru.lesson.a1.07"}"""),
        )
        assertEquals("/lesson/ru.lesson.a1.07", payload(lesson[0]).getValue("url").jsonPrimitive.content)
        assertEquals(1, mirror().payloads(AnalyticsEvent(name = "exercise_completed", deviceId = "d")).size)
    }

    @Test
    fun `erasing removes one install from the app site and nothing else`() {
        postgres.postgresDatabase.connection.use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """
                    insert into session values
                      ('00000000-0000-0000-0000-000000000001', '$app', 'device-a'),
                      ('00000000-0000-0000-0000-000000000002', '$app', 'device-a'),
                      ('00000000-0000-0000-0000-000000000003', '$app', 'device-b'),
                      ('00000000-0000-0000-0000-000000000004', '$other', 'device-a');
                    insert into website_event (event_id, website_id, session_id) values
                      ('00000000-0000-0000-0000-0000000000a1', '$app', '00000000-0000-0000-0000-000000000001'),
                      ('00000000-0000-0000-0000-0000000000a2', '$app', '00000000-0000-0000-0000-000000000002'),
                      ('00000000-0000-0000-0000-0000000000b1', '$app', '00000000-0000-0000-0000-000000000003'),
                      ('00000000-0000-0000-0000-0000000000c1', '$other', '00000000-0000-0000-0000-000000000004');
                    insert into event_data values
                      ('00000000-0000-0000-0000-0000000000d1', '$app', '00000000-0000-0000-0000-0000000000a1'),
                      ('00000000-0000-0000-0000-0000000000d2', '$app', '00000000-0000-0000-0000-0000000000b1');
                    insert into session_link values ('$app', '00000000-0000-0000-0000-000000000001', 'device-a');
                    """.trimIndent(),
                )
            }
        }

        assertEquals(6, mirror().erase("device-a"))

        assertEquals(2, count("session"), "device-b and the other site stay")
        assertEquals(2, count("website_event"))
        assertEquals(1, count("event_data"))
        assertEquals(0, count("session_link"))
    }

    @Test
    fun `retention drops old app events and the sessions left empty`() {
        postgres.postgresDatabase.connection.use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """
                    insert into session values
                      ('00000000-0000-0000-0000-000000000001', '$app', 'old'),
                      ('00000000-0000-0000-0000-000000000002', '$app', 'recent'),
                      ('00000000-0000-0000-0000-000000000004', '$other', 'site');
                    insert into website_event values
                      ('00000000-0000-0000-0000-0000000000a1', '$app', '00000000-0000-0000-0000-000000000001', now() - interval '200 days'),
                      ('00000000-0000-0000-0000-0000000000a2', '$app', '00000000-0000-0000-0000-000000000002', now() - interval '2 days'),
                      ('00000000-0000-0000-0000-0000000000c1', '$other', '00000000-0000-0000-0000-000000000004', now() - interval '400 days');
                    """.trimIndent(),
                )
            }
        }

        mirror().purgeOlderThan(180)

        assertEquals(2, count("website_event"), "the recent app event and the other site stay")
        assertEquals(2, count("session"), "only the app session left empty goes")
    }

    @Test
    fun `events are posted to the send endpoint over HTTP 1_1 with the app user agent`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/send") { exchange ->
            seen.add(
                listOf(
                    exchange.protocol,
                    exchange.requestHeaders.getFirst("Upgrade") ?: "-",
                    exchange.requestHeaders.getFirst("User-Agent"),
                    exchange.requestBody.readBytes().decodeToString(),
                ).joinToString(" | "),
            )
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            UmamiMirror("http://127.0.0.1:${server.address.port}", app, null)
                .send(listOf(AnalyticsEvent(name = "app_open", deviceId = "device-a")))
        } finally {
            server.stop(0)
        }

        assertEquals(2, seen.size, "a page view and the event")
        val (protocol, upgrade, agent, body) = seen.last().split(" | ")
        assertEquals("HTTP/1.1", protocol)
        assertEquals("-", upgrade, "no h2c upgrade: Umami's server drops the connection on it")
        assertEquals("Mozilla/5.0 (Linux; Android) GlossoApp", agent)
        assertEquals("app_open", payload(body).getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `every event of one app session reuses the visit token Umami answered`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        var issued = 0
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/send") { exchange ->
            val name = payload(exchange.requestBody.readBytes().decodeToString())["name"]?.jsonPrimitive?.content ?: "view"
            seen.add("$name:${exchange.requestHeaders.getFirst("x-umami-cache") ?: "-"}")
            val answer = """{"cache":"token-${++issued}"}""".toByteArray()
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        server.start()
        try {
            val mirror = UmamiMirror("http://127.0.0.1:${server.address.port}", app, null)
            mirror.send(listOf(AnalyticsEvent(name = "app_open", deviceId = "a", sessionId = "s1")))
            mirror.send(
                listOf(
                    AnalyticsEvent(name = "session_end", deviceId = "a", sessionId = "s1"),
                    AnalyticsEvent(name = "app_open", deviceId = "b", sessionId = "s2"),
                ),
            )
        } finally {
            server.stop(0)
        }

        assertEquals(
            listOf(
                "view:-", // the first request of a session has no visit yet
                "app_open:token-1",
                "session_end:token-2", // a later batch of the same session stays in its visit
                "view:-", // another session starts its own
                "app_open:token-4",
            ),
            seen,
        )
    }

    @Test
    fun `screens are page views only and stories are pages of their own`() {
        val screen = mirror().payloads(
            AnalyticsEvent(name = "screen_view", deviceId = "d", propertiesJson = """{"screen":"settings"}"""),
        )
        assertEquals(1, screen.size)
        assertEquals(null, payload(screen[0])["name"])
        assertEquals("/settings", payload(screen[0]).getValue("url").jsonPrimitive.content)

        val story = mirror().payloads(
            AnalyticsEvent(name = "story_opened", deviceId = "d", propertiesJson = """{"story_id":"en.story.a1.03"}"""),
        )
        assertEquals("/story/en.story.a1.03", payload(story[0]).getValue("url").jsonPrimitive.content)
        assertEquals("story_opened", payload(story[1]).getValue("name").jsonPrimitive.content)
    }
}
