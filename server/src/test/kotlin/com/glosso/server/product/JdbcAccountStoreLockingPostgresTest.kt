package com.glosso.server.product

import com.glosso.server.generation.SchemaMigrator
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the lock order of the account flows against a real PostgreSQL. Every
 * flow that touches a user's external links locks the `users` rows first;
 * these tests force the interleavings that used to deadlock or race (an
 * external transaction holds the `users` row while the flows queue up behind
 * it) and check that each one now finishes with a clean outcome.
 */
class JdbcAccountStoreLockingPostgresTest {

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var dataSource: DataSource
    private lateinit var store: JdbcAccountStore
    private lateinit var pool: ExecutorService

    private val now = Instant.parse("2026-06-01T00:00:00Z")

    @BeforeTest
    fun startPostgres() {
        postgres = EmbeddedPostgres.builder().start()
        dataSource = postgres.postgresDatabase
        SchemaMigrator.migrate(dataSource)
        store = JdbcAccountStore(dataSource)
        pool = Executors.newCachedThreadPool()
    }

    @AfterTest
    fun stopPostgres() {
        pool.shutdownNow()
        store.close()
        postgres.close()
    }

    private fun user(deviceId: String): UserIdentity = store.upsertUser(deviceId, now).identity

    /** Opens a transaction holding the `users` row of [userId] until it is closed. */
    private fun holdUser(userId: UUID): Connection {
        val connection = dataSource.connection
        connection.autoCommit = false
        connection.prepareStatement("select id from glosso.users where id = ? for update").use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().close()
        }
        return connection
    }

    /** Waits until [count] backends are blocked on a lock. */
    private fun awaitLockWaiters(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val waiting = dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock'",
                ).use { statement -> statement.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
            }
            if (waiting >= count) return
            Thread.sleep(20)
        }
        error("expected $count backends waiting on a lock")
    }

    private fun <T> Future<T>.result(): T = get(30, TimeUnit.SECONDS)

    @Test
    fun `two Google accounts signing in on one phone at once link only one`() {
        val phone = user("phone")
        val holder = holdUser(phone.id)
        val first = pool.submit(Callable { store.signInWithExternalAccount("google", "sub-a", "phone", now) })
        val second = pool.submit(Callable { store.signInWithExternalAccount("google", "sub-b", "phone", now) })
        awaitLockWaiters(2)
        holder.rollback()
        holder.close()

        val outcomes = listOf(first.result(), second.result())
        assertEquals(1, outcomes.count { it is ExternalSignIn.SignedIn }, outcomes.toString())
        assertEquals(1, outcomes.count { it == ExternalSignIn.Conflict }, outcomes.toString())
        val links = dataSource.connection.use { connection ->
            connection.prepareStatement("select count(*) from glosso.linked_accounts where user_id = ?").use { statement ->
                statement.setObject(1, phone.id)
                statement.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertEquals(1, links)
    }

    @Test
    fun `a user that vanished mid-flight is a retryable conflict, not a crash`() {
        assertFailsWith<AccountChangedConcurrently> {
            store.signInWithExternalAccount("google", "sub-ghost", "no-such-device", now)
        }
    }
}
