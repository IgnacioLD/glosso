package com.glosso.app.platform

import java.io.File
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

actual fun appFiles(context: PlatformContext): AppFiles {
    val app = context.applicationContext ?: context
    return object : AppFiles {
        override val fileSystem: FileSystem = FileSystem.SYSTEM
        override val filesDir: Path get() = app.filesDir.toOkioPath()
        override val cacheDir: Path get() = app.cacheDir.toOkioPath()
        override val noBackupDir: Path get() = app.noBackupFilesDir.toOkioPath()
    }
}

actual fun setLastModified(path: Path, epochMillis: Long): Boolean =
    File(path.toString()).setLastModified(epochMillis)
