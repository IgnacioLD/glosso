package com.glosso.server.tts

import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

/**
 * PostgreSQL metadata store. Follows the repo's existing "no ORM, no migration
 * framework" approach (`AnalyticsStore`): the schema is applied from the
 * versioned resource `db/migration/V2__tts_assets.sql`, which is idempotent.
 * The phase-1 `SchemaMigrator` already applies that same file (it discovers it
 * by name), so [ensureSchema] only matters when this store is used standalone.
 *
 * Idempotency is enforced by the unique `cache_key`: `insertIfAbsent` uses
 * `on conflict do nothing`, so re-running a job never duplicates an asset.
 */
class JdbcTtsAssetStore(private val dataSource: DataSource) : TtsAssetStore {

    fun ensureSchema() {
        val sql = JdbcTtsAssetStore::class.java
            .getResourceAsStream("/db/migration/V2__tts_assets.sql")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Missing migration resource /db/migration/V2__tts_assets.sql")
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    override fun findByCacheKey(cacheKey: String): TtsAssetRecord? {
        dataSource.connection.use { connection ->
            connection.prepareStatement("select * from glosso.tts_assets where cache_key = ?").use { statement ->
                statement.setString(1, cacheKey)
                statement.executeQuery().use { rs ->
                    return if (rs.next()) readAsset(rs) else null
                }
            }
        }
    }

    override fun insertIfAbsent(record: TtsAssetRecord): Boolean {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into glosso.tts_assets
                    (id, cache_key, content_id, text, language, provider, model, voice,
                     speed, speed_factor, format, object_key, duration_ms, size_bytes,
                     checksum, estimated_cost, characters, credits, job_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (cache_key) do nothing
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, record.id)
                statement.setString(2, record.cacheKey)
                statement.setString(3, record.contentId)
                statement.setString(4, record.text)
                statement.setString(5, record.language)
                statement.setString(6, record.provider)
                statement.setString(7, record.model)
                statement.setString(8, record.voice)
                statement.setString(9, record.speed)
                statement.setDouble(10, record.speedFactor)
                statement.setString(11, record.format)
                statement.setString(12, record.objectKey)
                if (record.durationMs == null) statement.setNull(13, Types.BIGINT)
                else statement.setLong(13, record.durationMs)
                statement.setLong(14, record.sizeBytes)
                statement.setString(15, record.checksum)
                statement.setBigDecimal(16, record.estimatedCostUsd)
                statement.setInt(17, record.characters)
                if (record.credits == null) statement.setNull(18, Types.DOUBLE)
                else statement.setDouble(18, record.credits)
                statement.setString(19, record.jobId)
                statement.setObject(20, java.sql.Timestamp.from(record.createdAt))
                return statement.executeUpdate() == 1
            }
        }
    }

    override fun recordCost(record: TtsCostRecord) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into glosso.tts_costs
                    (job_id, content_id, provider, model, voice, characters, credits,
                     estimated_cost, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, record.jobId)
                statement.setString(2, record.contentId)
                statement.setString(3, record.provider)
                statement.setString(4, record.model)
                statement.setString(5, record.voice)
                statement.setInt(6, record.characters)
                if (record.credits == null) statement.setNull(7, Types.DOUBLE)
                else statement.setDouble(7, record.credits)
                statement.setBigDecimal(8, record.estimatedCostUsd)
                statement.setObject(9, java.sql.Timestamp.from(record.createdAt))
                statement.executeUpdate()
            }
        }
    }

    override fun totalEstimatedCostUsd(): BigDecimal {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select coalesce(sum(estimated_cost), 0) from glosso.tts_costs").use { rs ->
                    rs.next()
                    return rs.getBigDecimal(1) ?: BigDecimal.ZERO
                }
            }
        }
    }

    override fun count(): Int {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from glosso.tts_assets").use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    private fun readAsset(rs: ResultSet): TtsAssetRecord {
        val duration = rs.getLong("duration_ms").let { if (rs.wasNull()) null else it }
        val credits = rs.getDouble("credits").let { if (rs.wasNull()) null else it }
        return TtsAssetRecord(
            cacheKey = rs.getString("cache_key"),
            contentId = rs.getString("content_id"),
            text = rs.getString("text"),
            language = rs.getString("language"),
            provider = rs.getString("provider"),
            model = rs.getString("model") ?: "",
            voice = rs.getString("voice") ?: "",
            speed = rs.getString("speed"),
            speedFactor = rs.getDouble("speed_factor"),
            format = rs.getString("format"),
            objectKey = rs.getString("object_key"),
            durationMs = duration,
            sizeBytes = rs.getLong("size_bytes"),
            checksum = rs.getString("checksum"),
            estimatedCostUsd = rs.getBigDecimal("estimated_cost") ?: BigDecimal.ZERO,
            characters = rs.getInt("characters"),
            credits = credits,
            jobId = rs.getString("job_id"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            id = rs.getString("id"),
        )
    }
}
