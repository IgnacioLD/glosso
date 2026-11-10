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
 * fake cannot prove the SQL that resolves the RTDN revocation. The route test
 * locks the flow; this locks the statement.
 */
class JdbcEntitlementStorePostgresTest {

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var dataSource: DataSource
    private lateinit var store: JdbcEntitlementStore

    @BeforeTest
    fun startPostgres() {
        postgres = EmbeddedPostgres.builder().start()
        dataSource = postgres.postgresDatabase
        SchemaMigrator.migrate(dataSource)
        store = JdbcEntitlementStore(dataSource)
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

    private fun paid(userId: UUID, tokenHash: String, plan: String = "PRO"): StoredEntitlement =
        StoredEntitlement(
            userId = userId,
            plan = plan,
            status = "ACTIVE",
            source = "play",
            expiresAt = Instant.parse("2026-04-01T00:00:00Z"),
            startedAt = Instant.parse("2026-03-01T00:00:00Z"),
            autoRenew = false,
            provider = "google_play",
            providerTokenHash = tokenHash,
        )

    @Test
    fun `revoking a token revokes every device that verified it`() {
        val hash = "canonical-hash"
        val devices = listOf("device-a", "device-b").map(::user)
        devices.forEach { store.upsertEntitlement(paid(it.id, hash)) }

        store.updateEntitlementsByProviderTokenHash(
            providerTokenHash = hash,
            entitled = false,
            status = "NONE",
            expiresAt = null,
            autoRenew = false,
        )

        for (device in devices) {
            val row = store.entitlement(device.id)
            assertEquals("FREE", row?.plan, device.deviceId)
            assertEquals("NONE", row?.status, device.deviceId)
            // Kept so a later renewal or refund for the same token still lands.
            assertEquals(hash, row?.providerTokenHash, device.deviceId)
        }
    }

    @Test
    fun `a renewal re-entitles every device without downgrading personalized`() {
        val hash = "renewal-hash"
        val a = user("device-a")
        val b = user("device-b")
        val personalized = user("device-c")
        store.upsertEntitlement(paid(a.id, hash))
        store.upsertEntitlement(paid(b.id, hash))
        store.upsertEntitlement(paid(personalized.id, hash, plan = "PERSONALIZED"))

        store.updateEntitlementsByProviderTokenHash(
            providerTokenHash = hash,
            entitled = true,
            status = "ACTIVE",
            expiresAt = Instant.parse("2026-05-01T00:00:00Z"),
            autoRenew = true,
        )

        assertEquals("PRO", store.entitlement(a.id)?.plan)
        assertEquals(true, store.entitlement(a.id)?.autoRenew)
        assertEquals("PRO", store.entitlement(b.id)?.plan)
        assertEquals("PERSONALIZED", store.entitlement(personalized.id)?.plan)
    }

    @Test
    fun `revoking a token leaves a personalized grant untouched`() {
        val hash = "personalized-hash"
        val pro = user("device-a")
        val personalized = user("device-b")
        store.upsertEntitlement(paid(pro.id, hash))
        store.upsertEntitlement(paid(personalized.id, hash, plan = "PERSONALIZED"))
        val before = store.entitlement(personalized.id)

        store.updateEntitlementsByProviderTokenHash(
            providerTokenHash = hash,
            entitled = false,
            status = "NONE",
            expiresAt = null,
            autoRenew = false,
        )

        assertEquals("FREE", store.entitlement(pro.id)?.plan)
        val after = store.entitlement(personalized.id)
        assertEquals("PERSONALIZED", after?.plan)
        assertEquals("ACTIVE", after?.status)
        assertEquals(before?.expiresAt, after?.expiresAt)
        assertEquals(hash, after?.providerTokenHash)
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

    @Test
    fun `a lifetime grant is recorded once and a fraud revocation is never undone`() {
        val founder = user("founder-device")
        val at = Instant.parse("2026-10-01T00:00:00Z")
        store.grantLifetime(founder.id, "PRO", "beta_founder", at)
        store.grantLifetime(founder.id, "PRO", "beta_founder", at.plusSeconds(60))

        val grants = store.lifetimeGrants(founder.id)
        assertEquals(1, grants.size)
        assertEquals("PRO", grants.single().plan)
        assertEquals(at, grants.single().grantedAt)

        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    "update glosso.lifetime_grants set revoked_at = now(), revoke_reason = 'fraud' " +
                        "where user_id = '${founder.id}'",
                )
            }
        }
        assertEquals(emptyList(), store.lifetimeGrants(founder.id))
        store.grantLifetime(founder.id, "PRO", "beta_founder", at)
        assertEquals(emptyList(), store.lifetimeGrants(founder.id))
    }

    @Test
    fun `V5 backfills the lifetime PRO of founders marked before it`() {
        val founder = user("legacy-founder")
        store.markBetaFounder(founder.id, founder.createdAt, null, "Ventaja Beta Founder")
        val standard = user("legacy-standard")

        val v5 = checkNotNull(javaClass.getResource("/db/migration/V5__lifetime_grants.sql")).readText()
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute(v5) } }

        assertEquals(listOf("PRO"), store.lifetimeGrants(founder.id).map { it.plan })
        assertEquals(emptyList(), store.lifetimeGrants(standard.id))
        val benefit = dataSource.connection.use { connection ->
            connection.prepareStatement("select beta_founder_benefit from glosso.users where id = ?").use { st ->
                st.setObject(1, founder.id)
                st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
        }
        assertEquals("PRO_LIFETIME", benefit)
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

    @Test
    fun `signing in merges what the new phone had instead of deleting it`() {
        val now = Instant.parse("2026-06-01T00:00:00Z")
        val account = user("old-phone")
        store.markBetaFounder(account.id, account.createdAt, null, "PRO_LIFETIME")
        store.grantLifetime(account.id, "PRO", EntitlementService.BETA_FOUNDER_SOURCE, account.createdAt)
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
        // The new phone bought PRO through Play; the account never paid.
        store.upsertEntitlement(paid(fresh.id, "fresh-token"))

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
        assertEquals("fresh-token", store.entitlement(account.id)?.providerTokenHash)
        assertEquals(listOf("PRO"), store.lifetimeGrants(account.id).map { it.plan })
        assertEquals(
            emptyList(),
            queryStrings("select id::text from glosso.users where id = ?", fresh.id),
        )
    }

    private fun mergeEntitlements(account: StoredEntitlement?, fresh: StoredEntitlement?): StoredEntitlement? {
        val now = Instant.parse("2026-03-15T00:00:00Z")
        val tag = UUID.randomUUID()
        val accountUser = user("account-$tag")
        val freshUser = user("fresh-$tag")
        account?.let { store.upsertEntitlement(it.copy(userId = accountUser.id)) }
        fresh?.let { store.upsertEntitlement(it.copy(userId = freshUser.id)) }
        store.signInWithExternalAccount("google", "sub-$tag", "account-$tag", now)
        store.signInWithExternalAccount("google", "sub-$tag", "fresh-$tag", now)
        return store.entitlement(accountUser.id)
    }

    @Test
    fun `merging two paid users keeps the highest entitlement, whichever side has it`() {
        val someone = UUID.randomUUID()
        val pro = paid(someone, "pro-token", plan = "PRO")
        val personalized = paid(someone, "personalized-token", plan = "PERSONALIZED")
        val lapsed = paid(someone, "lapsed-token", plan = "PERSONALIZED")
            .copy(expiresAt = Instant.parse("2026-03-10T00:00:00Z"))
        val free = pro.copy(plan = "FREE", status = "NONE", providerTokenHash = null)

        // The new phone's higher plan used to vanish with the placeholder.
        assertEquals("personalized-token", mergeEntitlements(pro, personalized)?.providerTokenHash)
        assertEquals("personalized-token", mergeEntitlements(personalized, pro)?.providerTokenHash)
        // In force beats a higher plan that already lapsed.
        assertEquals("pro-token", mergeEntitlements(lapsed, pro)?.providerTokenHash)
        assertEquals("pro-token", mergeEntitlements(free, pro)?.providerTokenHash)
        // A tie keeps the account's own row.
        assertEquals("pro-token", mergeEntitlements(pro, pro.copy(providerTokenHash = "other-pro"))?.providerTokenHash)
        assertEquals("pro-token", mergeEntitlements(pro, null)?.providerTokenHash)
    }

    @Test
    fun `a Google account links once and then follows the person to a new phone`() {
        val now = Instant.parse("2026-06-01T00:00:00Z")
        val account = user("phone-1")
        store.markBetaFounder(account.id, account.createdAt, null, "PRO_LIFETIME")
        store.grantLifetime(account.id, "PRO", EntitlementService.BETA_FOUNDER_SOURCE, account.createdAt)

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
        assertTrue(moved.identity.betaFounder)
        assertEquals(listOf("PRO"), store.lifetimeGrants(account.id).map { it.plan })
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
