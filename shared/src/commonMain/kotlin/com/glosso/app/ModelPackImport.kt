package com.glosso.app

import com.glosso.app.api.GlossoApi
import com.glosso.app.platform.PickedFile
import com.glosso.engine.model.PhonemeModelConfig
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.HashingSink
import okio.IOException
import okio.Path
import okio.blackholeSink
import okio.buffer
import okio.use

/**
 * Installs the on-device model pack from files the learner picked (a copy of the
 * pack fetched elsewhere, for a phone that cannot or should not download it).
 * Each file is copied to a temporary file in the pack directory while its SHA-256
 * is computed, and only a file whose digest is an accepted one for a pack file is
 * renamed onto that file; anything else is deleted. File names do not matter: the
 * digest says which file it is, so a renamed copy still installs and a corrupt or
 * foreign one never does.
 */
object ModelPackImport {

    enum class Outcome {
        /** Every pack file is now in place. */
        COMPLETE,

        /** What was picked is valid, but a pack file is still missing. */
        INCOMPLETE,

        /** A picked file matched no pack file and the pack is still incomplete. */
        REJECTED,
    }

    /** SHA-256 of the pack the app was released with (`<asset>.sha256` on the server). */
    private val PINNED_SHA256 = mapOf(
        PhonemeModelConfig.MODEL_FILE to "f3a768be3f4f1918a0cd52f51798dc50db307167d6b6dd12d0fa8ce6de1e258a",
        PhonemeModelConfig.VOCAB_FILE to "d732ab2456c0c017930001dc9af0b41b3b93d25b2eb9740bf9d925508d7d87d0",
    )

    private const val PUBLISHED_DIGEST_TIMEOUT_MS = 5_000L

    /**
     * The digests each pack file may have: the one pinned above (the deployed
     * pack, so an import works with no connection) or the one [api]'s server
     * publishes now, the digest a download is checked against.
     */
    suspend fun acceptedDigests(api: GlossoApi): Map<String, Set<String>> =
        PINNED_SHA256.mapValues { (name, pinned) ->
            val served = withTimeoutOrNull(PUBLISHED_DIGEST_TIMEOUT_MS) { api.sharedAssetSha256(name) }
            setOfNotNull(pinned, served?.lowercase())
        }

    /** [accepted] maps each pack file name to the lowercase hex SHA-256 digests it may have. */
    fun install(fs: FileSystem, dir: Path, files: List<PickedFile>, accepted: Map<String, Set<String>>): Outcome {
        fs.createDirectories(dir)
        var rejected = false
        files.forEachIndexed { index, file ->
            val temporary = dir / "import-$index.part"
            try {
                val digest = copyHashing(fs, file, temporary)
                val target = accepted.entries.firstOrNull { digest in it.value }?.key
                if (target == null) {
                    rejected = true
                } else {
                    val destination = dir / target
                    fs.delete(destination)
                    try {
                        fs.atomicMove(temporary, destination)
                    } catch (e: IOException) {
                        throw IOException("Could not install $target", e)
                    }
                }
            } finally {
                fs.delete(temporary)
            }
        }
        return when {
            accepted.keys.all { (fs.metadataOrNull(dir / it)?.size ?: 0L) > 0 } -> Outcome.COMPLETE
            rejected -> Outcome.REJECTED
            else -> Outcome.INCOMPLETE
        }
    }

    /** Lowercase hex SHA-256 of an installed file. */
    fun sha256(fs: FileSystem, file: Path): String {
        val hashing = HashingSink.sha256(blackholeSink())
        fs.source(file).buffer().use { it.readAll(hashing) }
        return hashing.hash.hex()
    }

    private fun copyHashing(fs: FileSystem, file: PickedFile, target: Path): String {
        val hashing = HashingSink.sha256(fs.sink(target))
        file.open().buffer().use { source -> hashing.buffer().use { source.readAll(it) } }
        return hashing.hash.hex()
    }
}
