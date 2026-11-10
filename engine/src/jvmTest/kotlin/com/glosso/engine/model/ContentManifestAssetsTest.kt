package com.glosso.engine.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The manifest's size index is what lets the app compute a lesson's size
 * locally. These guard the sum and the backward-compatible wire shape.
 */
class ContentManifestAssetsTest {

    private val lenient = Json { ignoreUnknownKeys = true }


    @Test
    fun `sizeOf adds the published sizes`() {
        val manifest = ContentManifest(
            assets = mapOf(
                "audio/a.ogg" to 100L,
                "audio/b.ogg" to 250L,
                "images/c.svg" to 50L,
            ),
        )
        assertEquals(400L, manifest.sizeOf(listOf("audio/a.ogg", "audio/b.ogg", "images/c.svg")))
    }

    @Test
    fun `sizeOf is null when any asset is unpublished`() {
        val manifest = ContentManifest(assets = mapOf("audio/a.ogg" to 100L))
        assertNull(manifest.sizeOf(listOf("audio/a.ogg", "audio/missing.ogg")))
    }

    @Test
    fun `sizeOf of nothing is zero`() {
        assertEquals(0L, ContentManifest().sizeOf(emptyList()))
    }

    @Test
    fun `an old manifest without assets still deserializes`() {
        val json = """{"version":1,"language":"en","assetVersion":"abc"}"""
        val manifest = lenient.decodeFromString<ContentManifest>(json)
        assertEquals("abc", manifest.assetVersion)
        assertEquals(emptyMap<String, Long>(), manifest.assets)
    }

    @Test
    fun `assets survive a round trip`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val manifest = ContentManifest(assets = mapOf("audio/a.ogg" to 42L))
        val decoded = json.decodeFromString(
            ContentManifest.serializer(),
            json.encodeToString(ContentManifest.serializer(), manifest),
        )
        assertEquals(42L, decoded.assets["audio/a.ogg"])
    }

    @Test
    fun `a bank carries its published byte total`() {
        val json = Json { ignoreUnknownKeys = true }
        val manifest = json.decodeFromString<ContentManifest>(
            """{"version":1,"banks":[{"kind":"DRILL","path":"drills/","version":1,"bytes":1234}]}""",
        )
        assertEquals(1234L, manifest.banks.single().bytes)
    }

    @Test
    fun `a bank without bytes defaults to zero`() {
        val manifest = lenient.decodeFromString<ContentManifest>(
            """{"version":1,"banks":[{"kind":"DRILL","path":"drills/","version":1}]}""",
        )
        assertEquals(0L, manifest.banks.single().bytes)
    }
}
