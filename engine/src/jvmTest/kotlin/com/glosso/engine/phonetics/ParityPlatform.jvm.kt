package com.glosso.engine.phonetics

import java.io.File

internal actual val ParityPlatform: ParityIo = object : ParityIo {
    override val name: String = "jvm"

    override fun env(name: String): String? = System.getenv(name)

    override fun readBytes(path: String): ByteArray = File(path).readBytes()

    override fun readText(path: String): String = File(path).readText()

    override fun writeText(path: String, text: String) = File(path).writeText(text)

    override fun writeBytes(path: String, bytes: ByteArray) = File(path).writeBytes(bytes)

    override fun mkdirs(path: String) {
        File(path).mkdirs()
    }

    // The JVM side is the reference for scores, not for memory: a heap plus
    // ONNX Runtime's native arena is not comparable to an iOS footprint.
    override fun peakMemoryBytes(): Long? = null

    override fun footprintBytes(): Long? = null
}
