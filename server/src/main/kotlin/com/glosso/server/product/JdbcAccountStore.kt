package com.glosso.server.product

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * PostgreSQL implementation of identity and linked-account persistence. Raw
 * JDBC, like the rest of the control plane: no ORM, one shared pool.
 */
class JdbcAccountStore(private val dataSource: DataSource) : AccountStore {

    override fun upsertUser(deviceId: String, now: Instant): UserUpsert {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val inserted = connection.prepareStatement(
                    """
                    insert into glosso.users (device_id, created_at, updated_at)
                    values (?, ?, ?)
                    on conflict (device_id) do nothing
                    returning id, device_id, created_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, deviceId)
                    statement.setTimestamp(2, Timestamp.from(now))
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.executeQuery().use { rs -> if (rs.next()) rs.toIdentity() else null }
                }
                val identity = inserted ?: run {
                    // A read of an existing user changes nothing, so it must not
                    // bump updated_at: upsertUser also serves the hot GET /v1/me.
                    connection.prepareStatement(
                        """
                        select id, device_id, created_at
                        from glosso.users where device_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, deviceId)
                        statement.executeQuery().use { rs ->
                            // The row was merged into an account between the two statements.
                            if (!rs.next()) throw AccountChangedConcurrently("users row disappeared during upsert")
                            rs.toIdentity()
                        }
                    }
                }
                connection.commit()
                return UserUpsert(identity, created = inserted != null)
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    // Lock order for the account flows: the `users` rows first, all at once and
    // sorted (lockUsers), and only then external links. Sign-in takes its
    // advisory lock before any of that and never while holding a row.

    override fun signInWithExternalAccount(
        provider: String,
        subject: String,
        deviceId: String,
        now: Instant,
    ): ExternalSignIn = transaction { connection ->
        // Serialize sign-ins of the same external identity, so two devices
        // signing in at once cannot both link it or both take the account.
        connection.prepareStatement("select pg_advisory_xact_lock(hashtext(?))").use { statement ->
            statement.setString(1, "$provider:$subject")
            statement.executeQuery().close()
        }
        val deviceUserId = userIdOfDevice(connection, deviceId)
            ?: throw AccountChangedConcurrently("no user for the signing-in device")
        val linkedUserId = linkedUserId(connection, provider, subject)
        lockUsers(connection, *listOfNotNull(deviceUserId, linkedUserId).toTypedArray())
        // Everything below is decided under the users locks: a sign-in of
        // another identity on the same device queues here, so it sees the
        // link this one writes instead of inserting a second one.
        if (userIdOfDevice(connection, deviceId) != deviceUserId ||
            linkedUserId(connection, provider, subject) != linkedUserId
        ) {
            throw AccountChangedConcurrently("the device or the link changed during a sign-in")
        }
        if (linkedUserId == deviceUserId) {
            touchLink(connection, provider, subject, now)
            return@transaction ExternalSignIn.SignedIn(loadIdentity(connection, deviceUserId), moved = false)
        }
        val deviceAlreadyLinked = connection.prepareStatement(
            "select 1 from glosso.linked_accounts where user_id = ? and provider = ?",
        ).use { statement ->
            statement.setObject(1, deviceUserId)
            statement.setString(2, provider)
            statement.executeQuery().use { rs -> rs.next() }
        }
        if (deviceAlreadyLinked) return@transaction ExternalSignIn.Conflict

        if (linkedUserId == null) {
            connection.prepareStatement(
                """
                insert into glosso.linked_accounts (user_id, provider, subject, linked_at, last_sign_in_at)
                values (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, deviceUserId)
                statement.setString(2, provider)
                statement.setString(3, subject)
                statement.setTimestamp(4, Timestamp.from(now))
                statement.setTimestamp(5, Timestamp.from(now))
                statement.executeUpdate()
            }
            markAccount(connection, deviceUserId)
            return@transaction ExternalSignIn.SignedIn(loadIdentity(connection, deviceUserId), moved = false)
        }

        moveAccountToDevice(connection, linkedUserId, deviceUserId, deviceId)
        touchLink(connection, provider, subject, now)
        ExternalSignIn.SignedIn(loadIdentity(connection, linkedUserId), moved = true)
    }

    override fun linkedProviders(userId: UUID): List<String> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select provider from glosso.linked_accounts where user_id = ? order by provider",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    /**
     * Moves the account [accountUserId] to [deviceId], merging the device's own
     * placeholder row [placeholderId] (if any) into the account first; see the
     * account move on [AccountStore]. Runs inside the caller's transaction,
     * which already holds the users locks of both rows.
     */
    private fun moveAccountToDevice(
        connection: Connection,
        accountUserId: UUID,
        placeholderId: UUID?,
        deviceId: String,
    ) {
        if (placeholderId != null && placeholderId != accountUserId) {
            MERGE_STATEMENTS.forEach { sql ->
                val names = MERGE_PARAMETER.findAll(sql).map { it.value }.toList()
                connection.prepareStatement(sql.replace(MERGE_PARAMETER, "?")).use { statement ->
                    names.forEachIndexed { index, name ->
                        statement.setObject(index + 1, if (name == ":account") accountUserId else placeholderId)
                    }
                    statement.executeUpdate()
                }
            }
            connection.prepareStatement("delete from glosso.users where id = ?").use { statement ->
                statement.setObject(1, placeholderId)
                statement.executeUpdate()
            }
        }
        connection.prepareStatement(
            """
            update glosso.users
            set device_id = ?, account_id = coalesce(account_id, id::text), updated_at = now()
            where id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, deviceId)
            statement.setObject(2, accountUserId)
            try {
                statement.executeUpdate()
            } catch (e: SQLException) {
                // A users row for the device appeared after the lookup (a first
                // GET /v1/me racing the move): nothing to merge it with here.
                if (e.sqlState == UNIQUE_VIOLATION) {
                    throw AccountChangedConcurrently("the device got a user during a move")
                }
                throw e
            }
        }
    }

    /** Locks the users rows in a fixed order, so two account flows can never deadlock. */
    private fun lockUsers(connection: Connection, vararg userIds: UUID) {
        val missing = tryLockUsers(connection, userIds.toList())
        if (missing.isNotEmpty()) throw AccountChangedConcurrently("users rows vanished: $missing")
    }

    /** Like [lockUsers], but returns the ids whose row no longer exists. */
    private fun tryLockUsers(connection: Connection, userIds: Collection<UUID>): Set<UUID> =
        userIds.toSortedSet().filterTo(mutableSetOf()) { id ->
            connection.prepareStatement("select id from glosso.users where id = ? for update").use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { rs -> !rs.next() }
            }
        }

    private fun linkedUserId(connection: Connection, provider: String, subject: String): UUID? =
        connection.prepareStatement(
            "select user_id from glosso.linked_accounts where provider = ? and subject = ?",
        ).use { statement ->
            statement.setString(1, provider)
            statement.setString(2, subject)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getObject("user_id", UUID::class.java) else null }
        }

    /** Opts the user into a stable account: `account_id` survives device moves. */
    private fun markAccount(connection: Connection, userId: UUID) {
        connection.prepareStatement(
            "update glosso.users set account_id = coalesce(account_id, id::text), updated_at = now() where id = ?",
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
    }

    private fun touchLink(connection: Connection, provider: String, subject: String, now: Instant) {
        connection.prepareStatement(
            "update glosso.linked_accounts set last_sign_in_at = ? where provider = ? and subject = ?",
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(now))
            statement.setString(2, provider)
            statement.setString(3, subject)
            statement.executeUpdate()
        }
    }

    private fun userIdOfDevice(connection: Connection, deviceId: String): UUID? =
        connection.prepareStatement("select id from glosso.users where device_id = ?").use { statement ->
            statement.setString(1, deviceId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getObject("id", UUID::class.java) else null }
        }

    private inline fun <T> transaction(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }

    private fun loadIdentity(connection: Connection, userId: UUID): UserIdentity =
        connection.prepareStatement(
            """
            select id, device_id, created_at
            from glosso.users where id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, userId)
            statement.executeQuery().use { rs ->
                check(rs.next()) { "users row missing for $userId" }
                rs.toIdentity()
            }
        }

    // The DataSource is shared with the rest of the control plane and owned by
    // ControlPlaneDatabase; closing it here would tear down a pool this store
    // does not own. The interface keeps close() for symmetry only.
    override fun close() {}

    private fun ResultSet.toIdentity(): UserIdentity = UserIdentity(
        id = getObject("id", UUID::class.java),
        deviceId = getString("device_id"),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    private companion object {
        val MERGE_PARAMETER = Regex(":account|:placeholder")

        const val UNIQUE_VIOLATION = "23505"

        /**
         * Merges a placeholder user into an account, in order, with the two ids
         * bound to `:account` and `:placeholder`; see [moveAccountToDevice].
         * Where both sides hold the same key the most recently updated row wins;
         * the rest of the placeholder's rows are re-parented, never deleted.
         * Rows left from the removed paid tier (entitlements, lifetime grants,
         * Beta Founder) are carried over too, so a move never drops them.
         */
        val MERGE_STATEMENTS = listOf(
            // Learner profiles, one per language.
            """
            delete from glosso.learner_profiles a using glosso.learner_profiles p
            where a.user_id = :account and p.user_id = :placeholder and p.language = a.language
              and p.updated_at > a.updated_at
            """,
            """
            delete from glosso.learner_profiles p using glosso.learner_profiles a
            where p.user_id = :placeholder and a.user_id = :account and p.language = a.language
            """,
            "update glosso.learner_profiles set user_id = :account where user_id = :placeholder",
            // Phoneme profiles, keyed like phoneme_profiles_key_idx.
            """
            delete from glosso.phoneme_profiles a using glosso.phoneme_profiles p
            where a.user_id = :account and p.user_id = :placeholder
              and p.language = a.language and p.phoneme = a.phoneme
              and coalesce(p.error_pattern, '') = coalesce(a.error_pattern, '')
              and coalesce(p.position, '') = coalesce(a.position, '')
              and p.updated_at > a.updated_at
            """,
            """
            delete from glosso.phoneme_profiles p using glosso.phoneme_profiles a
            where p.user_id = :placeholder and a.user_id = :account
              and p.language = a.language and p.phoneme = a.phoneme
              and coalesce(p.error_pattern, '') = coalesce(a.error_pattern, '')
              and coalesce(p.position, '') = coalesce(a.position, '')
            """,
            "update glosso.phoneme_profiles set user_id = :account where user_id = :placeholder",
            // Open needs are re-derived per language on every sync, so the two
            // sides are never mixed: the side synced last keeps the language.
            """
            delete from glosso.user_needs a
            where a.user_id = :account and a.status = 'open' and a.language in (
                select p.language from glosso.user_needs p
                where p.user_id = :placeholder and p.status = 'open'
                group by p.language
                having max(p.updated_at) > (
                    select max(x.updated_at) from glosso.user_needs x
                    where x.user_id = :account and x.status = 'open' and x.language = p.language
                )
            )
            """,
            """
            delete from glosso.user_needs p
            where p.user_id = :placeholder and p.status = 'open' and exists (
                select 1 from glosso.user_needs a
                where a.user_id = :account and a.status = 'open' and a.language = p.language
            )
            """,
            "update glosso.user_needs set user_id = :account where user_id = :placeholder",
            // Legacy paid-tier rows: kept, never written any more.
            """
            update glosso.entitlements p set user_id = :account
            where p.user_id = :placeholder and not exists (
                select 1 from glosso.entitlements a where a.user_id = :account
            )
            """,
            // Lifetime grants the account does not hold yet.
            """
            update glosso.lifetime_grants p set user_id = :account
            where p.user_id = :placeholder and not exists (
                select 1 from glosso.lifetime_grants a
                where a.user_id = :account and a.plan = p.plan and a.source = p.source
            )
            """,
            // An external link for a provider the account is not linked to yet.
            """
            update glosso.linked_accounts p set user_id = :account
            where p.user_id = :placeholder and not exists (
                select 1 from glosso.linked_accounts a
                where a.user_id = :account and a.provider = p.provider
            )
            """,
            // Beta Founder is a permanent status: the union of both sides.
            """
            update glosso.users a
            set beta_founder = true,
                beta_founder_since = p.beta_founder_since,
                beta_founder_until = p.beta_founder_until,
                beta_founder_benefit = p.beta_founder_benefit,
                updated_at = now()
            from glosso.users p
            where a.id = :account and p.id = :placeholder and not a.beta_founder and p.beta_founder
            """,
        ).map { it.trimIndent() }
    }
}
