package com.glosso.server

import com.glosso.engine.model.PhonemeModelConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.slf4j.LoggerFactory

/**
 * Downloads the runtime assets into the persistent volume on first run, so the
 * container can be provisioned without SSH access.
 *
 * Idempotent: only files that are missing or too small are fetched. Downloads go
 * to a `.download` temp file and are only renamed once complete, so a crash never
 * leaves a half-written model in place.
 *
 * Integrity (A08): when `GLOSSO_ASSET_CHECKSUMS` points to a `sha256sum`-format
 * file (`<hex>  <filename>`), every asset is verified against it - both a fresh
 * download and one already on disk - and a mismatch is fatal. Left unset, the
 * download size floor still applies but no hash is checked.
 */
object AssetBootstrapper {

    private val log = LoggerFactory.getLogger(AssetBootstrapper::class.java)

    private const val HF = "https://huggingface.co/onnx-community/wav2vec2-lv-60-espeak-cv-ft-ONNX/resolve/main"

    // Our selective int8 is not on Hugging Face; it is published next to the
    // other served assets. The default points at the deployment that publishes
    // it, so a fresh volume can self-provision the exact model both sides run.
    private val modelUrl =
        System.getenv("GLOSSO_MODEL_URL")
            ?: "https://api.glossostudio.com/assets/${PhonemeModelConfig.MODEL_FILE}"

    internal const val CHECKSUMS_ENV = "GLOSSO_ASSET_CHECKSUMS"

    private data class Asset(val url: String, val fileName: String, val minBytes: Long)

    private val assets = listOf(
        Asset(modelUrl, PhonemeModelConfig.MODEL_FILE, PhonemeModelConfig.MIN_MODEL_BYTES),
        Asset("$HF/vocab.json", PhonemeModelConfig.VOCAB_FILE, PhonemeModelConfig.MIN_VOCAB_BYTES),
        Asset(
            "https://raw.githubusercontent.com/cmusphinx/cmudict/master/cmudict.dict",
            "cmudict.dict",
            1024L * 1024,
        ),
    )

    fun ensure(assetsDir: File) {
        assetsDir.mkdirs()
        val expected = loadChecksums()
        for (asset in assets) {
            val target = File(assetsDir, asset.fileName)
            if (target.isFile && target.length() >= asset.minBytes) {
                verifyOrFail(target, asset.fileName, expected)
                log.info("Asset present: {} ({} bytes)", asset.fileName, target.length())
                continue
            }
            download(asset, target, expected)
        }
    }

    /**
     * Reads the optional `GLOSSO_ASSET_CHECKSUMS` file, a `sha256sum`-style list
     * of `<hex>  <filename>` lines. Missing file is fatal (fail closed): a
     * configured integrity check must not silently become a no-op.
     */
    internal fun loadChecksums(env: Map<String, String> = System.getenv()): Map<String, String> {
        val path = env[CHECKSUMS_ENV]?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val file = File(path)
        if (!file.isFile) {
            throw IllegalStateException("$CHECKSUMS_ENV points to a missing file: $path")
        }
        return parseChecksums(file.readText())
    }

    /** Parses `sha256sum` output. Lines that are not `<64 hex>  <name>` are ignored. */
    internal fun parseChecksums(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 2) return@mapNotNull null
            val hex = parts[0].lowercase()
            if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return@mapNotNull null
            parts[1].removePrefix("*") to hex
        }
        .toMap()

    internal fun verifyOrFail(file: File, name: String, expected: Map<String, String>) {
        val want = expected[name] ?: return
        val got = sha256Hex(file)
        if (!got.equals(want, ignoreCase = true)) {
            throw IllegalStateException("checksum mismatch for $name: expected $want, got $got")
        }
    }

    internal fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun download(asset: Asset, target: File, expected: Map<String, String>) {
        val tmp = File(target.parentFile, "${target.name}.download")
        tmp.delete()
        log.info("Downloading {} from {}", asset.fileName, asset.url)
        val connection = URI.create(asset.url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 300_000
        connection.setRequestProperty("User-Agent", "glosso-pro/asset-bootstrap")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code for ${asset.url}")
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var total = 0L
                    var read = input.read(buffer)
                    var lastLog = 0L
                    while (read >= 0) {
                        output.write(buffer, 0, read)
                        total += read
                        if (total - lastLog > 20L * 1024 * 1024) {
                            log.info("  {} ... {} MB", asset.fileName, total / (1024 * 1024))
                            lastLog = total
                        }
                        read = input.read(buffer)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            connection.disconnect()
        }

        if (tmp.length() < asset.minBytes) {
            val size = tmp.length()
            tmp.delete()
            throw IllegalStateException("${asset.fileName} too small after download ($size bytes)")
        }
        runCatching { verifyOrFail(tmp, asset.fileName, expected) }.onFailure { tmp.delete() }.getOrThrow()
        target.delete()
        if (!tmp.renameTo(target)) {
            throw IllegalStateException("Could not finalize ${asset.fileName}")
        }
        log.info("Asset ready: {} ({} bytes)", asset.fileName, target.length())
    }
}
