package com.glosso.app

import coil3.ImageLoader
import coil3.Uri
import coil3.disk.DiskCache
import coil3.fetch.Fetcher
import coil3.serviceLoaderEnabled
import coil3.svg.SvgDecoder
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.appFiles

/**
 * The one image loader the whole app shares: Coil's default pipeline plus SVG
 * decoding. Coil sniffs the bytes rather than the extension, so the catalogue
 * can carry PNG and SVG side by side and no screen has to know which an item
 * is. A vector has no pixel size of its own, so Coil rasters it at exactly the
 * size the layout asks for (the 16:9 frame in `GlossoArtwork`), keeping the
 * aspect ratio and staying sharp at any density or rotation; a raster takes the
 * same path it always did. The host registers it once as Coil's singleton
 * loader (Android: `GlossoApplication`), which every `AsyncImage` resolves.
 *
 * The components are listed here rather than discovered, so the pipeline is the
 * same in every build: the platform's network fetcher ([imageNetworkFetcher])
 * and the SVG decoder. The disk cache stays in `cache/image_cache`, where Coil 2
 * kept it; Coil 3 finds the old journal's version different and starts it
 * afresh, so the old entries are dropped instead of left behind.
 */
fun glossoImageLoader(context: PlatformContext): ImageLoader {
    val cacheDir = appFiles(context).cacheDir
    return ImageLoader.Builder(coilContext(context))
        .serviceLoaderEnabled(false)
        .components {
            add(imageNetworkFetcher())
            add(SvgDecoder.Factory())
        }
        .diskCache { DiskCache.Builder().directory(cacheDir / "image_cache").build() }
        .build()
}

/** Coil's own context type for [context]. */
internal expect fun coilContext(context: PlatformContext): coil3.PlatformContext

/** Fetches remote images: OkHttp on Android (as Coil 2 did), Ktor on iOS. */
internal expect fun imageNetworkFetcher(): Fetcher.Factory<Uri>
