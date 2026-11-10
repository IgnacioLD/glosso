package com.glosso.server.generation

import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource

/**
 * PostgreSQL implementation of the content pool and job ledger. Raw JDBC like the
 * rest of the control plane; no ORM. The schema is `glosso.content`,
 * `glosso.content_variants` and `glosso.generation_jobs` (migration V3), applied
 * by the shared [SchemaMigrator] at startup.
 */
class JdbcGenerationStore(private val dataSource: DataSource) : GenerationStore {

    override fun poolCounts(key: NeedClassKey): ContentCounts {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select count(*) as existing,
                       count(*) filter (where status = 'ACTIVE') as effective
                from glosso.content
                where language = ?
                  and coalesce(cefr, '') = coalesce(?, '')
                  and coalesce(skill, '') = coalesce(?, '')
                  and coalesce(target_phoneme, '') = coalesce(?, '')
                  and coalesce(error_pattern, '') = coalesce(?, '')
                  and coalesce(replacement_phoneme, '') = coalesce(?, '')
                  and coalesce(position, '') = coalesce(?, '')
                """.trimIndent(),
            ).use { statement ->
                var i = 1
                statement.setString(i++, key.language)
                statement.setString(i++, key.cefr)
                statement.setString(i++, key.skill)
                statement.setString(i++, key.phoneme)
                statement.setString(i++, key.errorPattern)
                statement.setString(i++, key.replacementPhoneme)
                statement.setString(i++, key.position)
                statement.executeQuery().use { rs ->
                    rs.next()
                    return ContentCounts(existing = rs.getInt("existing"), effective = rs.getInt("effective"))
                }
            }
        }
    }

    override fun insertContent(piece: ContentPiece) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into glosso.content
                    (id, kind, language, cefr, skill, objective, exercise_type, target_phoneme,
                     error_pattern, replacement_phoneme, position, difficulty, status, quality_score,
                     created_by, provider, need_class_id, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """.trimIndent(),
            ).use { statement ->
                var i = 1
                statement.setObject(i++, UUID.fromString(piece.id))
                statement.setString(i++, piece.kind)
                statement.setString(i++, piece.language)
                statement.setString(i++, piece.cefr)
                statement.setString(i++, piece.skill)
                statement.setString(i++, piece.objective)
                statement.setString(i++, piece.exerciseType)
                statement.setString(i++, piece.targetPhoneme)
                statement.setString(i++, piece.errorPattern)
                statement.setString(i++, piece.replacementPhoneme)
                statement.setString(i++, piece.position)
                setDoubleOrNull(statement, i++, piece.difficulty)
                statement.setString(i++, piece.status.name)
                setDoubleOrNull(statement, i++, piece.qualityScore)
                statement.setString(i++, piece.createdBy)
                statement.setString(i++, piece.provider)
                if (piece.needClassId == null) statement.setNull(i++, Types.BIGINT)
                else statement.setLong(i++, piece.needClassId)
                statement.setObject(i++, Timestamp.from(piece.createdAt))
                statement.executeUpdate()
            }
        }
    }

    override fun content(id: String): ContentPiece? {
        dataSource.connection.use { connection ->
            connection.prepareStatement("select * from glosso.content where id = ?").use { statement ->
                statement.setObject(1, UUID.fromString(id))
                statement.executeQuery().use { rs -> return if (rs.next()) readContent(rs) else null }
            }
        }
    }

    override fun variantsForContent(contentId: String): List<ContentVariant> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select * from glosso.content_variants where content_id = ? order by created_at",
            ).use { statement ->
                statement.setObject(1, UUID.fromString(contentId))
                statement.executeQuery().use { rs ->
                    val variants = ArrayList<ContentVariant>()
                    while (rs.next()) variants += readVariant(rs)
                    return variants
                }
            }
        }
    }

    override fun variant(id: String): ContentVariant? {
        dataSource.connection.use { connection ->
            connection.prepareStatement("select * from glosso.content_variants where id = ?").use { statement ->
                statement.setObject(1, UUID.fromString(id))
                statement.executeQuery().use { rs -> return if (rs.next()) readVariant(rs) else null }
            }
        }
    }

    override fun variantByHash(variantHash: String): ContentVariant? {
        dataSource.connection.use { connection ->
            connection.prepareStatement("select * from glosso.content_variants where variant_hash = ?").use { statement ->
                statement.setString(1, variantHash)
                statement.executeQuery().use { rs -> return if (rs.next()) readVariant(rs) else null }
            }
        }
    }

    override fun insertVariant(variant: ContentVariant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into glosso.content_variants
                    (id, content_id, variant_hash, spec, text, ipa, answer, audio_key, slow_audio_key,
                     image_key, voice, provider, model, status, quality_score, created_at, updated_at)
                values (?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (variant_hash) do nothing
                """.trimIndent(),
            ).use { statement ->
                var i = 1
                statement.setObject(i++, UUID.fromString(variant.id))
                statement.setObject(i++, UUID.fromString(variant.contentId))
                statement.setString(i++, variant.variantHash)
                statement.setString(i++, variant.specJson)
                statement.setString(i++, variant.text)
                statement.setString(i++, variant.ipa)
                statement.setString(i++, variant.answerJson)
                statement.setString(i++, variant.audioKey)
                statement.setString(i++, variant.slowAudioKey)
                statement.setString(i++, variant.imageKey)
                statement.setString(i++, variant.voice)
                statement.setString(i++, variant.provider)
                statement.setString(i++, variant.model)
                statement.setString(i++, variant.status.name)
                setDoubleOrNull(statement, i++, variant.qualityScore)
                statement.setObject(i++, Timestamp.from(variant.createdAt))
                statement.executeUpdate()
            }
        }
    }

    override fun recordVariantAudio(
        variantId: String,
        audioKey: String,
        slowAudioKey: String,
        provider: String,
        model: String,
        voice: String,
    ): Boolean {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                update glosso.content_variants
                   set audio_key = ?, slow_audio_key = ?, provider = ?, model = ?, voice = ?,
                       status = 'ACTIVE', updated_at = now()
                 where id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, audioKey)
                statement.setString(2, slowAudioKey)
                statement.setString(3, provider)
                statement.setString(4, model)
                statement.setString(5, voice)
                statement.setObject(6, UUID.fromString(variantId))
                return statement.executeUpdate() > 0
            }
        }
    }

    override fun setContentStatus(contentId: String, status: ContentStatus) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "update glosso.content set status = ?, updated_at = now() where id = ?",
            ).use { statement ->
                statement.setString(1, status.name)
                statement.setObject(2, UUID.fromString(contentId))
                statement.executeUpdate()
            }
        }
    }

    override fun contentPool(language: String?, status: ContentStatus?, limit: Int): List<ContentPiece> {
        dataSource.connection.use { connection ->
            val sql = buildString {
                append("select * from glosso.content where 1 = 1")
                if (language != null) append(" and language = ?")
                if (status != null) append(" and status = ?")
                append(" order by created_at desc limit ?")
            }
            connection.prepareStatement(sql).use { statement ->
                var i = 1
                if (language != null) statement.setString(i++, language)
                if (status != null) statement.setString(i++, status.name)
                statement.setInt(i, limit.coerceIn(1, 1000))
                statement.executeQuery().use { rs ->
                    val pieces = ArrayList<ContentPiece>()
                    while (rs.next()) pieces += readContent(rs)
                    return pieces
                }
            }
        }
    }

    override fun insertJobIfAbsent(job: GenerationJob): Boolean {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into glosso.generation_jobs
                    (id, job_hash, need_class_id, content_id, variant_id, task, spec, target_count,
                     status, attempts, max_attempts, provider, model, max_cost_per_job, requested_at,
                     started_at, finished_at, error)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (job_hash) do nothing
                """.trimIndent(),
            ).use { statement ->
                var i = 1
                statement.setObject(i++, UUID.fromString(job.id))
                statement.setString(i++, job.jobHash)
                if (job.needClassId == null) statement.setNull(i++, Types.BIGINT)
                else statement.setLong(i++, job.needClassId)
                setUuidOrNull(statement, i++, job.contentId)
                setUuidOrNull(statement, i++, job.variantId)
                statement.setString(i++, job.task)
                statement.setString(i++, job.specJson)
                statement.setInt(i++, job.targetCount)
                statement.setString(i++, job.status.name)
                statement.setInt(i++, job.attempts)
                statement.setInt(i++, job.maxAttempts)
                statement.setString(i++, job.provider)
                statement.setString(i++, job.model)
                if (job.maxCostPerJob == null) statement.setNull(i++, Types.NUMERIC)
                else statement.setBigDecimal(i++, job.maxCostPerJob)
                statement.setObject(i++, Timestamp.from(job.requestedAt))
                statement.setObject(i++, job.startedAt?.let { Timestamp.from(it) })
                statement.setObject(i++, job.finishedAt?.let { Timestamp.from(it) })
                statement.setString(i++, job.error)
                return statement.executeUpdate() == 1
            }
        }
    }

    override fun job(id: String): GenerationJob? {
        dataSource.connection.use { connection ->
            connection.prepareStatement("select * from glosso.generation_jobs where id = ?").use { statement ->
                statement.setObject(1, UUID.fromString(id))
                statement.executeQuery().use { rs -> return if (rs.next()) readJob(rs) else null }
            }
        }
    }

    override fun jobByHash(jobHash: String): GenerationJob? {
        dataSource.connection.use { connection ->
            connection.prepareStatement("select * from glosso.generation_jobs where job_hash = ?").use { statement ->
                statement.setString(1, jobHash)
                statement.executeQuery().use { rs -> return if (rs.next()) readJob(rs) else null }
            }
        }
    }

    override fun updateJob(job: GenerationJob) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                update glosso.generation_jobs
                   set status = ?, attempts = ?, provider = ?, model = ?, started_at = ?,
                       finished_at = ?, error = ?
                 where id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, job.status.name)
                statement.setInt(2, job.attempts)
                statement.setString(3, job.provider)
                statement.setString(4, job.model)
                statement.setObject(5, job.startedAt?.let { Timestamp.from(it) })
                statement.setObject(6, job.finishedAt?.let { Timestamp.from(it) })
                statement.setString(7, job.error)
                statement.setObject(8, UUID.fromString(job.id))
                statement.executeUpdate()
            }
        }
    }

    override fun jobs(status: JobStatus?, limit: Int): List<GenerationJob> {
        dataSource.connection.use { connection ->
            val sql = buildString {
                append("select * from glosso.generation_jobs")
                if (status != null) append(" where status = ?")
                append(" order by requested_at desc limit ?")
            }
            connection.prepareStatement(sql).use { statement ->
                var i = 1
                if (status != null) statement.setString(i++, status.name)
                statement.setInt(i, limit.coerceIn(1, 1000))
                statement.executeQuery().use { rs ->
                    val jobs = ArrayList<GenerationJob>()
                    while (rs.next()) jobs += readJob(rs)
                    return jobs
                }
            }
        }
    }

    override fun jobCounts(): Map<JobStatus, Int> {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select status, count(*) from glosso.generation_jobs group by status",
                ).use { rs ->
                    val counts = LinkedHashMap<JobStatus, Int>()
                    while (rs.next()) {
                        val status = runCatching { JobStatus.valueOf(rs.getString(1)) }.getOrNull()
                        if (status != null) counts[status] = rs.getInt(2)
                    }
                    return counts
                }
            }
        }
    }

    private fun readContent(rs: ResultSet): ContentPiece = ContentPiece(
        id = rs.getObject("id").toString(),
        kind = rs.getString("kind"),
        language = rs.getString("language"),
        cefr = rs.getString("cefr"),
        skill = rs.getString("skill"),
        objective = rs.getString("objective"),
        exerciseType = rs.getString("exercise_type"),
        targetPhoneme = rs.getString("target_phoneme"),
        errorPattern = rs.getString("error_pattern"),
        replacementPhoneme = rs.getString("replacement_phoneme"),
        position = rs.getString("position"),
        difficulty = rs.getDouble("difficulty").let { if (rs.wasNull()) null else it },
        status = ContentStatus.valueOf(rs.getString("status")),
        qualityScore = rs.getDouble("quality_score").let { if (rs.wasNull()) null else it },
        createdBy = rs.getString("created_by"),
        provider = rs.getString("provider"),
        needClassId = rs.getLong("need_class_id").let { if (rs.wasNull()) null else it },
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )

    private fun readVariant(rs: ResultSet): ContentVariant = ContentVariant(
        id = rs.getObject("id").toString(),
        contentId = rs.getObject("content_id").toString(),
        variantHash = rs.getString("variant_hash"),
        specJson = rs.getString("spec") ?: "{}",
        text = rs.getString("text"),
        ipa = rs.getString("ipa"),
        answerJson = rs.getString("answer"),
        audioKey = rs.getString("audio_key"),
        slowAudioKey = rs.getString("slow_audio_key"),
        imageKey = rs.getString("image_key"),
        voice = rs.getString("voice"),
        provider = rs.getString("provider"),
        model = rs.getString("model"),
        status = ContentStatus.valueOf(rs.getString("status")),
        qualityScore = rs.getDouble("quality_score").let { if (rs.wasNull()) null else it },
        createdAt = rs.getTimestamp("created_at").toInstant(),
    )

    private fun readJob(rs: ResultSet): GenerationJob = GenerationJob(
        id = rs.getObject("id").toString(),
        jobHash = rs.getString("job_hash"),
        needClassId = rs.getLong("need_class_id").let { if (rs.wasNull()) null else it },
        contentId = rs.getObject("content_id")?.toString(),
        variantId = rs.getObject("variant_id")?.toString(),
        task = rs.getString("task"),
        specJson = rs.getString("spec") ?: "{}",
        targetCount = rs.getInt("target_count"),
        status = JobStatus.valueOf(rs.getString("status")),
        attempts = rs.getInt("attempts"),
        maxAttempts = rs.getInt("max_attempts"),
        provider = rs.getString("provider"),
        model = rs.getString("model"),
        maxCostPerJob = rs.getBigDecimal("max_cost_per_job"),
        requestedAt = rs.getTimestamp("requested_at").toInstant(),
        startedAt = rs.getTimestamp("started_at")?.toInstant(),
        finishedAt = rs.getTimestamp("finished_at")?.toInstant(),
        error = rs.getString("error"),
    )

    private fun setUuidOrNull(statement: java.sql.PreparedStatement, index: Int, value: String?) {
        if (value == null) statement.setNull(index, Types.OTHER) else statement.setObject(index, UUID.fromString(value))
    }

    private fun setDoubleOrNull(statement: java.sql.PreparedStatement, index: Int, value: Double?) {
        if (value == null) statement.setNull(index, Types.DOUBLE) else statement.setDouble(index, value)
    }
}
