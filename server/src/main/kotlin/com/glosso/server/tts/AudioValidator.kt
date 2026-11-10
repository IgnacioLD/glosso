package com.glosso.server.tts

/**
 * Validates the bytes a provider returned before anything is published. A
 * response that is empty, too small, not the requested container, an error page
 * or an implausible duration is rejected: the asset is not uploaded to the pool
 * and the job is marked FAILED with the reason.
 */
class AudioValidator(
    private val maxDurationMs: Long = 120_000,
    private val minBytes: Int = 128,
) {

    data class Validation(
        val valid: Boolean,
        val reason: String?,
        val checksum: String,
        val sizeBytes: Long,
    )

    fun validate(audio: ByteArray, format: AudioFormat, declaredDurationMs: Long?): Validation {
        val checksum = TtsFingerprint.sha256Hex(audio)
        val size = audio.size.toLong()

        if (audio.isEmpty()) return invalid("empty_audio", checksum, size)
        if (audio.size < minBytes) return invalid("too_small", checksum, size)
        if (looksLikeErrorPayload(audio)) return invalid("corrupt_response", checksum, size)
        if (!matchesContainer(audio, format.container)) {
            return invalid("wrong_format", checksum, size)
        }

        val duration = declaredDurationMs ?: estimateDurationMs(size, format)
        if (declaredDurationMs != null && declaredDurationMs <= 0) {
            return invalid("non_positive_duration", checksum, size)
        }
        if (duration != null && duration > maxDurationMs) {
            return invalid("duration_too_long", checksum, size)
        }

        return Validation(valid = true, reason = null, checksum = checksum, sizeBytes = size)
    }

    private fun invalid(reason: String, checksum: String, size: Long) =
        Validation(valid = false, reason = reason, checksum = checksum, sizeBytes = size)

    /** A JSON/HTML error body served with a 200 status is corruption, not audio. */
    private fun looksLikeErrorPayload(audio: ByteArray): Boolean {
        val head = audio.copyOfRange(0, minOf(audio.size, 64)).toString(Charsets.UTF_8).trimStart()
        return head.startsWith("{") || head.startsWith("<")
    }

    private fun matchesContainer(audio: ByteArray, container: AudioContainer): Boolean {
        if (audio.size < 4) return false
        return when (container) {
            AudioContainer.MP3 -> isMp3(audio)
            AudioContainer.OGG -> audio[0] == 'O'.code.toByte() &&
                audio[1] == 'g'.code.toByte() &&
                audio[2] == 'g'.code.toByte() &&
                audio[3] == 'S'.code.toByte()
            AudioContainer.WAV -> audio[0] == 'R'.code.toByte() &&
                audio[1] == 'I'.code.toByte() &&
                audio[2] == 'F'.code.toByte() &&
                audio[3] == 'F'.code.toByte()
        }
    }

    private fun isMp3(audio: ByteArray): Boolean {
        // ID3 tag or an MPEG audio frame sync word (11 bits set).
        val id3 = audio[0] == 'I'.code.toByte() &&
            audio[1] == 'D'.code.toByte() &&
            audio[2] == '3'.code.toByte()
        val frameSync = (audio[0].toInt() and 0xFF) == 0xFF && (audio[1].toInt() and 0xE0) == 0xE0
        return id3 || frameSync
    }

    private fun estimateDurationMs(sizeBytes: Long, format: AudioFormat): Long? {
        if (format.container != AudioContainer.MP3 || format.bitRate <= 0) return null
        return sizeBytes * 8_000 / format.bitRate
    }
}
