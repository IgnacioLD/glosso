package com.glosso.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The manifest's asset size index: served paths, only the downloadable kinds,
 * keyed exactly as the app's asset paths are.
 */
class AssetSizeIndexTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `indexes audio and images by asset-relative path`() {
        val dir = folder.newFolder()
        File(dir, "audio").mkdirs()
        File(dir, "audio/a.ogg").writeBytes(ByteArray(10))
        File(dir, "images").mkdirs()
        File(dir, "images/c.svg").writeBytes(ByteArray(20))
        // Not a downloadable asset: must be ignored.
        File(dir, "manifest.json").writeText("{}")

        val index = assetSizeIndex(dir)

        assertEquals(10L, index["audio/a.ogg"])
        assertEquals(20L, index["images/c.svg"])
        assertEquals(2, index.size)
    }

    @Test
    fun `a language without assets yields an empty index`() {
        assertEquals(emptyMap<String, Long>(), assetSizeIndex(folder.newFolder()))
    }

    @Test
    fun `the served sizes prefer the files on disk`() {
        val disk = mapOf("audio/a.ogg" to 10L)
        val published = mapOf("audio/a.ogg" to 999L, "images/b.svg" to 20L)
        assertEquals(disk, servedAssetSizes(disk, published))
    }

    @Test
    fun `a content-only deploy falls back to the published sizes`() {
        val published = mapOf("audio/a.ogg" to 10L, "images/b.svg" to 20L)
        assertEquals(published, servedAssetSizes(emptyMap(), published))
    }
}
