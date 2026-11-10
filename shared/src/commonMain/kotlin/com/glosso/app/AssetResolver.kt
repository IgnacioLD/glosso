package com.glosso.app

import com.glosso.app.api.GlossoApi
import com.glosso.app.audio.AudioSource
import okio.Path

/**
 * The single place that decides where a content asset comes from: the downloaded
 * file of the current content version when it is held, otherwise the remote
 * (versioned) URL. Every screen resolves images and audio through here, so
 * cache-busting and offline invalidation behave the same everywhere: when the
 * served content version changes, [OfflineStore.localAsset] stops returning the
 * old file and the remote URL carries the new `?v=`.
 */
object AssetResolver {

    fun local(path: String?): Path? = OfflineStore.localAsset(path)

    /** The bytes of the downloaded file for [path], or null when it is not held. */
    fun localBytes(path: String?): ByteArray? = OfflineStore.readLocalAsset(path)

    /** A Coil model for [path]: the local file, the remote URL, or null. */
    fun imageModel(path: String?, api: GlossoApi): Any? {
        if (path.isNullOrBlank()) return null
        return local(path) ?: api.assetUrl(path)
    }

    /** An audio source for [path]: a downloaded file or the remote URL. */
    fun audio(path: String, api: GlossoApi): AudioSource {
        val file = local(path)
        return if (file != null) AudioSource.File(file.toString()) else AudioSource.Url(api.assetUrl(path))
    }
}
