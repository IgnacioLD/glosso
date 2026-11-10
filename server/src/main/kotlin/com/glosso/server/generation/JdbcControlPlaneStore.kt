package com.glosso.server.generation

import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * PostgreSQL implementation of the control plane. Raw JDBC, like the existing
 * analytics store: no ORM, one pool, and every multi-row write in a transaction.
 *
 * Nullable identity fields (phoneme, pattern, position) are stored as null and
 * matched through the coalesce expressions in the migrations' unique indexes.
 */
class JdbcControlPlaneStore(private val dataSource: DataSource) : ControlPlaneStore {

    override fun upsertUser(deviceId: String): UUID {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into glosso.users (device_id) values (?)
                on conflict (device_id) do update set updated_at = now()
                returning id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, deviceId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    return rs.getObject(1, UUID::class.java)
                }
            }
        }
    }

    override fun upsertLearnerProfile(profile: LearnerProfile) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into glosso.learner_profiles
                    (user_id, language, cefr, vocabulary_score, grammar_score, comprehension_score,
                     pronunciation_score, fluency_score, speaking_score, sample_counts, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
                on conflict (user_id, language) do update set
                    cefr = excluded.cefr,
                    vocabulary_score = excluded.vocabulary_score,
                    grammar_score = excluded.grammar_score,
                    comprehension_score = excluded.comprehension_score,
                    pronunciation_score = excluded.pronunciation_score,
                    fluency_score = excluded.fluency_score,
                    speaking_score = excluded.speaking_score,
                    sample_counts = excluded.sample_counts,
                    updated_at = now()
                """.trimIndent(),
            ).use { statement ->
                var i = 1
                statement.setObject(i++, profile.userId)
                statement.setString(i++, profile.language)
                statement.setString(i++, profile.cefr)
                setIntOrNull(statement, i++, profile.vocabularyScore)
                setIntOrNull(statement, i++, profile.grammarScore)
                setIntOrNull(statement, i++, profile.comprehensionScore)
                setIntOrNull(statement, i++, profile.pronunciationScore)
                setIntOrNull(statement, i++, profile.fluencyScore)
                setIntOrNull(statement, i++, profile.speakingScore)
                statement.setString(i++, profile.sampleCountsJson.ifBlank { "{}" })
                statement.executeUpdate()
            }
        }
    }

    override fun learnerProfile(userId: UUID, language: String): LearnerProfile? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select user_id, language, cefr, vocabulary_score, grammar_score, comprehension_score,
                       pronunciation_score, fluency_score, speaking_score, sample_counts
                from glosso.learner_profiles where user_id = ? and language = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, language)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return LearnerProfile(
                        userId = rs.getObject("user_id", UUID::class.java),
                        language = rs.getString("language"),
                        cefr = rs.getString("cefr"),
                        vocabularyScore = rs.getObject("vocabulary_score") as? Int,
                        grammarScore = rs.getObject("grammar_score") as? Int,
                        comprehensionScore = rs.getObject("comprehension_score") as? Int,
                        pronunciationScore = rs.getObject("pronunciation_score") as? Int,
                        fluencyScore = rs.getObject("fluency_score") as? Int,
                        speakingScore = rs.getObject("speaking_score") as? Int,
                        sampleCountsJson = rs.getString("sample_counts") ?: "{}",
                    )
                }
            }
        }
    }

    override fun replacePhonemeProfiles(userId: UUID, language: String, profiles: List<PhonemeProfile>) {
        dataSource.connection.use { connection ->
            connection.inTransaction {
                connection.prepareStatement(
                    "delete from glosso.phoneme_profiles where user_id = ? and language = ?",
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, language)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    insert into glosso.phoneme_profiles
                        (user_id, language, phoneme, score, confidence, error_pattern, replacement_phoneme,
                         position, attempts, successful_attempts, last_seen, trend, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    """.trimIndent(),
                ).use { statement ->
                    for (profile in profiles) {
                        var i = 1
                        statement.setObject(i++, userId)
                        statement.setString(i++, language)
                        statement.setString(i++, profile.phoneme)
                        statement.setInt(i++, profile.score)
                        statement.setFloat(i++, profile.confidence)
                        statement.setString(i++, profile.errorPattern)
                        statement.setString(i++, profile.replacementPhoneme)
                        statement.setString(i++, profile.position)
                        statement.setInt(i++, profile.attempts)
                        statement.setInt(i++, profile.successfulAttempts)
                        statement.setTimestamp(i++, profile.lastSeen?.let { Timestamp.from(it) })
                        statement.setString(i++, profile.trend)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }
    }

    override fun phonemeProfiles(userId: UUID, language: String): List<PhonemeProfile> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select user_id, language, phoneme, score, confidence, error_pattern, replacement_phoneme,
                       position, attempts, successful_attempts, last_seen, trend
                from glosso.phoneme_profiles where user_id = ? and language = ? order by phoneme
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, userId)
                statement.setString(2, language)
                statement.executeQuery().use { rs ->
                    val profiles = ArrayList<PhonemeProfile>()
                    while (rs.next()) {
                        profiles += PhonemeProfile(
                            userId = rs.getObject("user_id", UUID::class.java),
                            language = rs.getString("language"),
                            phoneme = rs.getString("phoneme"),
                            score = rs.getInt("score"),
                            confidence = rs.getFloat("confidence"),
                            errorPattern = rs.getString("error_pattern"),
                            replacementPhoneme = rs.getString("replacement_phoneme"),
                            position = rs.getString("position"),
                            attempts = rs.getInt("attempts"),
                            successfulAttempts = rs.getInt("successful_attempts"),
                            lastSeen = rs.getTimestamp("last_seen")?.toInstant(),
                            trend = rs.getString("trend"),
                        )
                    }
                    return profiles
                }
            }
        }
    }

    override fun replaceOpenNeeds(userId: UUID, language: String, needs: List<UserNeed>) {
        dataSource.connection.use { connection ->
            connection.inTransaction {
                connection.prepareStatement(
                    "delete from glosso.user_needs where user_id = ? and language = ? and status = 'open'",
                ).use { statement ->
                    statement.setObject(1, userId)
                    statement.setString(2, language)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    insert into glosso.user_needs
                        (user_id, language, cefr, skill, phoneme, error_pattern, replacement_phoneme,
                         position, priority, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'open')
                    """.trimIndent(),
                ).use { statement ->
                    for (need in needs) {
                        var i = 1
                        statement.setObject(i++, userId)
                        statement.setString(i++, language)
                        statement.setString(i++, need.cefr)
                        statement.setString(i++, need.skill)
                        statement.setString(i++, need.phoneme)
                        statement.setString(i++, need.errorPattern)
                        statement.setString(i++, need.replacementPhoneme)
                        statement.setString(i++, need.position)
                        statement.setFloat(i++, need.priority)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }
    }

    override fun openNeeds(language: String?): List<UserNeed> {
        dataSource.connection.use { connection ->
            val sql = buildString {
                append(
                    """
                    select user_id, language, cefr, skill, phoneme, error_pattern, replacement_phoneme,
                           position, priority
                    from glosso.user_needs where status = 'open'
                    """.trimIndent(),
                )
                if (language != null) append(" and language = ?")
                append(" order by priority desc")
            }
            connection.prepareStatement(sql).use { statement ->
                if (language != null) statement.setString(1, language)
                statement.executeQuery().use { rs ->
                    val needs = ArrayList<UserNeed>()
                    while (rs.next()) {
                        needs += UserNeed(
                            userId = rs.getObject("user_id", UUID::class.java),
                            language = rs.getString("language"),
                            cefr = rs.getString("cefr"),
                            skill = rs.getString("skill"),
                            phoneme = rs.getString("phoneme"),
                            errorPattern = rs.getString("error_pattern"),
                            replacementPhoneme = rs.getString("replacement_phoneme"),
                            position = rs.getString("position"),
                            priority = rs.getFloat("priority"),
                        )
                    }
                    return needs
                }
            }
        }
    }

    override fun upsertNeedClasses(classes: List<NeedClass>) {
        if (classes.isEmpty()) return
        dataSource.connection.use { connection ->
            connection.inTransaction {
                connection.prepareStatement(
                    """
                    insert into glosso.need_classes
                        (language, cefr, skill, phoneme, error_pattern, replacement_phoneme, position,
                         learner_count, available_existing, available_effective, gap, gap_state, priority,
                         last_aggregated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    on conflict (language, cefr, skill, coalesce(phoneme, ''), coalesce(error_pattern, ''),
                                 coalesce(replacement_phoneme, ''), coalesce(position, ''))
                    do update set
                        learner_count = excluded.learner_count,
                        available_existing = excluded.available_existing,
                        available_effective = excluded.available_effective,
                        gap = excluded.gap,
                        gap_state = excluded.gap_state,
                        priority = excluded.priority,
                        last_aggregated_at = now()
                    """.trimIndent(),
                ).use { statement ->
                    for (needClass in classes) {
                        var i = 1
                        statement.setString(i++, needClass.key.language)
                        statement.setString(i++, needClass.key.cefr)
                        statement.setString(i++, needClass.key.skill)
                        statement.setString(i++, needClass.key.phoneme)
                        statement.setString(i++, needClass.key.errorPattern)
                        statement.setString(i++, needClass.key.replacementPhoneme)
                        statement.setString(i++, needClass.key.position)
                        statement.setInt(i++, needClass.learnerCount)
                        statement.setInt(i++, needClass.availableExisting)
                        statement.setInt(i++, needClass.availableEffective)
                        statement.setInt(i++, needClass.gap)
                        statement.setString(i++, needClass.gapState.name)
                        statement.setDouble(i++, needClass.priority)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }
    }

    override fun needClasses(language: String?, limit: Int): List<NeedClass> {
        dataSource.connection.use { connection ->
            val sql = buildString {
                append(
                    """
                    select language, cefr, skill, phoneme, error_pattern, replacement_phoneme, position,
                           learner_count, available_existing, available_effective, gap, gap_state, priority
                    from glosso.need_classes
                    """.trimIndent(),
                )
                if (language != null) append(" where language = ?")
                append(" order by priority desc, learner_count desc, cefr limit ?")
            }
            connection.prepareStatement(sql).use { statement ->
                var i = 1
                if (language != null) statement.setString(i++, language)
                statement.setInt(i, limit.coerceIn(1, 1000))
                statement.executeQuery().use { rs ->
                    val classes = ArrayList<NeedClass>()
                    while (rs.next()) {
                        classes += NeedClass(
                            key = NeedClassKey(
                                language = rs.getString("language"),
                                cefr = rs.getString("cefr"),
                                skill = rs.getString("skill"),
                                phoneme = rs.getString("phoneme"),
                                errorPattern = rs.getString("error_pattern"),
                                replacementPhoneme = rs.getString("replacement_phoneme"),
                                position = rs.getString("position"),
                            ),
                            learnerCount = rs.getInt("learner_count"),
                            availableExisting = rs.getInt("available_existing"),
                            availableEffective = rs.getInt("available_effective"),
                            gap = rs.getInt("gap"),
                            gapState = runCatching { GapState.valueOf(rs.getString("gap_state")) }
                                .getOrDefault(GapState.OK),
                            priority = rs.getDouble("priority"),
                        )
                    }
                    return classes
                }
            }
        }
    }

    override fun needClassId(key: NeedClassKey): Long? {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select id from glosso.need_classes
                where language = ? and cefr = ? and skill = ?
                  and coalesce(phoneme, '') = coalesce(?, '')
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
                statement.executeQuery().use { rs -> return if (rs.next()) rs.getLong(1) else null }
            }
        }
    }

    override fun close() {
        (dataSource as? AutoCloseable)?.close()
    }

    private inline fun <T> Connection.inTransaction(block: () -> T): T {
        val previous = autoCommit
        autoCommit = false
        return try {
            val result = block()
            commit()
            result
        } catch (e: Exception) {
            rollback()
            throw e
        } finally {
            autoCommit = previous
        }
    }

    private fun setIntOrNull(statement: java.sql.PreparedStatement, index: Int, value: Int?) {
        if (value == null) statement.setNull(index, Types.INTEGER) else statement.setInt(index, value)
    }

    companion object {
        /**
         * Builds the store from the environment and applies migrations, or null
         * when it is not configured or unreachable. Never throws: the generation
         * control plane is optional and must not stop the signal plane from
         * serving, exactly like analytics.
         */
        fun fromEnv(env: Map<String, String> = System.getenv(), log: (String) -> Unit = {}): ControlPlaneStore? =
            ControlPlaneDatabase.open(env, log)?.controlStore
    }
}
