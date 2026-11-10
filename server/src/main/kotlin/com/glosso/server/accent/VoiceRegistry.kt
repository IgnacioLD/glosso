package com.glosso.server.accent

import com.glosso.engine.model.VoiceGender
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The registry could not be read or written; [reason] and [message] are safe to return to an operator. */
class VoiceStorageException(val reason: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/** A reference voice whose audio has been stored. */
@Serializable
data class RegisteredVoice(
    val voiceId: String,
    val accent: String,
    val gender: VoiceGender,
    val label: String? = null,
    /** Stored file name inside the registry directory: `<voiceId>.<ext>`. */
    val audioFile: String,
    val sha256: String,
    val sizeBytes: Long,
    /** Where the audio came from: `inline` or the fetcher's name. */
    val source: String,
    val registeredAt: Long,
)

/**
 * The registered reference voices: one `registry.json` plus one audio file per
 * voice, in a directory that `/assets` never serves (a hidden `.voices` under the
 * assets dir by default, or `GLOSSO_VOICES_DIR`). Provider audio is reference
 * material, not published content.
 *
 * A file is enough: a handful of voices, written by an operator, read rarely.
 * Writes go through a temp file and an atomic move, so a crash never leaves a
 * half-written registry.
 */
class VoiceRegistry(val dir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(RegisteredVoice.serializer())
    private val index = File(dir, "registry.json")
    private val lock = Any()

    fun all(): List<RegisteredVoice> = synchronized(lock) { read() }

    fun get(voiceId: String): RegisteredVoice? = all().firstOrNull { it.voiceId.equals(voiceId, ignoreCase = true) }

    fun audio(voice: RegisteredVoice): File = File(dir, voice.audioFile)

    /** Stores (or replaces) a voice and its audio. */
    fun put(voice: RegisteredVoice, audio: ByteArray): RegisteredVoice = synchronized(lock) {
        val current = read()
        current.firstOrNull { it.voiceId.equals(voice.voiceId, ignoreCase = true) && it.audioFile != voice.audioFile }
            ?.let { File(dir, it.audioFile).delete() }
        atomicWrite(File(dir, voice.audioFile), audio)
        val updated = current.filterNot { it.voiceId.equals(voice.voiceId, ignoreCase = true) } + voice
        atomicWrite(index, json.encodeToString(serializer, updated.sortedBy { it.voiceId }).toByteArray())
        voice
    }

    private fun read(): List<RegisteredVoice> = try {
        if (index.isFile) json.decodeFromString(serializer, index.readText()) else emptyList()
    } catch (e: IOException) {
        throw VoiceStorageException("storage_error", "could not read ${index.path}: ${reasonOf(e)}", e)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        try {
            target.parentFile?.let { Files.createDirectories(it.toPath()) }
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            throw VoiceStorageException("storage_error", "could not write ${target.path}: ${reasonOf(e)}", e)
        }
    }

    private fun reasonOf(e: IOException): String = e.message ?: e::class.simpleName ?: "I/O error"

    companion object {
        fun fromEnv(assetsDir: File, env: (String) -> String? = System::getenv): VoiceRegistry =
            VoiceRegistry(env("GLOSSO_VOICES_DIR")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(assetsDir, ".voices"))
    }
}
