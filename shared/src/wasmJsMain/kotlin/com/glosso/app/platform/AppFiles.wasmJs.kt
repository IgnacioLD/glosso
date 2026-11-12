package com.glosso.app.platform

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

actual fun appFiles(context: PlatformContext): AppFiles = WebAppFiles

/**
 * A browser gives a page no synchronous file system, so files live in memory for
 * the visit: takes, the HTTP cache and the analytics queue. Nothing here has to
 * outlive a reload, since the web build downloads nothing for offline use
 * (`AppConfig.offlineDownloads`). Settings and progress are in [keyValueStore].
 * shortcut: in memory, move to OPFS if the web build ever works offline.
 */
private object WebAppFiles : AppFiles {
    override val fileSystem: FileSystem = FakeFileSystem()
    override val filesDir: Path = directory("/files")
    override val cacheDir: Path = directory("/cache")
    override val noBackupDir: Path = directory("/no-backup")

    private fun directory(path: String): Path = path.toPath().also { fileSystem.createDirectories(it) }
}

// FakeFileSystem keeps its own clock; the cache's LRU order then follows writes.
actual fun setLastModified(path: Path, epochMillis: Long): Boolean = false
