package com.glosso.server.generation

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

/**
 * The shared PostgreSQL connection for the whole generation control plane:
 * profiles, needs, demand, content pool, jobs and TTS metadata. One pool, one
 * migration run, so the API, the scheduler and the worker do not each open their
 * own connection to the same database.
 *
 * It is optional like analytics: without `GLOSSO_DB_URL` (or if the database is
 * unreachable) it returns null, and the signal plane keeps serving.
 */
class ControlPlaneDatabase internal constructor(val dataSource: HikariDataSource) : AutoCloseable {

    val controlStore: ControlPlaneStore = JdbcControlPlaneStore(dataSource)

    override fun close() = dataSource.close()

    companion object {
        fun open(
            env: Map<String, String> = System.getenv(),
            log: (String) -> Unit = {},
        ): ControlPlaneDatabase? {
            val url = env["GLOSSO_DB_URL"]?.takeIf { it.isNotBlank() } ?: run {
                log("Control plane disabled: GLOSSO_DB_URL is not set")
                return null
            }
            return try {
                val config = HikariConfig().apply {
                    jdbcUrl = url
                    username = env["GLOSSO_DB_USER"] ?: "postgres"
                    password = env["GLOSSO_DB_PASSWORD"] ?: ""
                    maximumPoolSize = 4
                    minimumIdle = 1
                    connectionTimeout = 5_000
                    poolName = "glosso-control"
                }
                val dataSource = HikariDataSource(config)
                val applied = SchemaMigrator.migrate(dataSource, log)
                if (applied.isNotEmpty()) log("Control plane schema at version ${applied.last()}")
                log("Control plane ready")
                ControlPlaneDatabase(dataSource)
            } catch (e: Exception) {
                log("Control plane disabled: ${e.message}")
                null
            }
        }
    }
}
