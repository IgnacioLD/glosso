package com.glosso.server.analytics

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Types
import kotlinx.serialization.Serializable

/** One analytics event, already normalized for storage. */
data class AnalyticsEvent(
    val name: String,
    val deviceId: String? = null,
    val sessionId: String? = null,
    val appVersion: String? = null,
    val platform: String? = null,
    val language: String? = null,
    /** Event properties as a JSON object string. */
    val propertiesJson: String = "{}",
    /** When it happened on the device; null means when it is stored. */
    val occurredAt: java.time.Instant? = null,
)

@Serializable
data class EventCount(val name: String, val count: Long)

@Serializable
data class DailyCount(val day: String, val events: Long, val devices: Long)

@Serializable
data class AnalyticsSummary(
    val days: Int,
    val total: Long,
    val devices: Long,
    val byName: List<EventCount>,
    val daily: List<DailyCount>,
)

/** One 10-point score band, e.g. 70..79. */
@Serializable
data class ScoreBucket(val from: Int, val to: Int, val count: Long)

/**
 * What the product team actually wants to see: how well learners score, how many
 * master an attempt, whether they use audio, and which sound errors come up most.
 * Derived from the server-side `score_attempt` events, so no app change is needed.
 */
@Serializable
data class ProductSummary(
    val days: Int,
    val attempts: Long,
    val devices: Long,
    /** Percent of attempts that reached mastery. */
    val masteryRate: Int,
    /** Percent of attempts that used recorded audio (vs. IPA only / tests). */
    val audioShare: Int,
    val avgScore: Int,
    val scoreBuckets: List<ScoreBucket>,
    val levels: List<EventCount>,
    val topErrors: List<EventCount>,
)

/** How many devices of the window's cohort reached one step. */
@Serializable
data class FunnelStep(val name: String, val devices: Long)

/**
 * Day-N retention: of the devices first seen [day] days or more ago (so day N
 * is over), how many opened the app again exactly on day N.
 */
@Serializable
data class RetentionPoint(val day: Int, val eligible: Long, val retained: Long)

/**
 * Activation and retention from the app's own events (`app_open` and the
 * funnel steps). Devices on app versions that send no events are not in it.
 */
@Serializable
data class EngagementSummary(
    val days: Int,
    val funnel: List<FunnelStep>,
    val retention: List<RetentionPoint>,
    val time: UsageTime = UsageTime(),
)

/**
 * Time in the app, from `session_end` (one per stretch in the foreground; a
 * session is every stretch with the same session id). Exact, unlike a page
 * analytics "visit duration".
 */
@Serializable
data class UsageTime(
    val sessions: Long = 0,
    val devices: Long = 0,
    val medianSessionSeconds: Int = 0,
    val averageSessionSeconds: Int = 0,
    /** Minutes per device on the days that device used the app. */
    val minutesPerActiveDay: Double = 0.0,
    val sessionsPerDevice: Double = 0.0,
)

/**
 * First-party product analytics: app events (and server-side score events) land
 * in Postgres, owned by us, with no third-party SDK. Fire-and-forget from the
 * client's point of view; the server never blocks scoring on it.
 *
 * Disabled cleanly when no database is configured, so the API still runs in
 * local development and tests.
 */
class AnalyticsStore(private val dataSource: HikariDataSource) : AutoCloseable {

    val crashTraces: CrashTraceStore = CrashTraceStore(dataSource)

    fun ensureSchema() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("create schema if not exists glosso")
                statement.execute(
                    """
                    create table if not exists glosso.analytics_event (
                        id bigserial primary key,
                        name text not null,
                        device_id text,
                        session_id text,
                        app_version text,
                        platform text,
                        language text,
                        properties jsonb not null default '{}'::jsonb,
                        occurred_at timestamptz not null default now(),
                        received_at timestamptz not null default now()
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "create index if not exists analytics_event_name_time " +
                        "on glosso.analytics_event (name, occurred_at desc)",
                )
                statement.execute(
                    "create index if not exists analytics_event_device_time " +
                        "on glosso.analytics_event (device_id, occurred_at desc)",
                )
                crashTraces.ensureSchema(statement)
            }
        }
    }

    /** Inserts a batch of events and returns how many were written. */
    fun insert(events: List<AnalyticsEvent>): Int {
        if (events.isEmpty()) return 0
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into glosso.analytics_event
                    (name, device_id, session_id, app_version, platform, language, properties, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, coalesce(?::timestamptz, now()))
                """.trimIndent(),
            ).use { statement ->
                for (event in events) {
                    statement.setString(1, event.name)
                    statement.setString(2, event.deviceId)
                    statement.setString(3, event.sessionId)
                    statement.setString(4, event.appVersion)
                    statement.setString(5, event.platform)
                    statement.setString(6, event.language)
                    if (event.propertiesJson.isBlank()) {
                        statement.setNull(7, Types.OTHER)
                    } else {
                        statement.setString(7, event.propertiesJson)
                    }
                    statement.setTimestamp(8, event.occurredAt?.let(java.sql.Timestamp::from))
                    statement.addBatch()
                }
                return statement.executeBatch().count { it >= 0 || it == java.sql.Statement.SUCCESS_NO_INFO }
            }
        }
    }

    /** Deletes events and crash traces older than [days]; called at startup for retention. */
    fun purgeOlderThan(days: Int): Int {
        val window = days.coerceAtLeast(1)
        dataSource.connection.use { connection ->
            var deleted = 0
            connection.prepareStatement(
                "delete from glosso.analytics_event where received_at < now() - make_interval(days => ?)",
            ).use { statement ->
                statement.setInt(1, window)
                deleted += statement.executeUpdate()
            }
            deleted += crashTraces.purgeOlderThan(window, connection)
            return deleted
        }
    }

    /** Deletes every event and crash trace of one install; returns how many rows went. */
    fun deleteDevice(deviceId: String): Int =
        dataSource.connection.use { connection ->
            var deleted = 0
            connection.prepareStatement("delete from glosso.analytics_event where device_id = ?").use { statement ->
                statement.setString(1, deviceId)
                deleted += statement.executeUpdate()
            }
            deleted += crashTraces.deleteDevice(deviceId, connection)
            deleted
        }

    /**
     * The funnel of the devices that opened the app in the window, and day 1, 7
     * and 30 retention of the devices first seen in it.
     */
    fun engagement(days: Int): EngagementSummary {
        val window = days.coerceIn(1, 365)
        dataSource.connection.use { connection ->
            val reached = mutableMapOf<String, Long>()
            connection.prepareStatement(
                """
                with cohort as (
                    select distinct device_id from glosso.analytics_event
                    where name = 'app_open' and device_id is not null
                      and occurred_at >= now() - make_interval(days => ?)
                )
                select e.name, count(distinct e.device_id)
                from glosso.analytics_event e join cohort using (device_id)
                where e.name = any(?) and e.occurred_at >= now() - make_interval(days => ?)
                group by e.name
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, window)
                statement.setArray(2, connection.createArrayOf("text", FUNNEL.toTypedArray()))
                statement.setInt(3, window)
                statement.executeQuery().use { rs ->
                    while (rs.next()) reached[rs.getString(1)] = rs.getLong(2)
                }
            }

            val retention = mutableListOf<RetentionPoint>()
            connection.prepareStatement(
                """
                with opens as (
                    select distinct device_id, (occurred_at at time zone 'UTC')::date as day
                    from glosso.analytics_event
                    where name = 'app_open' and device_id is not null
                ),
                cohort as (
                    select device_id, min(day) as first_day from opens group by device_id
                    having min(day) >= (now() at time zone 'UTC')::date - ?
                )
                select n.day,
                       count(*) filter (where c.first_day + n.day < (now() at time zone 'UTC')::date),
                       count(*) filter (where c.first_day + n.day < (now() at time zone 'UTC')::date and exists (
                           select 1 from opens o where o.device_id = c.device_id and o.day = c.first_day + n.day
                       ))
                from cohort c cross join (values (1), (7), (30)) as n(day)
                group by n.day order by n.day
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, window)
                statement.executeQuery().use { rs ->
                    while (rs.next()) retention.add(RetentionPoint(rs.getInt(1), rs.getLong(2), rs.getLong(3)))
                }
            }

            var time = UsageTime()
            connection.prepareStatement(
                """
                with stretches as (
                    select device_id, session_id, (occurred_at at time zone 'UTC')::date as day,
                           least(greatest((properties->>'duration_s')::int, 0), 86400) as seconds
                    from glosso.analytics_event
                    where name = 'session_end' and device_id is not null and session_id is not null
                      and properties->>'duration_s' ~ '^[0-9]+$'
                      and occurred_at >= now() - make_interval(days => ?)
                ),
                sessions as (select session_id, sum(seconds) as seconds from stretches group by session_id),
                device_days as (select device_id, day, sum(seconds) as seconds from stretches group by device_id, day)
                select (select count(*) from sessions),
                       (select count(distinct device_id) from stretches),
                       (select coalesce(percentile_cont(0.5) within group (order by seconds), 0) from sessions),
                       (select coalesce(avg(seconds), 0) from sessions),
                       (select coalesce(avg(seconds) / 60.0, 0) from device_days)
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, window)
                statement.executeQuery().use { rs ->
                    rs.next()
                    val sessions = rs.getLong(1)
                    val devices = rs.getLong(2)
                    time = UsageTime(
                        sessions = sessions,
                        devices = devices,
                        medianSessionSeconds = rs.getDouble(3).toInt(),
                        averageSessionSeconds = rs.getDouble(4).toInt(),
                        minutesPerActiveDay = Math.round(rs.getDouble(5) * 10) / 10.0,
                        sessionsPerDevice = if (devices == 0L) 0.0 else Math.round(sessions * 10.0 / devices) / 10.0,
                    )
                }
            }

            return EngagementSummary(
                days = window,
                time = time,
                funnel = FUNNEL.map { FunnelStep(it, reached[it] ?: 0L) },
                retention = listOf(1, 7, 30).map { day ->
                    retention.firstOrNull { it.day == day } ?: RetentionPoint(day, 0, 0)
                },
            )
        }
    }

    fun summary(days: Int): AnalyticsSummary {
        val window = days.coerceIn(1, 365)
        dataSource.connection.use { connection ->
            val total: Long
            val devices: Long
            connection.prepareStatement(
                """
                select count(*), count(distinct device_id)
                from glosso.analytics_event
                where occurred_at >= now() - make_interval(days => ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, window)
                statement.executeQuery().use { rs ->
                    rs.next()
                    total = rs.getLong(1)
                    devices = rs.getLong(2)
                }
            }

            val byName = mutableListOf<EventCount>()
            connection.prepareStatement(
                """
                select name, count(*) as c
                from glosso.analytics_event
                where occurred_at >= now() - make_interval(days => ?)
                group by name order by c desc, name
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, window)
                statement.executeQuery().use { rs ->
                    while (rs.next()) byName.add(EventCount(rs.getString(1), rs.getLong(2)))
                }
            }

            val daily = mutableListOf<DailyCount>()
            connection.prepareStatement(
                """
                select to_char(date_trunc('day', occurred_at at time zone 'UTC'), 'YYYY-MM-DD') as day,
                       count(*) as events,
                       count(distinct device_id) as devices
                from glosso.analytics_event
                where occurred_at >= now() - make_interval(days => ?)
                group by day order by day desc
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, window)
                statement.executeQuery().use { rs ->
                    while (rs.next()) daily.add(DailyCount(rs.getString(1), rs.getLong(2), rs.getLong(3)))
                }
            }

            return AnalyticsSummary(window, total, devices, byName, daily)
        }
    }

    /** Product-level rollup over the server-side `score_attempt` events. */
    fun productSummary(days: Int): ProductSummary {
        val window = days.coerceIn(1, 365)
        dataSource.connection.use { connection ->
            var attempts = 0L
            var devices = 0L
            var mastery = 0L
            var audio = 0L
            var scoreSum = 0L
            connection.prepareStatement(
                """
                select count(*),
                       count(distinct device_id),
                       count(*) filter (where properties->>'mastery' = 'true'),
                       count(*) filter (where properties->>'audio' = 'true'),
                       coalesce(sum((properties->>'score')::int), 0)
                from glosso.analytics_event
                where name = 'score_attempt' and occurred_at >= now() - make_interval(days => ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, window)
                statement.executeQuery().use { rs ->
                    rs.next()
                    attempts = rs.getLong(1)
                    devices = rs.getLong(2)
                    mastery = rs.getLong(3)
                    audio = rs.getLong(4)
                    scoreSum = rs.getLong(5)
                }
            }

            val buckets = mutableListOf<ScoreBucket>()
            connection.prepareStatement(
                """
                select least(((properties->>'score')::int) / 10, 10) * 10 as band, count(*)
                from glosso.analytics_event
                where name = 'score_attempt' and properties->>'score' is not null
                  and occurred_at >= now() - make_interval(days => ?)
                group by band order by band
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, window)
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        val from = rs.getInt(1)
                        buckets.add(ScoreBucket(from, minOf(from + 9, 100), rs.getLong(2)))
                    }
                }
            }

            val levels = mutableListOf<EventCount>()
            connection.prepareStatement(
                """
                select properties->>'level' as level, count(*) as c
                from glosso.analytics_event
                where name = 'score_attempt' and properties->>'level' is not null
                  and occurred_at >= now() - make_interval(days => ?)
                group by level order by c desc, level
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, window)
                statement.executeQuery().use { rs ->
                    while (rs.next()) levels.add(EventCount(rs.getString(1), rs.getLong(2)))
                }
            }

            val topErrors = mutableListOf<EventCount>()
            connection.prepareStatement(
                """
                select rule, count(*) as c
                from glosso.analytics_event,
                     lateral unnest(string_to_array(properties->>'errors', ',')) as rule
                where name = 'score_attempt' and coalesce(properties->>'errors', '') <> ''
                  and rule <> '' and occurred_at >= now() - make_interval(days => ?)
                group by rule order by c desc, rule limit 10
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, window)
                statement.executeQuery().use { rs ->
                    while (rs.next()) topErrors.add(EventCount(rs.getString(1), rs.getLong(2)))
                }
            }

            return ProductSummary(
                days = window,
                attempts = attempts,
                devices = devices,
                masteryRate = if (attempts == 0L) 0 else (mastery * 100 / attempts).toInt(),
                audioShare = if (attempts == 0L) 0 else (audio * 100 / attempts).toInt(),
                avgScore = if (attempts == 0L) 0 else (scoreSum / attempts).toInt(),
                scoreBuckets = buckets,
                levels = levels,
                topErrors = topErrors,
            )
        }
    }

    override fun close() = dataSource.close()

    companion object {
        /** The activation funnel, in order. */
        val FUNNEL = listOf(
            "app_open",
            "onboarding_started",
            "onboarding_completed",
            "lesson_started",
            "lesson_completed",
        )

        /**
         * Builds the store from the environment, or null when it is not
         * configured or the database is unreachable. Never throws: analytics is
         * optional and must not stop the API from serving.
         */
        fun fromEnv(log: (String) -> Unit = {}): AnalyticsStore? {
            val url = System.getenv("GLOSSO_DB_URL")?.takeIf { it.isNotBlank() } ?: run {
                log("Analytics disabled: GLOSSO_DB_URL is not set")
                return null
            }
            val user = System.getenv("GLOSSO_DB_USER") ?: "postgres"
            val password = System.getenv("GLOSSO_DB_PASSWORD") ?: ""
            return try {
                val config = HikariConfig().apply {
                    jdbcUrl = url
                    username = user
                    this.password = password
                    maximumPoolSize = 3
                    minimumIdle = 1
                    connectionTimeout = 5_000
                    poolName = "glosso-analytics"
                }
                val store = AnalyticsStore(HikariDataSource(config))
                store.ensureSchema()
                log("Analytics ready")
                store
            } catch (e: Exception) {
                log("Analytics disabled: ${e.message}")
                null
            }
        }
    }
}
