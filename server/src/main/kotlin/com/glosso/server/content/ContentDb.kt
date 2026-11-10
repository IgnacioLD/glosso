package com.glosso.server.content

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import kotlinx.serialization.json.Json
import com.glosso.engine.model.DialogueModel
import com.glosso.engine.model.DrillModel
import com.glosso.engine.model.WordModel

private val logger = KotlinLogging.logger("ContentDb")

/**
 * Reads the content DB the pipeline produces (SQLite). One generic `content`
 * table holds every kind; `data` is the full item JSON with the reference IPA and
 * audio paths already baked in. This is the shipping format: one file the app can
 * download and read offline. Returns null when there is no DB, so the server can
 * fall back to the JSON banks.
 */
object ContentDb {

    private val json = Json { ignoreUnknownKeys = true }

    fun drills(file: File): List<DrillModel>? =
        query(file, kind = "DRILL")?.mapNotNull { data ->
            runCatching { json.decodeFromString(DrillModel.serializer(), data) }
                .onFailure { logger.warn(it) { "Ignoring unreadable drill row" } }
                .getOrNull()
        }

    /**
     * The published accent references, keyed by the sentence they read: every
     * scorable sentence of the catalogue (drill, word, dialogue turn) and every
     * accent wording of one. The scorer looks a take's text up here when the
     * client did not send the references itself, which is every app published
     * before the content carried them. Empty when there is no DB or the content
     * predates accent references.
     */
    fun accentReferences(file: File): Map<String, Map<String, String>> {
        val table = HashMap<String, Map<String, String>>()
        fun add(text: String, references: Map<String, String>) {
            if (text.isNotBlank() && references.isNotEmpty()) table[text.trim()] = references
        }
        fun <T> rows(kind: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
            query(file, kind = kind).orEmpty().mapNotNull { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
        for (drill in rows("DRILL", DrillModel.serializer())) {
            add(drill.text, drill.accentIpa)
            drill.wording.values.forEach { add(it.text, it.accentIpa) }
        }
        for (word in rows("WORD", WordModel.serializer())) {
            add(word.lemma, word.accentIpa)
            word.wording.values.forEach { add(it.text, it.accentIpa) }
        }
        for (dialogue in rows("DIALOGUE", DialogueModel.serializer())) {
            for (turn in dialogue.turns) {
                add(turn.text, turn.accentIpa)
                turn.wording.values.forEach { add(it.text, it.accentIpa) }
            }
        }
        return table
    }

    /**
     * Distinct, non-blank study levels across every kind in the catalog, sorted.
     * The manifest exposes these so the app's level picker follows the content
     * instead of a hardcoded list.
     */
    fun levels(file: File): List<String> = distinct(file, "level")

    /** Distinct, non-blank themes across every kind, sorted. */
    fun themes(file: File): List<String> = distinct(file, "theme")

    private fun distinct(file: File, column: String): List<String> {
        // `column` is never user input; guard anyway so the SQL stays static.
        if (column != "level" && column != "theme") return emptyList()
        if (!file.isFile) return emptyList()
        return try {
            ReadOnlySqlite.open(file).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "select distinct $column from content " +
                            "where $column is not null and $column != '' order by $column",
                    ).use { rs ->
                        val values = ArrayList<String>()
                        while (rs.next()) values += rs.getString(1)
                        values
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not read $column from content DB at ${file.path}" }
            emptyList()
        }
    }

    /** Raw item JSON for a kind, for endpoints that pass content through untouched. */
    fun raw(
        file: File,
        kind: String,
        theme: String? = null,
        level: String? = null,
        ref: String? = null,
        ids: Collection<String>? = null,
        lemmas: Collection<String>? = null,
        limit: Int = 100,
    ): List<String>? = query(file, kind, theme, level, ref, ids, lemmas, limit)

    private fun query(
        file: File,
        kind: String,
        theme: String? = null,
        level: String? = null,
        ref: String? = null,
        ids: Collection<String>? = null,
        lemmas: Collection<String>? = null,
        limit: Int = 10_000,
    ): List<String>? {
        if (!file.isFile) return null
        val idList = ids?.filter { it.isNotBlank() }.orEmpty()
        // Words are looked up by lemma for pronunciation feedback; the field only
        // exists on WORD rows, so an empty or unknown lemma matches nothing.
        val lemmaList = lemmas?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        return try {
            ReadOnlySqlite.open(file).use { conn ->
                val sql = buildString {
                    append("select data from content where kind = ?")
                    if (theme != null) append(" and theme = ?")
                    if (level != null) append(" and level = ?")
                    if (ref != null) append(" and ref = ?")
                    if (idList.isNotEmpty()) append(" and id in (${idList.joinToString(",") { "?" }})")
                    if (lemmaList.isNotEmpty()) {
                        append(" and lower(json_extract(data, '$.lemma'))")
                        append(" in (${lemmaList.joinToString(",") { "?" }})")
                    }
                    append(" limit ?")
                }
                conn.prepareStatement(sql).use { ps ->
                    var i = 1
                    ps.setString(i++, kind)
                    if (theme != null) ps.setString(i++, theme)
                    if (level != null) ps.setString(i++, level)
                    if (ref != null) ps.setString(i++, ref)
                    idList.forEach { ps.setString(i++, it) }
                    lemmaList.forEach { ps.setString(i++, it) }
                    ps.setInt(i, limit)
                    ps.executeQuery().use { rs ->
                        val rows = ArrayList<String>()
                        while (rs.next()) rows += rs.getString("data")
                        rows
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Could not read content DB at ${file.path}" }
            null
        }
    }
}
