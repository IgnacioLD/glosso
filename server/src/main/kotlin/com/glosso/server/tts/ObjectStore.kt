package com.glosso.server.tts

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Object storage for audio. The interface exists so tests and local runs never
 * need R2 credentials, and so the real bucket can be R2, S3 or a local directory
 * without touching the TTS service.
 */
interface ObjectStore : AutoCloseable {

    /** Stable id, e.g. `s3`, `in-memory`. */
    val id: String

    fun exists(key: String): Boolean

    fun put(key: String, bytes: ByteArray, contentType: String): ObjectRef

    fun get(key: String): ByteArray?

    fun delete(key: String)

    override fun close() {}
}

data class ObjectRef(val key: String, val sizeBytes: Long, val contentType: String)

/** In-memory store: tests and ephemeral runs. Not durable. */
class InMemoryObjectStore : ObjectStore {

    private val objects = ConcurrentHashMap<String, ByteArray>()

    override val id: String = "in-memory"

    override fun exists(key: String): Boolean = objects.containsKey(key)

    override fun put(key: String, bytes: ByteArray, contentType: String): ObjectRef {
        val copy = bytes.copyOf()
        objects[key] = copy
        return ObjectRef(key, copy.size.toLong(), contentType)
    }

    override fun get(key: String): ByteArray? = objects[key]?.copyOf()

    override fun delete(key: String) {
        objects.remove(key)
    }
}

/**
 * Local directory store, used for development and as a cheap stand-in for R2
 * when `GLOSSO_TTS_CACHE_DIR` is set. Keys map to files under the root.
 */
class LocalFileObjectStore(private val root: File) : ObjectStore {

    override val id: String = "local-file"

    private fun fileFor(key: String): File = File(root, key)

    override fun exists(key: String): Boolean = fileFor(key).isFile

    override fun put(key: String, bytes: ByteArray, contentType: String): ObjectRef {
        val file = fileFor(key)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return ObjectRef(key, bytes.size.toLong(), contentType)
    }

    override fun get(key: String): ByteArray? {
        val file = fileFor(key)
        return if (file.isFile) file.readBytes() else null
    }

    override fun delete(key: String) {
        fileFor(key).delete()
    }
}
