package com.glosso.server.product

import com.glosso.server.generation.SchemaMigrator
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the real JDBC store against a real PostgreSQL, because the in-memory
 * fake cannot prove the SQL of the account move. The route test locks the flow;
 * this locks the statements.
 */
class JdbcAccountStorePostgresTest {

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var dataSource: DataSource
    private lateinit var store: JdbcAccountStore

    @BeforeTest
    fun startPostgres() {
        postgres = EmbeddedPostgres.builder().start()
        dataSource = postgres.postgresDatabase
        SchemaMigrator.migrate(dataSource)
        store = JdbcAccountStore(dataSource)
    }

    @AfterTest
    fun stopPostgres() {
        store.close()
        postgres.close()
    }

    private fun user(deviceId: String): UserIdentity =
        store.upsertUser(deviceId, Instant.parse("2026-03-01T00:00:00Z")).identity

    private fun updatedAt(userId: UUID): Instant =
        dataSource.connection.use { connection ->
            connection.prepareStatement("select updated_at from glosso.users where id = ?").use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs ->
                    check(rs.next()) { "users row missing for $userId" }
                    rs.getTimestamp("updated_at").toInstant()
                }
            }
        }

    @Test
    fun `reading an existing user does not touch updated_at`() {
        val created = Instant.parse("2026-03-01T00:00:00Z")
        val identity = store.upsertUser("hot-device", created).identity
        val before = updatedAt(identity.id)

        // The hot GET /v1/me path calls upsertUser again with a later instant;
        // it must be a pure read, so updated_at stays put.
        store.upsertUser("hot-device", created.plusSeconds(3_600))

        assertEquals(before, updatedAt(identity.id))
    }

    private fun execute(sql: String, vararg params: Any?) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    private fun queryStrings(sql: String, vararg params: Any?): List<String?> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                statement.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    /** Rows written by the removed paid tier, which a move must still carry over. */
    private fun legacyPaidTierRows(userId: UUID, tokenHash: String) {
        execute(
            "update glosso.users set beta_founder = true, beta_founder_since = created_at where id = ?",
            userId,
        )
        execute(
            "insert into glosso.lifetime_grants (user_id, plan, source) values (?, 'PRO', 'beta_founder')",
            userId,
        )
        execute(
            "insert into glosso.entitlements (user_id, plan, status, source, provider_token_hash) " +
                "values (?, 'PRO', 'ACTIVE', 'play', ?)",
            userId, tokenHash,
        )
    }

    @Test
    fun `signing in merges what the new phone had instead of deleting it`() {
        val now = Instant.parse("2026-06-01T00:00:00Z")
        val account = user("old-phone")
        val fresh = user("new-phone")

        // English: the account's profile is older than the new phone's.
        execute(
            "insert into glosso.learner_profiles (user_id, language, cefr, updated_at) values (?, 'en', 'A2', ?)",
            account.id, java.sql.Timestamp.from(now.minusSeconds(86_400)),
        )
        execute(
            "insert into glosso.learner_profiles (user_id, language, cefr, updated_at) values (?, 'en', 'B1', ?)",
            fresh.id, java.sql.Timestamp.from(now.minusSeconds(60)),
        )
        // French only on the account; German only on the new phone.
        execute("insert into glosso.learner_profiles (user_id, language, cefr) values (?, 'fr', 'A1')", account.id)
        execute("insert into glosso.learner_profiles (user_id, language, cefr) values (?, 'de', 'A1')", fresh.id)
        // Same phoneme on both sides, the account's being the newest.
        execute(
            "insert into glosso.phoneme_profiles (user_id, language, phoneme, score, updated_at) values (?, 'en', 'θ', 80, ?)",
            account.id, java.sql.Timestamp.from(now),
        )
        execute(
            "insert into glosso.phoneme_profiles (user_id, language, phoneme, score, updated_at) values (?, 'en', 'θ', 20, ?)",
            fresh.id, java.sql.Timestamp.from(now.minusSeconds(3_600)),
        )
        execute("insert into glosso.phoneme_profiles (user_id, language, phoneme, score) values (?, 'en', 'ð', 55)", fresh.id)
        execute("insert into glosso.user_needs (user_id, language, skill) values (?, 'de', 'pronunciation')", fresh.id)
        legacyPaidTierRows(fresh.id, "fresh-token")

        store.signInWithExternalAccount("google", "sub-merge", "old-phone", now)
        val moved = store.signInWithExternalAccount("google", "sub-merge", "new-phone", now)

        assertEquals(account.id, (moved as ExternalSignIn.SignedIn).identity.id)
        assertEquals(account.id, store.upsertUser("new-phone", now).identity.id)
        assertEquals(
            listOf("de:A1", "en:B1", "fr:A1"),
            queryStrings(
                "select language || ':' || cefr from glosso.learner_profiles where user_id = ? order by language",
                account.id,
            ),
        )
        assertEquals(
            setOf("ð:55", "θ:80"),
            queryStrings(
                "select phoneme || ':' || score from glosso.phoneme_profiles where user_id = ?",
                account.id,
            ).toSet(),
        )
        assertEquals(listOf("de"), queryStrings("select language from glosso.user_needs where user_id = ?", account.id))
        assertEquals(
            listOf("fresh-token"),
            queryStrings("select provider_token_hash from glosso.entitlements where user_id = ?", account.id),
        )
        assertEquals(
            listOf("beta_founder"),
            queryStrings("select source from glosso.lifetime_grants where user_id = ?", account.id),
        )
        assertEquals(
            listOf("true"),
            queryStrings("select beta_founder::text from glosso.users where id = ?", account.id),
        )
        assertEquals(
            emptyList(),
            queryStrings("select id::text from glosso.users where id = ?", fresh.id),
        )
    }

    @Test
    fun `a Google account links once and then follows the person to a new phone`() {
        val now = Instant.parse("2026-06-01T00:00:00Z")
        val account = user("phone-1")

        val linked = store.signInWithExternalAccount("google", "sub-1", "phone-1", now)
        assertEquals(ExternalSignIn.SignedIn(store.upsertUser("phone-1", now).identity, moved = false), linked)
        // Idempotent on the same phone.
        assertEquals(false, (store.signInWithExternalAccount("google", "sub-1", "phone-1", now) as ExternalSignIn.SignedIn).moved)

        val fresh = user("phone-2")
        execute("insert into glosso.learner_profiles (user_id, language, cefr) values (?, 'de', 'A1')", fresh.id)
        val moved = store.signInWithExternalAccount("google", "sub-1", "phone-2", now)

        assertTrue(moved is ExternalSignIn.SignedIn && moved.moved, moved.toString())
        assertEquals(account.id, moved.identity.id)
        assertEquals("phone-2", moved.identity.deviceId)
        assertEquals(listOf("de"), queryStrings("select language from glosso.learner_profiles where user_id = ?", account.id))
        assertEquals(
            listOf(account.id.toString()),
            queryStrings("select user_id::text from glosso.linked_accounts where provider = 'google' and subject = 'sub-1'"),
        )
        // The old phone starts a new, unlinked anonymous user.
        val orphan = store.upsertUser("phone-1", now).identity
        assertTrue(orphan.id != account.id)
    }

    @Test
    fun `a phone linked to one Google account refuses another one`() {
        val now = Instant.parse("2026-06-01T00:00:00Z")
        user("phone-a")
        user("phone-b")
        store.signInWithExternalAccount("google", "sub-a", "phone-a", now)
        store.signInWithExternalAccount("google", "sub-b", "phone-b", now)

        assertEquals(ExternalSignIn.Conflict, store.signInWithExternalAccount("google", "sub-b", "phone-a", now))
        assertEquals(ExternalSignIn.Conflict, store.signInWithExternalAccount("google", "sub-c", "phone-a", now))
        assertEquals(
            listOf("sub-a"),
            queryStrings(
                "select subject from glosso.linked_accounts l join glosso.users u on u.id = l.user_id where u.device_id = 'phone-a'",
            ),
        )
    }
}
