package com.glosso.server.accent

import java.io.File

/** Audio bytes of a reference voice and the container they are in. */
class FetchedVoiceAudio(val bytes: ByteArray, val container: AudioContainer)

enum class AudioContainer(val extension: String) {
    WAV("wav"),
    OGG("ogg"),
    MP3("mp3"),
    FLAC("flac");

    companion object {
        fun byExtension(extension: String): AudioContainer? =
            entries.firstOrNull { it.extension.equals(extension, ignoreCase = true) }

        /** Recognises the container from its magic bytes; null when it is none of these. */
        fun sniff(bytes: ByteArray): AudioContainer? {
            fun at(offset: Int, ascii: String) =
                bytes.size >= offset + ascii.length &&
                    ascii.indices.all { bytes[offset + it] == ascii[it].code.toByte() }
            return when {
                at(0, "RIFF") && at(8, "WAVE") -> WAV
                at(0, "OggS") -> OGG
                at(0, "fLaC") -> FLAC
                at(0, "ID3") -> MP3
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 -> MP3
                else -> null
            }
        }
    }
}

/** Why a voice's audio could not be fetched, safe to return to an operator. */
class VoiceFetchException(val reason: String, message: String) : Exception(message)

/**
 * The plug for the owner's voice provider: given a voice UUID, return that
 * voice's reference audio.
 *
 * The provider's API is not known yet, so no network fetcher exists. To wire it:
 * implement this interface (one HTTP call, the key read from the environment,
 * never logged), add a branch to [VoiceAudioFetchers.fromEnv] and set
 * `GLOSSO_VOICE_FETCHER` to its name. Everything downstream (validation,
 * storage, the registry, the admin API) is already provider-independent. See
 * docs/MULTI-ACCENT.md.
 */
interface VoiceAudioFetcher {
    /** Short stable name, recorded as the source of each registered voice. */
    val name: String

    /** @throws VoiceFetchException when the audio cannot be obtained. */
    suspend fun fetch(voiceId: String): FetchedVoiceAudio
}

/**
 * Reads `<dir>/<voiceId>.<wav|ogg|mp3|flac>`: the "local file" mode, so a voice
 * can be registered and tested today from a clip the operator already has.
 */
class LocalFileVoiceFetcher(private val dir: File) : VoiceAudioFetcher {
    override val name: String = "local"

    override suspend fun fetch(voiceId: String): FetchedVoiceAudio {
        val file = AudioContainer.entries
            .map { File(dir, "$voiceId.${it.extension}") }
            .firstOrNull { it.isFile }
            ?: throw VoiceFetchException(
                "not_found",
                "no local audio for $voiceId in ${dir.name}/ (expected <uuid>.wav|ogg|mp3|flac)",
            )
        val container = AudioContainer.byExtension(file.extension)
            ?: throw VoiceFetchException("unsupported", "unsupported extension ${file.extension}")
        return FetchedVoiceAudio(file.readBytes(), container)
    }
}

/** The default while no provider is wired: every fetch fails with a clear reason. */
object UnconfiguredVoiceFetcher : VoiceAudioFetcher {
    override val name: String = "none"

    override suspend fun fetch(voiceId: String): FetchedVoiceAudio = throw VoiceFetchException(
        "fetcher_not_configured",
        "no voice provider is configured; send audioBase64, or set GLOSSO_VOICE_FETCHER=local " +
            "and GLOSSO_VOICE_LOCAL_DIR (docs/MULTI-ACCENT.md)",
    )
}

object VoiceAudioFetchers {
    /**
     * `GLOSSO_VOICE_FETCHER`:
     *  - unset / `none`: [UnconfiguredVoiceFetcher] (audio must come inline).
     *  - `local`: [LocalFileVoiceFetcher] over `GLOSSO_VOICE_LOCAL_DIR`.
     *
     * The provider's own fetcher is added here once its API is known.
     */
    fun fromEnv(env: (String) -> String? = System::getenv, log: (String) -> Unit = {}): VoiceAudioFetcher =
        when (val kind = env("GLOSSO_VOICE_FETCHER")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) {
            null, "none" -> UnconfiguredVoiceFetcher
            "local" -> {
                val dir = env("GLOSSO_VOICE_LOCAL_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
                if (dir == null || !dir.isDirectory) {
                    log("GLOSSO_VOICE_FETCHER=local but GLOSSO_VOICE_LOCAL_DIR is not a directory; voice fetch disabled")
                    UnconfiguredVoiceFetcher
                } else {
                    LocalFileVoiceFetcher(dir)
                }
            }
            else -> {
                log("unknown GLOSSO_VOICE_FETCHER=$kind; voice fetch disabled")
                UnconfiguredVoiceFetcher
            }
        }
}
