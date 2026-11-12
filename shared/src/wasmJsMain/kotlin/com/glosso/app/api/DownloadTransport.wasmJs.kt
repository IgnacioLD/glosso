package com.glosso.app.api

import okio.IOException
import okio.Path

// The web build keeps no files between visits, so it offers no offline
// downloads (AppConfig.offlineDownloads) and nothing reaches this.
actual suspend fun GlossoApi.downloadToPath(
    url: String,
    target: Path,
    expectedBytes: Long?,
    expectedSha256: String?,
    onProgress: (Long, Long?) -> Unit,
): Unit = throw IOException("No downloads in the web build")
