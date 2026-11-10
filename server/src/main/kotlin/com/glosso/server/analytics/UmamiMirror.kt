package com.glosso.server.analytics

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A copy of the stored events for a dashboard. Postgres stays the source of
 * truth; the mirror is best-effort for sending, but erasing must succeed or the
 * learner is told their data could not be deleted.
 */
interface AnalyticsMirror : AutoCloseable {
    fun send(events: List<AnalyticsEvent>)

    /** Removes everything the mirror holds for one install; returns the rows removed. */
    fun erase(deviceId: String): Int

    /** Applies the same retention as the store. */
    fun purgeOlderThan(days: Int): Int

    override fun close() {}
}

/**
 * Mirrors the app's events into the self-hosted Umami on the same host
 * (`docs/ANALYTICS-BETA.md`), so funnels and retention can be read in its UI.
 *
 * Sent from inside the VPS, never with the learner's IP: Umami resolves no
 * location for them. The installation id is Umami's distinct id, which keeps
 * one visitor per install, and each event keeps its own time. `app_open` and a
 * lesson start are also sent as page views, because Umami only counts visitors
 * and visits that have one.
 *
 * Erasing goes straight to Umami's database (pinned version, see
 * deploy/docker-compose.yml): its API deletes only by an internal session id
 * that rotates every month.
 */
class UmamiMirror(
    private val baseUrl: String,
    private val websiteId: String,
    private val database: DataSource?,
    // HTTP/1.1 on purpose: by default the JDK client asks a plain-http server to
    // upgrade to HTTP/2 (h2c), and Umami's Next.js server closes the connection on
    // that header ("header parser received no bytes").
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3))
        .build(),
) : AnalyticsMirror {

    /**
     * Umami's visit token per app session, so every event of one session lands in
     * one Umami visit and its "visit duration" is the time in the app (from
     * `app_open` to `session_end`). Without it Umami cuts visits at each clock
     * hour. Its own tracker script does the same with the `cache` it answers.
     */
    private val visits = ConcurrentHashMap<String, Visit>()

    private class Visit(val token: String, val lastSeen: Instant)

    override fun send(events: List<AnalyticsEvent>) {
        val now = Instant.now()
        visits.entries.removeIf { it.value.lastSeen < now - VISIT_MEMORY }
        for (event in events) {
            val session = event.sessionId
            for (body in payloads(event)) {
                val builder = HttpRequest.newBuilder(URI.create("$baseUrl/api/send"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                session?.let { visits[it] }?.let { builder.header("x-umami-cache", it.token) }
                val response = http.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                check(response.statusCode() in 200..299) { "umami answered ${response.statusCode()}" }
                val token = runCatching {
                    (Json.parseToJsonElement(response.body()) as JsonObject)["cache"]?.let { (it as JsonPrimitive).content }
                }.getOrNull()
                if (session != null && token != null) visits[session] = Visit(token, now)
            }
        }
    }

    /** The `/api/send` bodies for one event: the event itself, plus a page view where it is one. */
    internal fun payloads(event: AnalyticsEvent): List<String> {
        val properties = runCatching { Json.parseToJsonElement(event.propertiesJson) as JsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        val data = buildJsonObject {
            properties.forEach { (key, value) -> put(key, value) }
            event.appVersion?.let { put("app_version", it) }
            event.language?.let { put("course", it) }
        }
        fun prop(key: String) = (properties[key] as? JsonPrimitive)?.content
        val page = when (event.name) {
            "app_open" -> "/"
            "screen_view" -> prop("screen")?.let { "/$it" }
            "lesson_started" -> prop("lesson_id")?.let { "/lesson/$it" }
            "story_opened" -> prop("story_id")?.let { "/story/$it" }
            "paywall_viewed" -> "/paywall"
            else -> null
        }
        val seconds = (event.occurredAt ?: Instant.now()).epochSecond
        fun body(name: String?, url: String) = buildJsonObject {
            put("type", "event")
            put(
                "payload",
                buildJsonObject {
                    put("website", websiteId)
                    put("hostname", HOSTNAME)
                    put("url", url)
                    name?.let {
                        put("name", it)
                        put("data", data)
                    }
                    event.deviceId?.let { put("id", it.take(MAX_DISTINCT_ID)) }
                    event.language?.let { put("language", it) }
                    put("timestamp", seconds)
                    put("os", "Android")
                    put("browser", "Glosso")
                    put("device", "mobile")
                },
            )
        }.toString()
        // A screen view is only a page view: as an event too it would double every screen.
        if (event.name == "screen_view") return listOfNotNull(page?.let { body(null, it) })
        return listOfNotNull(page?.let { body(null, it) }, body(event.name, page ?: "/"))
    }

    override fun erase(deviceId: String): Int {
        val db = database ?: return 0
        db.connection.use { connection ->
            connection.autoCommit = false
            try {
                var removed = 0
                val sessions = "(select session_id from session where website_id = ?::uuid and distinct_id = ?)"
                val statements = listOf(
                    "delete from event_data where website_event_id in " +
                        "(select event_id from website_event where session_id in $sessions)",
                    "delete from website_event where session_id in $sessions",
                    "delete from session_data where session_id in $sessions",
                    "delete from revenue where session_id in $sessions",
                    "delete from session_replay where session_id in $sessions",
                    "delete from session_link where website_id = ?::uuid and distinct_id = ?",
                    "delete from session where website_id = ?::uuid and distinct_id = ?",
                )
                for (sql in statements) {
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, websiteId)
                        statement.setString(2, deviceId.take(MAX_DISTINCT_ID))
                        removed += statement.executeUpdate()
                    }
                }
                connection.commit()
                return removed
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    override fun purgeOlderThan(days: Int): Int {
        val db = database ?: return 0
        db.connection.use { connection ->
            connection.autoCommit = false
            try {
                val old = "(select event_id from website_event where website_id = ?::uuid " +
                    "and created_at < now() - make_interval(days => ?))"
                val empty = "(select s.session_id from session s where s.website_id = ?::uuid and not exists " +
                    "(select 1 from website_event e where e.session_id = s.session_id))"
                var removed = 0
                for ((sql, withDays) in listOf(
                    "delete from event_data where website_event_id in $old" to true,
                    "delete from website_event where event_id in $old" to true,
                    "delete from session_data where session_id in $empty" to false,
                    "delete from session_link where session_id in $empty" to false,
                    "delete from session where session_id in $empty" to false,
                )) {
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, websiteId)
                        if (withDays) statement.setInt(2, days.coerceAtLeast(1))
                        removed += statement.executeUpdate()
                    }
                }
                connection.commit()
                return removed
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    override fun close() {
        (database as? AutoCloseable)?.close()
    }

    companion object {
        private const val HOSTNAME = "app.glossostudio.com"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) GlossoApp"
        private const val MAX_DISTINCT_ID = 50

        /** An app session ends after 30 min away; keep its visit a while longer. */
        private val VISIT_MEMORY = Duration.ofHours(3)

        /**
         * From GLOSSO_UMAMI_URL and GLOSSO_UMAMI_APP_WEBSITE_ID; erasing also
         * needs GLOSSO_UMAMI_DB_URL (+ _USER, _PASSWORD). Null when not set, so
         * the server runs exactly as before.
         */
        fun fromEnv(log: (String) -> Unit = {}): UmamiMirror? {
            val url = System.getenv("GLOSSO_UMAMI_URL")?.trimEnd('/')?.takeIf { it.isNotBlank() }
            val website = System.getenv("GLOSSO_UMAMI_APP_WEBSITE_ID")?.takeIf { it.isNotBlank() }
            if (url == null || website == null) {
                log("Umami mirror disabled: set GLOSSO_UMAMI_URL and GLOSSO_UMAMI_APP_WEBSITE_ID")
                return null
            }
            val dbUrl = System.getenv("GLOSSO_UMAMI_DB_URL")?.takeIf { it.isNotBlank() }
            if (dbUrl == null) {
                // Without erasing, "delete my usage data" would leave a copy behind.
                log("Umami mirror disabled: GLOSSO_UMAMI_DB_URL is needed to honour deletions")
                return null
            }
            val database = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = dbUrl
                    username = System.getenv("GLOSSO_UMAMI_DB_USER") ?: "umami"
                    password = System.getenv("GLOSSO_UMAMI_DB_PASSWORD") ?: ""
                    maximumPoolSize = 2
                    minimumIdle = 0
                    connectionTimeout = 5_000
                    poolName = "glosso-umami"
                    initializationFailTimeout = -1
                },
            )
            log("Umami mirror ready: $url")
            return UmamiMirror(url, website, database)
        }
    }
}
