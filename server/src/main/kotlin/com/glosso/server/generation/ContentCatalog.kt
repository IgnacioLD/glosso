package com.glosso.server.generation

import io.github.oshai.kotlinlogging.KotlinLogging
import com.glosso.server.content.ReadOnlySqlite
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Existing and effective content pieces that can serve one Need Class. */
data class ContentCounts(val existing: Int, val effective: Int)

/** Read-only view of the published content, used to measure the gap. */
interface ContentCatalog {
    fun counts(key: NeedClassKey): ContentCounts
}

/** No content measured. Used when no catalog is available, so every gap is full. */
object EmptyContentCatalog : ContentCatalog {
    override fun counts(key: NeedClassKey): ContentCounts = ContentCounts(0, 0)
}

/**
 * Measures "contenido existente" against the real published pool: the SQLite
 * `content.db` the server already serves through the content routes. PostgreSQL
 * phase 2 will add the generated pool, and this catalog is where its count plugs
 * in.
 *
 * Until the effectiveness layer (phase 6) exists, every existing piece counts as
 * effective. The shape is deliberately a list of small records, not a live SQL
 * scan per class, so an aggregation over many classes does not re-read the file.
 */
class PublishedContentCatalog private constructor(private val items: List<CatalogItem>) : ContentCatalog {

    private data class CatalogItem(
        val language: String,
        val level: String,
        val targets: Set<String>,
        val ruleId: String?,
        val position: String?,
    )

    override fun counts(key: NeedClassKey): ContentCounts {
        val target = key.phoneme ?: return ContentCounts(0, 0)
        val matched = items.count { item ->
            item.language == key.language &&
                item.level == key.cefr &&
                target in item.targets &&
                (key.errorPattern == null || item.ruleId == key.errorPattern) &&
                (key.position == null || item.position == null || item.position == key.position)
        }
        return ContentCounts(existing = matched, effective = matched)
    }

    companion object {
        private val logger = KotlinLogging.logger("ContentCatalog")
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads `content/<code>/content.db` for each language, with the legacy
         * English layout (a flat `content.db` in the assets root) as fallback.
         * A missing or unreadable DB contributes nothing, never throws.
         */
        fun fromAssets(assetsDir: File, languages: List<String>): ContentCatalog {
            val files = languages.associateWith { code ->
                val scoped = File(assetsDir, "content/$code/content.db")
                if (scoped.isFile) scoped else File(assetsDir, "content.db")
            }
            return fromContentDbs(files)
        }

        fun fromContentDbs(files: Map<String, File>): ContentCatalog {
            val items = files.flatMap { (language, file) -> read(file, language) }
            return PublishedContentCatalog(items)
        }

        private fun read(file: File, fallbackLanguage: String): List<CatalogItem> {
            if (!file.isFile) return emptyList()
            return try {
                ReadOnlySqlite.open(file).use { conn ->
                    conn.createStatement().use { st ->
                        // No `language` column: the DB is already scoped to one
                        // language by its path, so the file's code is the source.
                        st.executeQuery("select level, data from content").use { rs ->
                            val rows = ArrayList<CatalogItem>()
                            while (rs.next()) {
                                val level = rs.getString("level")?.trim().orEmpty()
                                if (level.isEmpty()) continue
                                parse(rs.getString("data"), level, fallbackLanguage)?.let { rows += it }
                            }
                            rows
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Could not read content DB at ${file.path}" }
                emptyList()
            }
        }

        private fun parse(data: String?, level: String, language: String?): CatalogItem? {
            val objectValue = runCatching {
                json.parseToJsonElement(data.orEmpty()).jsonObject
            }.getOrNull() ?: return null

            val targets = LinkedHashSet<String>()
            (objectValue["target"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { targets += it }
            (objectValue["targets"] as? JsonArray)?.forEach { element ->
                (element as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { targets += it }
            }
            if (targets.isEmpty()) return null

            return CatalogItem(
                language = language?.trim().orEmpty(),
                level = level,
                targets = targets,
                ruleId = (objectValue["ruleId"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
                position = (objectValue["position"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
            )
        }
    }
}
