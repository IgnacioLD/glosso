package com.glosso.app

import com.glosso.engine.model.ArticleModel
import com.glosso.engine.model.DialogueModel
import com.glosso.engine.model.DrillModel
import com.glosso.engine.model.ExerciseModel
import com.glosso.engine.model.LessonModel
import com.glosso.engine.model.StoryModel
import com.glosso.engine.model.WordModel
import com.glosso.engine.model.forAccent
import kotlinx.serialization.Serializable

/** Everything one lesson needs to run with no network, stored as one file. */
@Serializable
data class OfflineBundle(
    val lesson: LessonModel,
    val dialogue: DialogueModel? = null,
    val story: StoryModel? = null,
    val article: ArticleModel? = null,
    val words: List<WordModel> = emptyList(),
    val exercises: List<ExerciseModel> = emptyList(),
    val drills: List<DrillModel> = emptyList(),
) {
    val id: String get() = lesson.id

    /** The pack as [accent] words it; see [com.glosso.engine.model.AccentWording]. */
    fun forAccent(accent: String, authored: String): OfflineBundle = copy(
        lesson = lesson.forAccent(accent),
        exercises = exercises.map { it.forAccent(accent) },
        dialogue = dialogue?.forAccent(accent, authored),
        story = story?.forAccent(accent),
        article = article?.forAccent(accent),
        words = words.map { it.forAccent(accent, authored) },
        drills = drills.map { it.forAccent(accent, authored) },
    )

    /** Every asset path this bundle references (audio and artwork), de-duplicated. */
    fun assetPaths(): List<String> {
        val paths = LinkedHashSet<String>()
        dialogue?.let { d ->
            d.image?.let { paths += it }
            d.turns.forEach { turn -> paths += turn.audio.values }
        }
        story?.let { s ->
            s.image?.let { paths += it }
            s.paragraphAudio.forEach { paths += it.values }
        }
        article?.let { a ->
            a.image?.let { paths += it }
            a.paragraphAudio.forEach { paths += it.values }
        }
        words.forEach { w -> paths += w.audio.values }
        exercises.forEach { e -> e.image?.let { paths += it } }
        drills.forEach { d -> paths += d.audio.values }
        return paths.filter { it.isNotBlank() }
    }
}

/** One downloaded lesson as the index sees it, without carrying the content. */
@Serializable
data class OfflineMeta(
    val id: String,
    val title: String,
    val theme: String,
    val level: String,
    val bytes: Long = 0,
    val assets: List<String> = emptyList(),
    val downloadedAt: Long = 0,
    /**
     * Content version the assets were fetched for. An entry from an older
     * catalogue is stale: it is not served and can be re-downloaded. Defaults to
     * empty, so an index written before versioning counts as stale.
     */
    val contentVersion: String = "",
)

@Serializable
data class OfflineIndex(
    val lessons: Map<String, OfflineMeta> = emptyMap(),
)

/**
 * The downloads still usable for [version], oldest first. A blank version (the
 * catalog is not known yet, e.g. a cold offline start) or a pack stamped with a
 * different version is not usable: it is offered for download again instead of
 * silently playing stale audio. One place, so every surface agrees on what
 * "downloaded" means.
 */
fun OfflineIndex.usableLessons(version: String): List<OfflineMeta> =
    lessons.values
        .filter { version.isNotBlank() && it.contentVersion == version }
        .sortedBy { it.downloadedAt }

/** What the UI needs to draw the download controls and the storage figure. */
data class OfflineState(
    val loaded: Boolean = false,
    val metas: List<OfflineMeta> = emptyList(),
    val downloadingId: String? = null,
    val progress: Float = 0f,
    val error: String? = null,
    /** The content version these downloads belong to; empty until known. */
    val contentVersion: String = "",
) {
    val totalBytes: Long get() = metas.sumOf { it.bytes }
    val downloadedIds: Set<String> get() = metas.map { it.id }.toSet()
    fun meta(id: String) = metas.firstOrNull { it.id == id }
}
