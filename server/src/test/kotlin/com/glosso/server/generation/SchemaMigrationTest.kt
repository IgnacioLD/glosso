package com.glosso.server.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the PostgreSQL contract without a live database: the migrations are plain
 * resources, so their shape can be asserted here. The JDBC path itself is only
 * exercised on a real deployment.
 */
class SchemaMigrationTest {

    private fun resource(path: String): String =
        SchemaMigrationTest::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("missing resource $path")

    private val up: String get() = resource("/db/migration/V1__control_plane.sql")

    @Test
    fun `the migration declares the phase 1 tables`() {
        val sql = up.lowercase()
        val tables = listOf(
            "glosso.users",
            "glosso.learner_profiles",
            "glosso.phoneme_profiles",
            "glosso.user_needs",
            "glosso.need_classes",
        )
        for (table in tables) {
            assertTrue(sql.contains("create table if not exists $table"), "missing $table")
        }
    }

    @Test
    fun `the migration never touches the analytics table`() {
        assertFalse(up.lowercase().contains("analytics_event"))
    }

    @Test
    fun `need classes stay anonymous and carry the demand snapshot`() {
        val sql = up.lowercase()
        assertFalse(
            sql.substringAfter("create table if not exists glosso.need_classes")
                .substringBefore(");")
                .contains("user_id"),
            "need_classes must never carry a user_id",
        )
        for (column in listOf("learner_count", "available_existing", "available_effective", "gap", "priority")) {
            assertTrue(sql.contains(column), "need_classes is missing $column")
        }
    }

    @Test
    fun `the down migration drops every table the up migration creates`() {
        val created = Regex("""create table if not exists (glosso\.[a-z_]+)""", RegexOption.IGNORE_CASE)
            .findAll(up)
            .map { it.groupValues[1].lowercase() }
            .toSet()
        val dropped = Regex("""drop table if exists (glosso\.[a-z_]+)""", RegexOption.IGNORE_CASE)
            .findAll(resource("/db/migration/V1__control_plane.down.sql"))
            .map { it.groupValues[1].lowercase() }
            .toSet()

        assertTrue(created.isNotEmpty(), "no create table statements found")
        assertEquals(created, dropped)
    }

    @Test
    fun `the required indices are declared`() {
        val sql = up.lowercase()
        val indices = listOf(
            "learner_profiles_cefr_idx",
            "phoneme_profiles_key_idx",
            "phoneme_profiles_phoneme_idx",
            "user_needs_user_idx",
            "user_needs_phoneme_idx",
            "user_needs_status_idx",
            "need_classes_key_idx",
            "need_classes_demand_idx",
            "need_classes_lookup_idx",
        )
        for (index in indices) assertTrue(sql.contains(index), "missing index $index")
    }

    @Test
    fun `the runner knows the control plane migration`() {
        val migration = SchemaMigrator.migrations.single { it.version == "1" }
        assertNotNull(
            SchemaMigrationTest::class.java.getResource(migration.resource),
            "migration resource is not on the classpath",
        )
    }

    @Test
    fun `the runner discovers the tts migration without a hardcoded list`() {
        val migration = SchemaMigrator.migrations.single { it.version == "2" }
        assertEquals("/db/migration/V2__tts_assets.sql", migration.resource)
        assertNotNull(
            SchemaMigrationTest::class.java.getResource(migration.resource),
            "migration resource is not on the classpath",
        )
        assertTrue(
            SchemaMigrator.migrations.none { it.resource.endsWith(".down.sql") },
            "down migrations must never be applied by the runner",
        )
    }

    @Test
    fun `the pool migration declares the phase 2 tables and the gap view`() {
        val sql = resource("/db/migration/V3__content_pool.sql").lowercase()
        for (table in listOf("glosso.content", "glosso.content_variants", "glosso.generation_jobs")) {
            assertTrue(sql.contains("create table if not exists $table"), "missing $table")
        }
        assertTrue(sql.contains("create or replace view glosso.v_gap"), "missing v_gap")
        for (column in listOf("variant_hash", "job_hash", "status", "need_class_id", "audio_key", "slow_audio_key")) {
            assertTrue(sql.contains(column), "pool migration is missing $column")
        }
    }

    @Test
    fun `the phase 2 down migration drops every table the up migration creates`() {
        val up = resource("/db/migration/V3__content_pool.sql")
        val created = Regex("""create table if not exists (glosso\.[a-z_]+)""", RegexOption.IGNORE_CASE)
            .findAll(up)
            .map { it.groupValues[1].lowercase() }
            .toSet()
        val dropped = Regex("""drop table if exists (glosso\.[a-z_]+)""", RegexOption.IGNORE_CASE)
            .findAll(resource("/db/migration/V3__content_pool.down.sql"))
            .map { it.groupValues[1].lowercase() }
            .toSet()

        assertTrue(created.isNotEmpty(), "no create table statements found")
        assertEquals(created, dropped)
    }

    @Test
    fun `the runner discovers the pool migration without a hardcoded list`() {
        val migration = SchemaMigrator.migrations.single { it.version == "3" }
        assertEquals("/db/migration/V3__content_pool.sql", migration.resource)
        assertNotNull(
            SchemaMigrationTest::class.java.getResource(migration.resource),
            "migration resource is not on the classpath",
        )
        assertTrue(
            SchemaMigrator.migrations.none { it.resource.endsWith(".down.sql") },
            "down migrations must never be applied by the runner",
        )
    }

    @Test
    fun `the entitlements migration declares the table and the beta founder columns`() {
        val sql = resource("/db/migration/V4__entitlements_and_beta.sql").lowercase()
        assertTrue(sql.contains("create table if not exists glosso.entitlements"), "missing entitlements table")
        for (column in listOf(
            "beta_founder",
            "beta_founder_since",
            "beta_founder_until",
            "beta_founder_benefit",
            "account_id",
            "provider_token_hash",
            "auto_renew",
        )) {
            assertTrue(sql.contains(column), "V4 is missing $column")
        }
    }

    @Test
    fun `the runner discovers the entitlements migration without a hardcoded list`() {
        val migration = SchemaMigrator.migrations.single { it.version == "4" }
        assertEquals("/db/migration/V4__entitlements_and_beta.sql", migration.resource)
        assertNotNull(
            SchemaMigrationTest::class.java.getResource(migration.resource),
            "migration resource is not on the classpath",
        )
        assertTrue(
            SchemaMigrator.migrations.none { it.resource.endsWith(".down.sql") },
            "down migrations must never be applied by the runner",
        )
    }

    @Test
    fun `the entitlements down migration reverses the table and the columns`() {
        val sql = resource("/db/migration/V4__entitlements_and_beta.down.sql").lowercase()
        assertTrue(sql.contains("drop table if exists glosso.entitlements"), "missing table drop")
        assertTrue(sql.contains("drop column if exists beta_founder"), "missing column drops")
    }

    @Test
    fun `the recovery migration declares the one-time code table`() {
        val sql = resource("/db/migration/V6__founder_recovery.sql").lowercase()
        assertTrue(sql.contains("create table if not exists glosso.recovery_codes"), "missing recovery_codes")
        for (column in listOf("user_id", "code_hash", "created_at", "expires_at", "used_at")) {
            assertTrue(sql.contains(column), "V6 is missing $column")
        }
        assertTrue(sql.contains("code_hash   text not null unique"), "the code hash must be unique")
        // One active code per user is a database invariant, not only a code path.
        assertTrue(
            Regex("""create unique index if not exists \w+\s+on glosso\.recovery_codes \(user_id\)\s+where used_at is null""")
                .containsMatchIn(sql),
            "one active code per user must be a unique partial index",
        )
    }

    @Test
    fun `the linked accounts migration stores only the opaque external id`() {
        val sql = resource("/db/migration/V7__linked_accounts.sql").lowercase()
        assertTrue(sql.contains("create table if not exists glosso.linked_accounts"), "missing linked_accounts")
        assertTrue(sql.contains("unique (provider, subject)"), "an external identity belongs to one user")
        assertTrue(sql.contains("unique (user_id, provider)"), "one identity per provider and user")
        val table = sql.substringAfter("create table if not exists glosso.linked_accounts").substringBefore(");")
        for (personal in listOf("email", "name ", "picture", "token")) {
            assertTrue(personal !in table, "linked_accounts must not store $personal")
        }
        val down = resource("/db/migration/V7__linked_accounts.down.sql").lowercase()
        assertTrue(down.contains("drop table if exists glosso.linked_accounts"), "missing down drop")
    }

    @Test
    fun `the recovery down migration drops every table the up migration creates`() {
        val up = resource("/db/migration/V6__founder_recovery.sql")
        val created = Regex("""create table if not exists (glosso\.[a-z_]+)""", RegexOption.IGNORE_CASE)
            .findAll(up)
            .map { it.groupValues[1].lowercase() }
            .toSet()
        val dropped = Regex("""drop table if exists (glosso\.[a-z_]+)""", RegexOption.IGNORE_CASE)
            .findAll(resource("/db/migration/V6__founder_recovery.down.sql"))
            .map { it.groupValues[1].lowercase() }
            .toSet()

        assertTrue(created.isNotEmpty(), "no create table statements found")
        assertEquals(created, dropped)
    }

    @Test
    fun `the runner discovers the recovery migration without a hardcoded list`() {
        val migration = SchemaMigrator.migrations.single { it.version == "6" }
        assertEquals("/db/migration/V6__founder_recovery.sql", migration.resource)
        assertNotNull(
            SchemaMigrationTest::class.java.getResource(migration.resource),
            "migration resource is not on the classpath",
        )
        assertTrue(
            SchemaMigrator.migrations.none { it.resource.endsWith(".down.sql") },
            "down migrations must never be applied by the runner",
        )
    }

    @Test
    fun `the drop-recovery migration removes the one-time code table`() {
        val sql = resource("/db/migration/V8__drop_recovery_codes.sql").lowercase()
        assertTrue(
            sql.contains("drop table if exists glosso.recovery_codes"),
            "V8 must drop the recovery_codes table",
        )
        val down = resource("/db/migration/V8__drop_recovery_codes.down.sql").lowercase()
        assertTrue(
            down.contains("create table if not exists glosso.recovery_codes"),
            "the V8 down migration must recreate the table",
        )
    }

    @Test
    fun `the runner discovers the drop-recovery migration without a hardcoded list`() {
        val migration = SchemaMigrator.migrations.single { it.version == "8" }
        assertEquals("/db/migration/V8__drop_recovery_codes.sql", migration.resource)
        assertNotNull(
            SchemaMigrationTest::class.java.getResource(migration.resource),
            "migration resource is not on the classpath",
        )
        assertTrue(
            SchemaMigrator.migrations.none { it.resource.endsWith(".down.sql") },
            "down migrations must never be applied by the runner",
        )
    }
}
