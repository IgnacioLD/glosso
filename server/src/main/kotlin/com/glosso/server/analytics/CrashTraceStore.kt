package com.glosso.server.analytics

import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.Statement
import java.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StackFrame(
    @JsonNames("className", "class_name") val className: String? = null,
    @JsonNames("methodName", "method_name") val methodName: String? = null,
    @JsonNames("fileName", "file_name") val fileName: String? = null,
    val line: Int? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CrashCause(
    @JsonNames("type", "exception") val type: String,
    @JsonNames("frames", "stack") val frames: List<StackFrame> = emptyList(),
)

@Serializable
data class CrashTraceRecord(
    @SerialName("trace_id") val traceId: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    val platform: String,
    val fatal: Boolean = false,
    val kind: String,
    val exception: String,
    val where: String? = null,
    val causes: List<CrashCause> = emptyList(),
    @SerialName("received_at") val receivedAt: String,
)

data class CrashGroup(
    val exception: String,
    val where: String?,
    val count: Long,
    val fatalCount: Long,
    val versions: List<String>,
    val lastSeen: Instant,
    val latestTraceId: String,
)

data class StoredCrashTrace(
    val traceId: String,
    val deviceId: String? = null,
    val appVersion: String? = null,
    val platform: String,
    val fatal: Boolean = false,
    val kind: String,
    val exception: String,
    val where: String? = null,
    val causes: List<CrashCause> = emptyList(),
    val receivedAt: Instant? = null,
)

object CrashTraceSanitizer {
    /** Letters, digits, and . _ $ < > - bounded to 256 characters. */
    val CODE_IDENTIFIER = Regex("^[A-Za-z0-9._$<>-]{1,256}$")
    val SHORT_ID = Regex("^[A-Za-z0-9._:-]{1,64}$")
    val PLATFORMS = setOf("android", "ios")

    const val MAX_CAUSES = 4
    const val MAX_FRAMES_PER_CAUSE = 64
    const val MAX_BODY_BYTES = 64_000

    fun sanitizeCodeIdentifier(value: String?, maxLength: Int = 256): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        if (trimmed.length > maxLength) return null
        return if (CODE_IDENTIFIER.matches(trimmed)) trimmed else null
    }

    fun sanitizeShortId(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        return if (SHORT_ID.matches(trimmed)) trimmed else null
    }

    fun sanitizeLine(line: Int?): Int? =
        line?.takeIf { it in -2..1_000_000 }
}

class CrashTraceStore(private val dataSource: HikariDataSource) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun ensureSchema(statement: Statement) {
        statement.execute(
            """
            create table if not exists glosso.crash_traces (
                trace_id text primary key,
                device_id text,
                app_version text,
                platform text not null,
                fatal boolean not null default false,
                kind text not null,
                exception text not null,
                "where" text,
                stack jsonb not null default '[]'::jsonb,
                received_at timestamptz not null default now()
            )
            """.trimIndent(),
        )
        statement.execute(
            "create index if not exists crash_traces_received_at on glosso.crash_traces (received_at desc)",
        )
        statement.execute(
            "create index if not exists crash_traces_device_id on glosso.crash_traces (device_id)",
        )
        statement.execute(
            """create index if not exists crash_traces_exception_where on glosso.crash_traces (exception, "where")""",
        )
    }

    fun insert(trace: StoredCrashTrace): Boolean {
        val stackJson = json.encodeToString(trace.causes)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into glosso.crash_traces
                    (trace_id, device_id, app_version, platform, fatal, kind, exception, "where", stack, received_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, coalesce(?::timestamptz, now()))
                on conflict (trace_id) do nothing
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, trace.traceId)
                statement.setString(2, trace.deviceId)
                statement.setString(3, trace.appVersion)
                statement.setString(4, trace.platform)
                statement.setBoolean(5, trace.fatal)
                statement.setString(6, trace.kind)
                statement.setString(7, trace.exception)
                statement.setString(8, trace.where)
                statement.setString(9, stackJson)
                statement.setTimestamp(10, trace.receivedAt?.let(java.sql.Timestamp::from))
                return statement.executeUpdate() > 0
            }
        }
    }

    fun deleteDevice(deviceId: String, existingConnection: Connection? = null): Int {
        val runner = { conn: Connection ->
            conn.prepareStatement("delete from glosso.crash_traces where device_id = ?").use { statement ->
                statement.setString(1, deviceId)
                statement.executeUpdate()
            }
        }
        return if (existingConnection != null) runner(existingConnection) else dataSource.connection.use(runner)
    }

    fun purgeOlderThan(days: Int, existingConnection: Connection? = null): Int {
        val window = days.coerceAtLeast(1)
        val runner = { conn: Connection ->
            conn.prepareStatement(
                "delete from glosso.crash_traces where received_at < now() - make_interval(days => ?)",
            ).use { statement ->
                statement.setInt(1, window)
                statement.executeUpdate()
            }
        }
        return if (existingConnection != null) runner(existingConnection) else dataSource.connection.use(runner)
    }

    fun get(traceId: String): CrashTraceRecord? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select trace_id, device_id, app_version, platform, fatal, kind, exception, "where", stack::text, received_at
                from glosso.crash_traces
                where trace_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, traceId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val stackRaw = rs.getString(9) ?: "[]"
                    val causes = runCatching {
                        json.decodeFromString<List<CrashCause>>(stackRaw)
                    }.getOrDefault(emptyList())
                    val receivedInstant = rs.getTimestamp(10)?.toInstant() ?: Instant.now()
                    return CrashTraceRecord(
                        traceId = rs.getString(1),
                        deviceId = rs.getString(2),
                        appVersion = rs.getString(3),
                        platform = rs.getString(4),
                        fatal = rs.getBoolean(5),
                        kind = rs.getString(6),
                        exception = rs.getString(7),
                        where = rs.getString(8),
                        causes = causes,
                        receivedAt = receivedInstant.toString(),
                    )
                }
            }
        }
    }

    fun list(days: Int, limit: Int = 1000): List<CrashTraceRecord> {
        val window = days.coerceIn(1, 365)
        val maxRows = limit.coerceIn(1, 5000)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select trace_id, device_id, app_version, platform, fatal, kind, exception, "where", stack::text, received_at
                from glosso.crash_traces
                where received_at >= now() - make_interval(days => ?)
                order by received_at desc
                limit ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, window)
                statement.setInt(2, maxRows)
                statement.executeQuery().use { rs ->
                    val results = mutableListOf<CrashTraceRecord>()
                    while (rs.next()) {
                        val stackRaw = rs.getString(9) ?: "[]"
                        val causes = runCatching {
                            json.decodeFromString<List<CrashCause>>(stackRaw)
                        }.getOrDefault(emptyList())
                        val receivedInstant = rs.getTimestamp(10)?.toInstant() ?: Instant.now()
                        results.add(
                            CrashTraceRecord(
                                traceId = rs.getString(1),
                                deviceId = rs.getString(2),
                                appVersion = rs.getString(3),
                                platform = rs.getString(4),
                                fatal = rs.getBoolean(5),
                                kind = rs.getString(6),
                                exception = rs.getString(7),
                                where = rs.getString(8),
                                causes = causes,
                                receivedAt = receivedInstant.toString(),
                            ),
                        )
                    }
                    return results
                }
            }
        }
    }

    fun groups(days: Int): List<CrashGroup> {
        val window = days.coerceIn(1, 365)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select exception,
                       "where",
                       count(*) as total_count,
                       count(*) filter (where fatal) as fatal_count,
                       array_to_string(array_agg(distinct app_version) filter (where app_version is not null), ',') as versions_str,
                       max(received_at) as last_seen,
                       (array_agg(trace_id order by received_at desc))[1] as latest_trace_id
                from glosso.crash_traces
                where received_at >= now() - make_interval(days => ?)
                group by exception, "where"
                order by total_count desc, last_seen desc
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, window)
                statement.executeQuery().use { rs ->
                    val list = mutableListOf<CrashGroup>()
                    while (rs.next()) {
                        val versionsStr = rs.getString(5)
                        val versions = versionsStr?.split(",")?.filter { it.isNotBlank() }?.sortedDescending() ?: emptyList()
                        val lastSeen = rs.getTimestamp(6)?.toInstant() ?: Instant.now()
                        list.add(
                            CrashGroup(
                                exception = rs.getString(1),
                                where = rs.getString(2),
                                count = rs.getLong(3),
                                fatalCount = rs.getLong(4),
                                versions = versions,
                                lastSeen = lastSeen,
                                latestTraceId = rs.getString(7) ?: "",
                            ),
                        )
                    }
                    return list
                }
            }
        }
    }
}
