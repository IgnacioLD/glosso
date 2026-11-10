package com.glosso.server.generation

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real migrations against a real, empty PostgreSQL, so the test proves
 * the contract the resource-only [SchemaMigrationTest] cannot: that the runner
 * applies V2 and therefore `glosso.tts_assets` and `glosso.tts_costs` exist.
 */
class SchemaMigratorPostgresTest {

    private lateinit var postgres: EmbeddedPostgres
    private lateinit var dataSource: DataSource

    @BeforeTest
    fun startPostgres() {
        postgres = EmbeddedPostgres.builder().start()
        dataSource = postgres.postgresDatabase
    }

    @AfterTest
    fun stopPostgres() {
        postgres.close()
    }

    private fun existingTables(): Set<String> {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select table_name from information_schema.tables where table_schema = 'glosso'",
                ).use { rs ->
                    val tables = HashSet<String>()
                    while (rs.next()) tables += rs.getString(1)
                    return tables
                }
            }
        }
    }

    private fun columnsOftable(table: String): Set<String> {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select column_name from information_schema.columns where table_schema = 'glosso' and table_name = ?",
            ).use { statement ->
                statement.setString(1, table)
                statement.executeQuery().use { rs ->
                    val columns = HashSet<String>()
                    while (rs.next()) columns += rs.getString(1)
                    return columns
                }
            }
        }
    }

    @Test
    fun `an empty database gets the control plane, the tts and the pool tables`() {
        val applied = SchemaMigrator.migrate(dataSource)

        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8"), applied.sortedBy { it.toInt() })
        val tables = existingTables()
        assertTrue("users" in tables, "control-plane tables missing: $tables")
        assertTrue("tts_assets" in tables, "tts_assets missing: $tables")
        assertTrue("tts_costs" in tables, "tts_costs missing: $tables")
        assertTrue("content" in tables, "content pool missing: $tables")
        assertTrue("content_variants" in tables, "content_variants missing: $tables")
        assertTrue("generation_jobs" in tables, "generation_jobs missing: $tables")
        assertTrue("entitlements" in tables, "entitlements missing: $tables")
        assertTrue("lifetime_grants" in tables, "lifetime_grants missing: $tables")
        assertTrue("linked_accounts" in tables, "linked_accounts missing: $tables")
        // The one-time code was removed: V6 is kept as history, V8 drops its table.
        assertTrue("recovery_codes" !in tables, "recovery_codes must be gone: $tables")
    }

    @Test
    fun `the beta founder columns land on users`() {
        SchemaMigrator.migrate(dataSource)

        val columns = columnsOftable("users")
        for (column in listOf(
            "beta_founder",
            "beta_founder_since",
            "beta_founder_until",
            "beta_founder_benefit",
            "account_id",
        )) {
            assertTrue(column in columns, "users is missing $column: $columns")
        }
    }

    @Test
    fun `migrations are recorded and never applied twice`() {
        val first = SchemaMigrator.migrate(dataSource)
        val second = SchemaMigrator.migrate(dataSource)

        assertTrue(first.isNotEmpty())
        assertTrue(second.isEmpty(), "second run must be a no-op, got $second")
    }
}
