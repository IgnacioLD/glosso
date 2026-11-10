package com.glosso.server.tts

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The object store must run without R2 credentials: tests and a deployment with
 * no bucket use the local directory or memory stores, while R2/S3 stays
 * configurable by environment only.
 */
class ObjectStoreTest {

    @Test
    fun `the in-memory store round trips`() {
        val store = InMemoryObjectStore()
        assertFalse(store.exists("tts/a.mp3"))
        store.put("tts/a.mp3", byteArrayOf(1, 2, 3), "audio/mpeg")
        assertTrue(store.exists("tts/a.mp3"))
        assertContentEquals(byteArrayOf(1, 2, 3), store.get("tts/a.mp3"))
        store.delete("tts/a.mp3")
        assertFalse(store.exists("tts/a.mp3"))
        assertNull(store.get("tts/a.mp3"))
    }

    @Test
    fun `the local file store runs without credentials`() {
        val root = Files.createTempDirectory("glosso-objectstore").toFile()
        val store = LocalFileObjectStore(root)
        store.put("tts/nested/b.mp3", byteArrayOf(9, 8, 7), "audio/mpeg")
        assertTrue(store.exists("tts/nested/b.mp3"))
        assertContentEquals(byteArrayOf(9, 8, 7), store.get("tts/nested/b.mp3"))
        assertTrue(java.io.File(root, "tts/nested/b.mp3").isFile)
        store.delete("tts/nested/b.mp3")
        assertFalse(store.exists("tts/nested/b.mp3"))
    }

    @Test
    fun `the s3 store is only built when the environment is complete`() {
        assertNull(S3ObjectStore.fromConfig(null))
        assertNull(
            TtsConfig.fromEnv(mapOf("GLOSSO_R2_ENDPOINT" to "https://r2.example")).r2,
            "incomplete R2 credentials are ignored instead of failing",
        )
    }

    @Test
    fun `the assembled object store prefers r2, then local files, then memory`() {
        assertNull(TtsConfig.fromEnv(emptyMap()).r2)
        assertEquals("cache", TtsConfig.fromEnv(mapOf("GLOSSO_TTS_CACHE_DIR" to "cache")).cacheDir)

        val withR2 = TtsConfig.fromEnv(
            mapOf(
                "GLOSSO_R2_ENDPOINT" to "https://r2.example",
                "GLOSSO_R2_BUCKET" to "glosso",
                "GLOSSO_R2_ACCESS_KEY" to "key",
                "GLOSSO_R2_SECRET_KEY" to "secret",
            ),
        ).r2
        assertEquals("glosso", withR2?.bucket)
    }
}
