package com.glosso.app.platform

import okio.FileSystem
import okio.Path

/**
 * The app's private directories. On Android they are the same `Context` dirs the
 * app has always used, so files written by earlier versions are found again.
 */
interface AppFiles {
    val fileSystem: FileSystem

    /** Private, backed up: Android `filesDir`; iOS `Library/Application Support`. */
    val filesDir: Path

    /** Disposable, the system may clear it: Android `cacheDir`; iOS `Library/Caches`. */
    val cacheDir: Path

    /**
     * Private and never backed up, for big downloads that can be fetched again:
     * Android `noBackupFilesDir`; iOS a folder in Application Support excluded
     * from backup.
     */
    val noBackupDir: Path
}

expect fun appFiles(context: PlatformContext): AppFiles

/**
 * Sets [path]'s modification time; false when it could not (e.g. it does not
 * exist). okio can read the time but not write it.
 */
expect fun setLastModified(path: Path, epochMillis: Long): Boolean
