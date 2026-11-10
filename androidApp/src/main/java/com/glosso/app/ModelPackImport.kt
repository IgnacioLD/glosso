package com.glosso.app

import com.glosso.app.platform.PickedFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okio.buffer
import okio.sink

/**
 * Installs the on-device model pack from files the learner picked (a copy of the
 * pack fetched elsewhere, for a phone that cannot or should not download it).
 * Each file is copied to a temporary file in the pack directory while its SHA-256
 * is computed, and only a file whose digest is an accepted one for a pack file is
 * renamed onto that file; anything else is deleted. File names do not matter: the
 * digest says which file it is, so a renamed copy still installs and a corrupt or
 * foreign one never does.
 */
internal object ModelPackImport {

    enum class Outcome {
        /** Every pack file is now in place. */
        COMPLETE,

        /** What was picked is valid, but a pack file is still missing. */
        INCOMPLETE,

        /** A picked file matched no pack file and the pack is still incomplete. */
        REJECTED,
    }

    /** [accepted] maps each pack file name to the lowercase hex SHA-256 digests it may have. */
    fun install(dir: File, files: List<PickedFile>, accepted: Map<String, Set<String>>): Outcome {
        dir.mkdirs()
        var rejected = false
        files.forEachIndexed { index, file ->
            val temporary = File(dir, "import-$index.part")
            try {
                val digest = copyHashing(file, temporary)
                val target = accepted.entries.firstOrNull { digest in it.value }?.key
                if (target == null) {
                    rejected = true
                } else {
                    val destination = File(dir, target)
                    destination.delete()
                    if (!temporary.renameTo(destination)) throw IOException("Could not install $target")
                }
            } finally {
                temporary.delete()
            }
        }
        return when {
            accepted.keys.all { File(dir, it).length() > 0 } -> Outcome.COMPLETE
            rejected -> Outcome.REJECTED
            else -> Outcome.INCOMPLETE
        }
    }

    /** Lowercase hex SHA-256 of an installed file. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyHashing(file: PickedFile, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.open().buffer().use { source ->
            target.sink().buffer().use { sink ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                    sink.write(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
