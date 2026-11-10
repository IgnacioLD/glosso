package com.glosso.server

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the integrity gate of the asset bootstrapper (A08): a checksum list is
 * parsed, a matching file passes, a mismatching one is fatal, and a configured
 * list that is missing fails closed instead of silently disabling the check.
 */
class AssetBootstrapperChecksumTest {

    private val hex = "a".repeat(64)

    @Test
    fun `parses sha256sum lines and ignores noise`() {
        val text = buildString {
            append("# comentario\n")
            append("$hex  model.onnx\n")
            append("${"b".repeat(64)} *vocab.json\n")
            append("\n")
            append("no-hex  x\n")
        }
        val parsed = AssetBootstrapper.parseChecksums(text)
        assertEquals(hex, parsed["model.onnx"])
        assertEquals("b".repeat(64), parsed["vocab.json"])
        assertEquals(2, parsed.size)
    }

    @Test
    fun `a matching checksum passes and a mismatch is fatal`() {
        val file = Files.createTempFile("glosso-asset", ".bin").toFile()
        file.writeText("hello world")
        val good = AssetBootstrapper.sha256Hex(file)

        AssetBootstrapper.verifyOrFail(file, file.name, mapOf(file.name to good))

        val error = assertFailsWith<IllegalStateException> {
            AssetBootstrapper.verifyOrFail(file, file.name, mapOf(file.name to hex))
        }
        assertTrue(error.message.orEmpty().contains("checksum mismatch"))
    }

    @Test
    fun `a configured but missing checksum file fails closed`() {
        val missing = File(Files.createTempDirectory("glosso").toFile(), "nope.txt").path
        assertFailsWith<IllegalStateException> {
            AssetBootstrapper.loadChecksums(mapOf(AssetBootstrapper.CHECKSUMS_ENV to missing))
        }
    }

    @Test
    fun `an unset checksum env is a no-op`() {
        assertTrue(AssetBootstrapper.loadChecksums(emptyMap()).isEmpty())
    }
}
