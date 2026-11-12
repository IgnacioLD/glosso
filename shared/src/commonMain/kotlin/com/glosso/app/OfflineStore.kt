package com.glosso.app

import com.glosso.app.api.GlossoApi
import com.glosso.app.api.downloadToPath
import com.glosso.app.download.DownloadFailure
import com.glosso.app.download.DownloadFile
import com.glosso.app.download.DownloadIds
import com.glosso.app.download.DownloadItem
import com.glosso.app.download.DownloadItemState
import com.glosso.app.download.DownloadKind
import com.glosso.app.download.DownloadOutcome
import com.glosso.app.download.DownloadPriority
import com.glosso.app.download.DownloadStatus
import com.glosso.app.download.Downloader
import com.glosso.app.platform.AppFiles
import com.glosso.app.platform.Lock
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.appFiles
import com.glosso.app.platform.currentTimeMillis
import com.glosso.app.platform.ioDispatcher
import com.glosso.app.platform.logWarning
import com.glosso.app.resources.*
import com.glosso.engine.model.localized
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import org.jetbrains.compose.resources.getString

/**
 * Optional offline packs, kept in the app's private storage. Cloud-first stays
 * the default: this only holds what the learner explicitly downloaded, so a
 * lesson can be opened, read, listened to and answered with no connection. The
 * pronunciation score itself is still a cloud call; that step degrades with a
 * clear message when there is no network.
 *
 * The on-disk layout is fixed, so downloads survive app updates:
 * `filesDir/offline/{index.json, lessons/<id>.json, assets/<version>/<path>,
 * pending/<id>.json|.version}` ([OfflineLayout]).
 */
object OfflineStore {

    /** Size lookups are small and many; a handful in flight hides the latency. */
    private const val ESTIMATE_PARALLELISM = 6

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private val cache = LockedMap<String, OfflineBundle>()

    /**
     * Per-asset sizes learned by [estimate] (or from a file already on disk).
     * The downloader verifies against these, so integrity does not rest only on
     * the Content-Length of the very response being checked.
     */
    private val assetSizes = LockedMap<String, Long>()

    /**
     * A lesson's total size, computed once per content version. Opening the
     * download sheet again must not recompute it, with or without the manifest.
     */
    private val estimateCache = LockedMap<String, Long>()

    /** The app's download queue, wired at startup (`DownloadManager` on Android). */
    lateinit var downloader: Downloader

    private lateinit var layout: OfflineLayout
    private lateinit var fileSystem: FileSystem

    /** Assets live under a per-version folder, so an old catalogue is unreachable. */
    @Volatile
    private var contentVersion: String = ""

    private val _state = MutableStateFlow(OfflineState())
    val state: StateFlow<OfflineState> = _state.asStateFlow()

    fun init(context: PlatformContext) = init(appFiles(context))

    fun init(files: AppFiles) {
        if (::layout.isInitialized) return
        fileSystem = files.fileSystem
        layout = OfflineLayout(files.filesDir)
        fileSystem.createDirectories(layout.assets)
        fileSystem.createDirectories(layout.pending)
        scope.launch { reload() }
    }

    /**
     * The content version in use. Downloads are scoped to it and anything from an
     * older one is dropped, so an update never serves a stale pack. Called when
     * [GlossoApi] learns the served version (once at startup and on every change).
     */
    fun setContentVersion(version: String) {
        if (version == contentVersion) return
        contentVersion = version
        assetSizes.clear()
        estimateCache.clear()
        if (!::layout.isInitialized) return
        scope.launch {
            if (version.isNotBlank()) pruneOtherVersions(version)
            reload()
        }
    }

    private fun pruneOtherVersions(version: String) {
        fileSystem.listOrNull(layout.assets)?.forEach { child ->
            if (isDirectory(child) && child.name != version) runCatching { fileSystem.deleteRecursively(child) }
        }
    }

    private fun assetsDir(version: String): Path = layout.assetsFor(version)

    suspend fun reload() = withContext(ioDispatcher) {
        val index = readIndex()
        // Only downloads made for the current catalogue are usable; an older one
        // is offered for download again instead of silently playing old audio.
        val fresh = index.usableLessons(contentVersion)
        _state.value = _state.value.copy(
            loaded = true,
            contentVersion = contentVersion,
            metas = fresh,
        )
    }

    /** The downloaded lesson catalog, newest first, for the offline lesson list. */
    suspend fun bundles(): List<OfflineBundle> = withContext(ioDispatcher) {
        readIndex().usableLessons(contentVersion)
            .sortedByDescending { it.downloadedAt }
            .mapNotNull { bundle(it.id) }
    }

    suspend fun bundle(id: String): OfflineBundle? = withContext(ioDispatcher) {
        val stored = cache[id] ?: run {
            val file = bundleFile(id)
            if (!isFile(file)) return@withContext null
            runCatching { json.decodeFromString(OfflineBundle.serializer(), readText(file)) }
                .onSuccess { cache[id] = it }
                .getOrNull()
        }
        // A pack is stored as it was downloaded; it is read in the accent and the
        // interface language the learner has now, which may not be the ones it
        // was downloaded under.
        stored?.forAccent(VoicePreferences.accent, LanguagePreferences.current.defaultAccent)
            ?.let { pack ->
                val ui = UiLanguage.current.value
                pack.copy(lesson = pack.lesson.localized(ui), exercises = pack.exercises.map { it.localized(ui) })
            }
    }

    /**
     * The local file for a downloaded asset of the current content version, or
     * null when it is not held. `null` also covers an asset downloaded for an
     * older version, so the caller falls back to the remote (versioned) URL.
     */
    fun localAsset(path: String?): Path? {
        if (path.isNullOrBlank() || !isSafeAssetPath(path) ||
            !::layout.isInitialized || contentVersion.isBlank()
        ) return null
        val file = layout.asset(contentVersion, path)
        return file.takeIf { length(it) > 0 }
    }

    /** The bytes of [localAsset] for [path], or null when it is not held or cannot be read. */
    fun readLocalAsset(path: String?): ByteArray? {
        val file = localAsset(path) ?: return null
        return runCatching { fileSystem.read(file) { readByteArray() } }.getOrNull()
    }

    /**
     * An asset path must stay inside the assets directory: no absolute paths and
     * no `..` segments. The catalogue is ours, but a bad or tampered path must
     * never read or write outside the app's private storage.
     */
    private fun isSafeAssetPath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith("/") && path.split('/').none { it == ".." }

    /**
     * Approximate size of a bundle's assets in bytes, from the server's
     * Content-Length. Files already on disk count their local size without a
     * request, and the rest are asked for in parallel (bounded), so a lesson
     * with twenty small assets is not twenty round trips in a row. Null when the
     * server could not be reached, so the UI can say "needs connection" instead
     * of a fake number.
     */
    suspend fun estimate(bundle: OfflineBundle, api: GlossoApi): Long? = withContext(ioDispatcher) {
        // One manifest fetch, ever. Revalidating it on every size request would
        // be a network round trip for something already in hand.
        if (!api.isManifestLoaded) api.ensureAssetVersion()
        val version = api.contentVersion
        val cacheKey = estimateKey(bundle.id, version)
        estimateCache[cacheKey]?.let { return@withContext it }

        val paths = bundle.assetPaths().filter(::isSafeAssetPath)
        if (paths.isEmpty()) {
            estimateCache[cacheKey] = 0L
            return@withContext 0L
        }

        // Fast path: the manifest carries every asset's size, so the whole
        // lesson is added up locally with no network at all. Remember each size
        // too, so the downloader can verify what it fetches against it.
        api.manifestSizeOf(paths)?.let { total ->
            paths.forEach { path -> api.manifestAssetSize(path)?.let { assetSizes[path] = it } }
            estimateCache[cacheKey] = total
            return@withContext total
        }

        // Older server without sizes: probe only what we do not already know,
        // bounded, and remember each size and the lesson total so this happens
        // at most once per content version.
        val dir = assetsDir(version)
        val sizes = coroutineScope {
            val semaphore = Semaphore(ESTIMATE_PARALLELISM)
            paths.map { path ->
                async {
                    localSize(dir, path) ?: assetSizes[path] ?: semaphore.withPermit {
                        api.assetSize(path)?.also { assetSizes[path] = it }
                    }
                }
            }.awaitAll()
        }
        // Match the long-standing behaviour: sum whatever we could resolve, so a
        // flaky probe shows a size rather than nothing. Only a fully resolved
        // lesson is cached: a partial sum would undercount for good behind the
        // requestEstimate short-circuit. A partial one is recomputed next time,
        // and the per-asset cache means only the failed probes are retried, so
        // it self-corrects.
        val resolved = sizes.filterNotNull()
        if (resolved.isEmpty()) return@withContext null
        val total = resolved.sum()
        if (resolved.size == sizes.size) estimateCache[cacheKey] = total
        total
    }

    /** The cached size for [lessonId] at [version], or null when not computed. */
    fun cachedEstimate(lessonId: String, version: String): Long? =
        estimateCache[estimateKey(lessonId, version)]

    private fun estimateKey(lessonId: String, version: String): String = "$lessonId@$version"

    /** A downloaded asset's size, cached, so a probe is never repeated. */
    private fun localSize(dir: Path, path: String): Long? {
        val cached = assetSizes[path]
        if (cached != null) return cached
        val file = dir / path
        if (!isFile(file) || length(file) <= 0) return null
        assetSizes[path] = length(file)
        return length(file)
    }

    /**
     * Joins the background download queue for [bundle]: the files are fetched by
     * the shared service (resumable, parallel, verified) and, when they all
     * land, the bundle and its index entry are committed so the rest of the app
     * reads it exactly as before. [onProgress] is called with a 0..1 fraction.
     * Returns true only when the pack is complete and committed.
     */
    suspend fun download(
        bundle: OfflineBundle,
        api: GlossoApi,
        onProgress: (Float) -> Unit = {},
    ): Boolean = withContext(ioDispatcher) {
        val id = bundle.id
        _state.value = _state.value.copy(downloadingId = id, progress = 0f, error = null)
        try {
            // A pack is bound to the catalogue it was fetched from, so stamp it
            // with the served version and store its assets under that version.
            api.ensureAssetVersion()
            val version = api.contentVersion
            if (version.isBlank()) {
                _state.value = _state.value.copy(downloadingId = null, error = getString(Res.string.error_download))
                return@withContext false
            }
            val dir = assetsDir(version)
            stageBundle(bundle, version)
            val paths = bundle.assetPaths().filter(::isSafeAssetPath)
            // Seed the per-asset sizes from the manifest when it carries them, so
            // the downloader verifies against a published size and the progress
            // bar has its denominator from the first byte even when the size
            // sheet was never opened (a download started from the lesson player).
            paths.forEach { path ->
                if (assetSizes[path] == null) {
                    api.manifestAssetSize(path)?.let { assetSizes[path] = it }
                }
            }
            val files = paths.map { path ->
                val target = dir / path
                val url = api.assetUrl(path)
                DownloadFile(url = url, target = target, expectedBytes = assetSizes[path]) { report ->
                    api.downloadToPath(url, target, expectedBytes = assetSizes[path], onProgress = report)
                }
            }
            val outcome = downloader.enqueue(
                DownloadItem(
                    id = DownloadIds.lesson(id),
                    kind = DownloadKind.LESSON,
                    title = bundle.lesson.title,
                    priority = DownloadPriority.NORMAL,
                    files = files,
                ),
            ).await()
            onProgress(if (outcome == DownloadOutcome.DONE) 1f else _state.value.progress)
            if (outcome == DownloadOutcome.CANCELLED) {
                // A cancelled item leaves the queue's active list, so no queue
                // update clears it: without this the sheet kept saying
                // "Downloading (0%)" after Cancel. The staged pack goes too.
                discardStaged(id)
                if (_state.value.downloadingId == id) _state.value = _state.value.copy(downloadingId = null, progress = 0f)
            }
            outcome == DownloadOutcome.DONE
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The caller (a screen) went away; the download keeps going in the
            // service, so this is not a failure.
            throw e
        } catch (e: Exception) {
            logWarning("GlossoOffline", "download $id failed", e)
            discardStaged(id)
            _state.value = _state.value.copy(
                downloadingId = null,
                error = getString(Res.string.error_download),
            )
            false
        }
    }

    /**
     * Called by the [downloader] when every one of a lesson's files has landed:
     * the staged pack becomes a real one and the index is updated.
     */
    suspend fun commitDownload(id: String) = withContext(ioDispatcher) {
        val staged = pendingFile(id)
        if (!isFile(staged)) return@withContext
        val bundle = runCatching {
            json.decodeFromString(OfflineBundle.serializer(), readText(staged))
        }.getOrNull() ?: run { discardStaged(id); return@withContext }
        // The version the files were fetched for, not whatever is current now:
        // a catalogue change mid-download must not re-point the commit.
        val version = pendingVersionFile(id).takeIf { isFile(it) }?.let { readText(it) }?.trim()
            ?.takeIf { it.isNotBlank() } ?: contentVersion
        val dir = assetsDir(version)
        val paths = bundle.assetPaths()
        bundleFile(id).also { file -> file.parent?.let { fileSystem.createDirectories(it) } }
            .let { file -> writeText(file, json.encodeToString(OfflineBundle.serializer(), bundle)) }
        cache[id] = bundle
        val meta = OfflineMeta(
            id = id,
            title = bundle.lesson.title,
            theme = bundle.lesson.theme,
            level = bundle.lesson.level,
            bytes = paths.sumOf { length(dir / it) },
            assets = paths,
            downloadedAt = currentTimeMillis(),
            contentVersion = version,
        )
        val index = readIndex()
        writeIndex(index.copy(lessons = index.lessons + (id to meta)))
        discardStaged(id)
        val fresh = readIndex().usableLessons(contentVersion)
            .sortedByDescending { it.downloadedAt }
        _state.value = _state.value.copy(downloadingId = null, progress = 1f, metas = fresh, error = null)
    }

    suspend fun onDownloadFailed(id: String, failure: DownloadFailure) = withContext(ioDispatcher) {
        discardStaged(id)
        _state.value = _state.value.copy(downloadingId = null, error = getString(failure.message))
    }

    /** Mirrors the queue's state for the active lesson into the offline state. */
    fun onQueueState(item: DownloadItemState?) {
        if (item == null) return
        val lessonId = DownloadIds.lessonIdOf(item.id)
        val failure = item.failure?.takeIf { item.status == DownloadStatus.FAILED }
        _state.value = _state.value.copy(
            downloadingId = lessonId.takeIf { item.isActive },
            progress = item.progress,
            error = if (failure == null) null else _state.value.error,
        )
        // The words load through a suspend call; a failure is terminal, so the
        // message lands right after without racing a later progress update.
        if (failure != null) scope.launch { _state.value = _state.value.copy(error = getString(failure.message)) }
    }

    private fun stageBundle(bundle: OfflineBundle, version: String) {
        fileSystem.createDirectories(layout.pending)
        writeText(pendingFile(bundle.id), json.encodeToString(OfflineBundle.serializer(), bundle))
        writeText(pendingVersionFile(bundle.id), version)
    }

    private fun discardStaged(id: String) {
        delete(pendingFile(id))
        delete(pendingVersionFile(id))
    }

    private fun pendingFile(id: String): Path = layout.pendingBundle(id)

    private fun pendingVersionFile(id: String): Path = layout.pendingVersion(id)

    suspend fun delete(id: String) = withContext(ioDispatcher) {
        val index = readIndex()
        if (id !in index.lessons) return@withContext
        val removed = index.lessons[id]
        cache.remove(id)
        delete(bundleFile(id))
        val remaining = index.lessons - id
        val version = removed?.contentVersion.orEmpty()
        val stillUsed = remaining.values
            .filter { it.contentVersion == version }
            .flatMap { it.assets }
            .toSet()
        removed?.assets.orEmpty()
            .filter { it !in stillUsed }
            .forEach { path ->
                delete(assetsDir(version) / path)
                delete(assetsDir(version) / "$path.part")
                delete(assetsDir(version) / "$path.part.meta")
            }
        writeIndex(index.copy(lessons = remaining))
        val fresh = OfflineIndex(remaining).usableLessons(contentVersion)
            .sortedByDescending { it.downloadedAt }
        _state.value = _state.value.copy(metas = fresh)
    }

    /**
     * Keeps only the packs downloaded for [version], the content fingerprint the
     * manifest publishes, and deletes every other one. A blank [version] (no
     * manifest, or one without a fingerprint) deletes them all.
     */
    suspend fun keepOnly(version: String) = withContext(ioDispatcher) {
        if (version.isBlank()) return@withContext clear()
        readIndex().lessons.filterValues { it.contentVersion != version }.keys.forEach { delete(it) }
        pruneOtherVersions(version)
    }

    suspend fun clear() = withContext(ioDispatcher) {
        cache.clear()
        assetSizes.clear()
        estimateCache.clear()
        runCatching { fileSystem.deleteRecursively(layout.root) }
        fileSystem.createDirectories(layout.assets)
        fileSystem.createDirectories(layout.pending)
        writeIndex(OfflineIndex())
        _state.value = _state.value.copy(metas = emptyList(), downloadingId = null, progress = 0f)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun bundleFile(id: String): Path = layout.bundle(id)

    private fun readIndex(): OfflineIndex = runCatching {
        val file = layout.index
        if (isFile(file)) json.decodeFromString(OfflineIndex.serializer(), readText(file))
        else OfflineIndex()
    }.getOrDefault(OfflineIndex())

    private fun writeIndex(index: OfflineIndex) {
        fileSystem.createDirectories(layout.lessons)
        writeText(layout.index, json.encodeToString(OfflineIndex.serializer(), index))
    }

    // File helpers with java.io.File's forgiving semantics: a missing file has
    // length 0 and deleting one is a no-op.

    private fun isFile(path: Path): Boolean = fileSystem.metadataOrNull(path)?.isRegularFile == true

    private fun isDirectory(path: Path): Boolean = fileSystem.metadataOrNull(path)?.isDirectory == true

    private fun length(path: Path): Long = runCatching { fileSystem.metadataOrNull(path)?.size }.getOrNull() ?: 0L

    private fun readText(path: Path): String = fileSystem.read(path) { readUtf8() }

    private fun writeText(path: Path, text: String) = fileSystem.write(path) { writeUtf8(text) }

    private fun delete(path: Path) {
        runCatching { fileSystem.delete(path) }
    }
}

/**
 * Where offline packs live under the app's private files dir. Fixed: lessons and
 * audio downloaded by any earlier version are found here after an update.
 */
class OfflineLayout(filesDir: Path) {
    val root: Path = filesDir / "offline"
    val index: Path = root / "index.json"
    val lessons: Path = root / "lessons"
    val assets: Path = root / "assets"
    val pending: Path = root / "pending"

    fun bundle(id: String): Path = lessons / "$id.json"
    fun assetsFor(version: String): Path = assets / version
    fun asset(version: String, path: String): Path = assetsFor(version) / path
    fun pendingBundle(id: String): Path = pending / "$id.json"
    fun pendingVersion(id: String): Path = pending / "$id.version"
}

/** A small map safe to touch from several threads (a `ConcurrentHashMap` stand-in). */
internal class LockedMap<K, V> {
    private val lock = Lock()
    private val map = mutableMapOf<K, V>()

    operator fun get(key: K): V? = lock.withLock { map[key] }
    operator fun set(key: K, value: V) = lock.withLock { map[key] = value }
    fun remove(key: K): V? = lock.withLock { map.remove(key) }
    fun clear() = lock.withLock { map.clear() }
}
