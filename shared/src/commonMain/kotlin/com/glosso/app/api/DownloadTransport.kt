package com.glosso.app.api

import okio.Path

/**
 * Streams [url] to [target] with resume and verification; see the Android
 * `downloadTo`. [onProgress] reports bytes written and the total, if known.
 */
expect suspend fun GlossoApi.downloadToPath(
    url: String,
    target: Path,
    expectedBytes: Long? = null,
    expectedSha256: String? = null,
    onProgress: (Long, Long?) -> Unit = { _, _ -> },
)
