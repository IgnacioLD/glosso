package com.glosso.server.content

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Opens a shipped SQLite catalog (`content.db`) strictly read-only and
 * immutable, so the server never writes next to it: no rollback journal, no
 * WAL/SHM and no temp files in the DB directory. The content is published
 * out-of-band and replaced only on redeploy, so `immutable` is safe.
 *
 * This is what lets the catalog live on a read-only mount (the container runs
 * `read_only: true` and assets are mounted `/assets:ro`): a default
 * `jdbc:sqlite:` connection opens read-write and dies on a read-only
 * filesystem. `temp_store=memory` removes the last filesystem dependency for
 * queries that sort or use `json_extract`.
 *
 * See docs/OWASP-BACKEND.md and the deployment notes in the PR.
 */
internal object ReadOnlySqlite {

    fun open(file: File): Connection {
        // `File.toURI()` yields `file:/abs/path`; SQLite reads it as a URI
        // filename (percent-encoded). `immutable=1` skips all locking.
        val url = "jdbc:sqlite:${file.toURI()}?mode=ro&immutable=1"
        val connection = DriverManager.getConnection(url)
        connection.createStatement().use { it.execute("pragma temp_store = memory") }
        return connection
    }
}
