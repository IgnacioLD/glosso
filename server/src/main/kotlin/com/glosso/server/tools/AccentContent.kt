package com.glosso.server.tools

import com.glosso.engine.model.AccentWording
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the publisher merges into the items about accents, read from the content
 * directory:
 *
 *  - `accents/<accent>.json`: the accent's own wording, a map from clip key
 *    (drill or word id, `<dialogueId>#t<turn>`, `<storyOrArticleId>#p<paragraph>`)
 *    to the text that accent says instead of the authored one. What is shown
 *    but not read aloud is keyed the same way: `<id>#title`, `<id>#objective`,
 *    `<dialogueId>#speaker:<name>`, `<wordId>#ex<i>` and, for an exercise,
 *    `#prompt`, `#answer`, `#instruction` and `#opt<i>`.
 *  - `accent-reference.json`: for every scorable sentence, its reading in the
 *    convention the acoustic model transcribes in, built by
 *    `tools/content/accent_reference.py`.
 *
 * Both are optional: a bank without them publishes exactly as before. When the
 * reference file exists it must cover every sentence, so a stale file (content
 * edited, tool not re-run) fails the publish instead of shipping a sentence with
 * no reference.
 */
internal class AccentContent(
    private val wordings: Map<String, Map<String, String>>,
    private val references: Map<String, Map<String, String>>,
) {
    private val used = HashSet<Pair<String, String>>()
    private val missing = LinkedHashSet<String>()

    /** The model-convention readings of [text], keyed by accent; empty without a reference file. */
    fun referenceOf(text: String): Map<String, String> {
        val sentence = text.trim()
        if (references.isEmpty() || sentence.isEmpty()) return emptyMap()
        return references.mapNotNull { (accent, table) ->
            val ipa = table[sentence]
            if (ipa == null) missing += "$accent: \"$sentence\""
            ipa?.let { accent to it }
        }.toMap()
    }

    /** Each accent's wording of the sentence at [key], with its own reference. */
    fun sentenceWording(key: String): Map<String, AccentWording> =
        wordings.mapNotNull { (accent, texts) ->
            val text = texts[key] ?: return@mapNotNull null
            used += accent to key
            accent to AccentWording(text = text, accentIpa = referenceOf(text))
        }.toMap()

    /** Each accent's paragraphs of the reading [id], the whole list, parallel to [paragraphs]. */
    fun paragraphs(id: String, paragraphs: List<String>): Map<String, List<String>> =
        wordings.mapNotNull { (accent, texts) ->
            val keys = paragraphs.indices.map { "$id#p$it" }
            if (keys.none { it in texts }) return@mapNotNull null
            keys.filter { it in texts }.forEach { used += accent to it }
            accent to keys.mapIndexed { i, key -> texts[key] ?: paragraphs[i] }
        }.toMap()

    /**
     * Each accent's wording of an item's display fields. [keys] maps the field
     * name the model uses ("title", "opt0") to the wording key that holds it.
     */
    fun fields(keys: Map<String, String>): Map<String, Map<String, String>> =
        wordings.mapNotNull { (accent, texts) ->
            val own = keys.mapNotNull { (field, key) ->
                val text = texts[key] ?: return@mapNotNull null
                used += accent to key
                field to text
            }.toMap()
            if (own.isEmpty()) null else accent to own
        }.toMap()

    /** Everything that must stop the publish: sentences without a reference, wordings of no item. */
    fun problems(): List<String> {
        val orphans = wordings.flatMap { (accent, texts) ->
            texts.keys.filter { (accent to it) !in used }.map { "$accent: wording key '$it' matches no item" }
        }
        return missing.map { "no accent reference for $it (run tools/content/accent_reference.py)" } + orphans
    }

    companion object {
        const val REFERENCE_FILE = "accent-reference.json"
        private val json = Json { ignoreUnknownKeys = true }

        val NONE = AccentContent(emptyMap(), emptyMap())

        fun load(contentDir: File): AccentContent {
            val wordings = File(contentDir, "accents").listFiles { f: File -> f.isFile && f.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?.associate { file ->
                    val doc = json.parseToJsonElement(file.readText()).jsonObject
                    val accent = doc["accent"]?.jsonPrimitive?.content ?: file.nameWithoutExtension
                    accent to doc["text"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }
                }.orEmpty()
            val reference = File(contentDir, REFERENCE_FILE).takeIf { it.isFile }
                ?.let { json.parseToJsonElement(it.readText()).jsonObject["references"]?.jsonObject }
                ?.mapValues { (_, table) -> table.jsonObject.mapValues { it.value.jsonPrimitive.content } }
                .orEmpty()
            return AccentContent(wordings, reference)
        }
    }
}
