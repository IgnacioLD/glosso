package com.glosso.server.content

import com.glosso.server.module
import com.glosso.server.generation.NeedClassKey
import com.glosso.server.generation.PublishedContentCatalog
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the fix that keeps the server able to read its SQLite catalog on a
 * read-only filesystem (container `read_only: true` + assets `/assets:ro`).
 *
 * The default `jdbc:sqlite:` opens read-write and dies on a read-only mount;
 * [ReadOnlySqlite] opens `mode=ro&immutable=1`. These tests prove the read path
 * works and, crucially, that the connection really cannot write (so it never
 * needs a journal, WAL/SHM or temp file next to the DB).
 */
class ReadOnlyContentDbTest {

    private fun createCatalog(dir: File): File {
        val db = File(dir, "content.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "create table content (id text primary key, kind text not null, " +
                        "language text not null, level text, theme text, text text not null, " +
                        "ref text, data text not null)",
                )
                st.execute("create table theme (id text primary key, labels text)")
                st.execute(
                    "insert into content values " +
                        "('w1','WORD','en','A1','animals','cat','kæt','{\"target\":\"k\"}')",
                )
                st.execute(
                    "insert into content values " +
                        "('d1','DRILL','en','A2','animals','dog','dɒɡ','{\"target\":\"d\"}')",
                )
                st.execute("insert into theme values ('animals','{\"en\":\"Animals\"}')")
            }
        }
        return db
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    @Test
    fun `reads levels, themes and raw rows through the read-only connection`() {
        val db = createCatalog(tempDir("glosso-readonly"))

        assertEquals(listOf("A1", "A2"), ContentDb.levels(db))
        assertEquals(listOf("animals"), ContentDb.themes(db))
        assertEquals(1, ContentDb.raw(db, kind = "WORD")?.size)
    }

    @Test
    fun `the catalog connection cannot write`() {
        val db = createCatalog(tempDir("glosso-readonly-write"))

        ReadOnlySqlite.open(db).use { conn ->
            assertFailsWith<Exception> {
                conn.createStatement().use { it.execute("delete from content") }
            }
            assertFailsWith<Exception> {
                conn.createStatement().use {
                    it.execute("insert into content values ('x','WORD','en','A1',null,'x',null,'{}')")
                }
            }
        }
        // Nothing was touched.
        assertEquals(listOf("A1", "A2"), ContentDb.levels(db))
    }

    @Test
    fun `reads from a read-only directory like the container mount`() {
        val dir = tempDir("glosso-readonly-mount")
        val db = createCatalog(dir)
        try {
            dir.setWritable(false, false)
            assertTrue(!dir.canWrite(), "la prueba necesita un directorio sin permiso de escritura")
            assertEquals(listOf("A1", "A2"), ContentDb.levels(db))
        } finally {
            dir.setWritable(true, false)
        }
    }

    @Test
    fun `the running server loads and serves the catalog from the read-only db`() = testApplication {
        val assets = tempDir("glosso-readonly-server")
        val langDir = File(assets, "content/en").apply { mkdirs() }
        createCatalog(langDir)
        application { module(assetsDir = assets, adminToken = null, analytics = null) }

        val manifest = client.get("/v1/content/manifest?language=en")
        assertEquals(HttpStatusCode.OK, manifest.status)
        assertTrue(manifest.bodyAsText().contains("A1"), "el manifiesto no trae el nivel: ${manifest.bodyAsText()}")

        val words = client.get("/v1/content/WORD?language=en")
        assertEquals(HttpStatusCode.OK, words.status)
        assertTrue(words.bodyAsText().contains("target"), "el catalogo no devolvio filas: ${words.bodyAsText()}")
    }

    @Test
    fun `the published catalog still measures content`() {
        val db = createCatalog(tempDir("glosso-readonly-catalog"))
        val catalog = PublishedContentCatalog.fromContentDbs(mapOf("en" to db))

        val hit = catalog.counts(NeedClassKey(language = "en", cefr = "A1", skill = "pronunciation", phoneme = "k"))
        val miss = catalog.counts(NeedClassKey(language = "en", cefr = "B1", skill = "pronunciation", phoneme = "k"))
        assertEquals(1, hit.existing)
        assertEquals(0, miss.existing)
    }
}
