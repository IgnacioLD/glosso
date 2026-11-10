package com.glosso.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dateWithTimeIntervalSince1970

actual fun appFiles(context: PlatformContext): AppFiles = IosAppFiles

private object IosAppFiles : AppFiles {
    override val fileSystem: FileSystem = FileSystem.SYSTEM
    override val filesDir: Path by lazy { directory(NSApplicationSupportDirectory) }
    override val cacheDir: Path by lazy { directory(NSCachesDirectory) }
    override val noBackupDir: Path by lazy { noBackup(filesDir / "no-backup") }

    private fun directory(kind: ULong): Path {
        val path = NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).first() as String
        return path.toPath().also { fileSystem.createDirectories(it) }
    }

    /** Created on first use and flagged so iCloud and device backups skip it. */
    @OptIn(ExperimentalForeignApi::class)
    private fun noBackup(path: Path): Path {
        fileSystem.createDirectories(path)
        NSURL.fileURLWithPath(path.toString(), isDirectory = true)
            .setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
        return path
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun setLastModified(path: Path, epochMillis: Long): Boolean =
    NSFileManager.defaultManager.setAttributes(
        mapOf<Any?, Any?>(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)),
        ofItemAtPath = path.toString(),
        error = null,
    )
