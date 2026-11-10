package com.glosso.server.generation

import java.net.JarURLConnection
import java.net.URL
import java.sql.Connection
import javax.sql.DataSource

/**
 * Minimal migration runner: the repo uses no ORM and we are not adding Flyway or
 * Liquibase for the control plane. Each migration is a versioned SQL resource,
 * applied once and recorded in `glosso.schema_migrations`.
 *
 * Migrations are discovered from the classpath (`/db/migration/V<version>__<name>.sql`)
 * and applied in version order, so a new file is picked up automatically instead
 * of having to be registered by hand. The `.down.sql` companions are never
 * discovered: they are applied manually (see `V1__control_plane.down.sql`).
 *
 * Migrations are written to be idempotent (`if not exists`), so re-running after
 * a partial failure is safe.
 */
object SchemaMigrator {

    data class Migration(val version: String, val name: String, val resource: String)

    private const val RESOURCE_DIR = "db/migration"

    private val FILE_PATTERN = Regex("""^V(\d+)__([A-Za-z0-9_]+)\.sql$""")

    val migrations: List<Migration> = discover()

    /** Applies every migration not yet recorded, in version order. */
    fun migrate(dataSource: DataSource, log: (String) -> Unit = {}): List<String> {
        dataSource.connection.use { connection ->
            ensureRegistry(connection)
            val applied = appliedVersions(connection)
            val freshly = ArrayList<String>()
            for (migration in migrations.sortedBy { it.version.toIntOrNull() ?: Int.MAX_VALUE }) {
                if (migration.version in applied) continue
                val sql = readResource(migration.resource)
                connection.createStatement().use { it.execute(sql) }
                connection.prepareStatement(
                    "insert into glosso.schema_migrations (version, name) values (?, ?)",
                ).use { statement ->
                    statement.setString(1, migration.version)
                    statement.setString(2, migration.name)
                    statement.executeUpdate()
                }
                freshly += migration.version
                log("Applied migration ${migration.version} (${migration.name})")
            }
            return freshly
        }
    }

    /**
     * Scans the classpath for versioned migration resources. Works both from the
     * build directory (development, tests) and from a packaged jar. Throws when
     * nothing is found, so a broken classpath can never silently skip migrations.
     */
    private fun discover(): List<Migration> {
        val resources = linkedSetOf<String>()
        val urls = SchemaMigrator::class.java.classLoader.getResources(RESOURCE_DIR)
        while (urls.hasMoreElements()) {
            collect(urls.nextElement(), resources)
        }
        if (resources.isEmpty()) {
            SchemaMigrator::class.java.protectionDomain?.codeSource?.location?.let { collect(it, resources) }
        }
        check(resources.isNotEmpty()) {
            "No migration resources found under /$RESOURCE_DIR; is the server resource dir on the classpath?"
        }
        return resources.mapNotNull { path ->
            val fileName = path.substringAfterLast('/')
            val match = FILE_PATTERN.matchEntire(fileName) ?: return@mapNotNull null
            Migration(
                version = match.groupValues[1],
                name = match.groupValues[2],
                resource = "/$RESOURCE_DIR/$fileName",
            )
        }
    }

    private fun collect(url: URL, into: MutableSet<String>) {
        when (url.protocol) {
            "file" -> {
                val dir = runCatching { java.io.File(url.toURI()) }.getOrNull() ?: return
                dir.listFiles()?.forEach { into += "$RESOURCE_DIR/${it.name}" }
            }
            "jar" -> {
                val jar = runCatching { (url.openConnection() as JarURLConnection).jarFile }.getOrNull() ?: return
                jar.use { file ->
                    file.entries().asSequence()
                        .map { it.name }
                        .filter { it.startsWith("$RESOURCE_DIR/") && !it.endsWith("/") }
                        .forEach { into += it }
                }
            }
        }
    }

    private fun ensureRegistry(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("create schema if not exists glosso")
            statement.execute(
                """
                create table if not exists glosso.schema_migrations (
                    version    text primary key,
                    name       text not null,
                    applied_at timestamptz not null default now()
                )
                """.trimIndent(),
            )
        }
    }

    private fun appliedVersions(connection: Connection): Set<String> {
        connection.createStatement().use { statement ->
            statement.executeQuery("select version from glosso.schema_migrations").use { rs ->
                val versions = HashSet<String>()
                while (rs.next()) versions += rs.getString(1)
                return versions
            }
        }
    }

    private fun readResource(path: String): String =
        SchemaMigrator::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Missing migration resource $path")
}
