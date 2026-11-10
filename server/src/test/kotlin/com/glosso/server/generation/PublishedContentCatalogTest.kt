package com.glosso.server.generation

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishedContentCatalogTest {

    private fun assetsWithContent(): File {
        val dir = Files.createTempDirectory("glosso-catalog-assets").toFile()
        val db = File(dir, "content.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "create table content (id text primary key, kind text, language text, " +
                        "level text, theme text, text text, data text)",
                )
                st.execute("insert into content values ('a','DRILL','en','A1','t','x'," +
                    "'{\"target\":\"θ\",\"ruleId\":\"en.th.stopping.t\"}')")
                st.execute("insert into content values ('b','DRILL','en','A1','t','x'," +
                    "'{\"targets\":[\"θ\",\"ð\"]}')")
                st.execute("insert into content values ('c','DRILL','en','B1','t','x'," +
                    "'{\"target\":\"θ\"}')")
                st.execute("insert into content values ('d','DRILL','en','A1','t','x'," +
                    "'{\"target\":\"s\"}')")
                st.execute("insert into content values ('e','STORY','en','A1','t','x'," +
                    "'{\"title\":\"A Morning\"}')")
            }
        }
        return dir
    }

    private fun key(
        cefr: String,
        phoneme: String?,
        errorPattern: String? = null,
        position: String? = null,
    ) = NeedClassKey("en", cefr, SKILL_PRONUNCIATION, phoneme, errorPattern, null, position)

    @Test
    fun `existing content is counted per need class`() {
        val dir = assetsWithContent()
        val catalog = PublishedContentCatalog.fromAssets(dir, listOf("en"))

        assertEquals(2, catalog.counts(key("A1", "θ")).existing)
        assertEquals(1, catalog.counts(key("A1", "θ", errorPattern = "en.th.stopping.t")).existing)
        assertEquals(1, catalog.counts(key("B1", "θ")).existing)
        assertEquals(1, catalog.counts(key("A1", "s")).existing)
    }

    @Test
    fun `content with no target does not cover a pronunciation need`() {
        val dir = assetsWithContent()
        val catalog = PublishedContentCatalog.fromAssets(dir, listOf("en"))

        assertEquals(0, catalog.counts(key("A1", "z")).existing)
    }

    @Test
    fun `a missing database contributes nothing`() {
        val dir = Files.createTempDirectory("glosso-catalog-empty").toFile()
        val catalog = PublishedContentCatalog.fromAssets(dir, listOf("en"))

        assertEquals(ContentCounts(0, 0), catalog.counts(key("A1", "θ")))
    }
}
