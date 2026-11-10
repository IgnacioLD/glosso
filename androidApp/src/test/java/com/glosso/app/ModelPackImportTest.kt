package com.glosso.app

import com.glosso.app.platform.PickedFile
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Importing the scoring pack from picked files: only files with an accepted digest are installed. */
class ModelPackImportTest {

    private val model = ByteArray(200_000) { (it * 31).toByte() }
    private val vocab = """{"a":1,"b":2}""".toByteArray()
    private val accepted = mapOf("model.onnx" to setOf(sha256(model)), "vocab.json" to setOf(sha256(vocab)))
    private val dir: File = Files.createTempDirectory("pack").toFile()

    private fun picked(bytes: ByteArray) = PickedFile { Buffer().write(bytes) }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun leftovers() = dir.listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun `valid files install whatever order and name they come in`() {
        val outcome = ModelPackImport.install(dir, listOf(picked(vocab), picked(model)), accepted)

        assertEquals(ModelPackImport.Outcome.COMPLETE, outcome)
        assertArrayEquals(model, File(dir, "model.onnx").readBytes())
        assertArrayEquals(vocab, File(dir, "vocab.json").readBytes())
        assertEquals(listOf("model.onnx", "vocab.json"), leftovers())
    }

    @Test
    fun `a corrupt file is rejected and leaves nothing behind`() {
        val corrupt = model.copyOf().also { it[1234] = (it[1234] + 1).toByte() }

        val outcome = ModelPackImport.install(dir, listOf(picked(corrupt), picked(vocab)), accepted)

        assertEquals(ModelPackImport.Outcome.REJECTED, outcome)
        assertFalse(File(dir, "model.onnx").exists())
        assertEquals(listOf("vocab.json"), leftovers())
    }

    @Test
    fun `a truncated file is rejected`() {
        val outcome = ModelPackImport.install(dir, listOf(picked(model.copyOf(1000))), accepted)

        assertEquals(ModelPackImport.Outcome.REJECTED, outcome)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `one valid file is kept and the pack is reported incomplete until the other arrives`() {
        assertEquals(ModelPackImport.Outcome.INCOMPLETE, ModelPackImport.install(dir, listOf(picked(model)), accepted))
        assertEquals(ModelPackImport.Outcome.COMPLETE, ModelPackImport.install(dir, listOf(picked(vocab)), accepted))
    }

    @Test
    fun `an unreadable file fails without a partial copy`() {
        val broken = PickedFile { throw java.io.FileNotFoundException("gone") }

        runCatching { ModelPackImport.install(dir, listOf(broken), accepted) }

        assertTrue(leftovers().isEmpty())
    }
}
