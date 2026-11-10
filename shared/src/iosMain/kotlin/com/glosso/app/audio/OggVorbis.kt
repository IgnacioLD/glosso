package com.glosso.app.audio

import cnames.structs.stb_vorbis
import com.glosso.app.vorbis.stb_vorbis_close
import com.glosso.app.vorbis.stb_vorbis_get_error
import com.glosso.app.vorbis.stb_vorbis_get_info
import com.glosso.app.vorbis.stb_vorbis_get_samples_short_interleaved
import com.glosso.app.vorbis.stb_vorbis_open_memory
import com.glosso.app.vorbis.stb_vorbis_stream_length_in_samples
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dataWithLength
import platform.posix.fclose
import platform.posix.SEEK_SET
import platform.posix.fopen
import platform.posix.fseek
import platform.posix.fwrite

/** The audio could not be decoded; [message] says why (for the log, not the learner). */
internal class VorbisException(message: String) : Exception(message)

/** Decoded Ogg Vorbis as 16-bit PCM WAV, at the stream's own sample rate and channel count. */
internal sealed class DecodedAudio {
    abstract val sampleRate: Int
    abstract val channels: Int
    abstract val frames: Long

    val seconds: Double get() = frames.toDouble() / sampleRate

    /** A whole WAV file in memory, small enough to keep in [PcmCache]. */
    class InMemory(
        override val sampleRate: Int,
        override val channels: Int,
        override val frames: Long,
        val wav: NSData,
    ) : DecodedAudio() {
        val bytes: Long get() = wav.length.toLong()
    }

    /** A WAV file written to the temporary directory, for audio too long to hold in memory. */
    class OnDisk(
        override val sampleRate: Int,
        override val channels: Int,
        override val frames: Long,
        val path: String,
    ) : DecodedAudio()
}

/**
 * Ogg Vorbis to PCM with stb_vorbis (src/nativeInterop/stb_vorbis), for the
 * content audio AVFoundation cannot play. The whole stream is decoded before
 * playback starts: a 26 s story paragraph takes about 10 ms on the simulator.
 * Audio whose PCM would exceed [IN_MEMORY_MAX_BYTES] is written to a temporary
 * WAV file chunk by chunk instead, so a story of several minutes never holds
 * more than its compressed bytes and one chunk in memory.
 */
@OptIn(ExperimentalForeignApi::class)
internal object VorbisDecoder {

    /** 4 MB of PCM: about 87 s of the 24 kHz mono content audio. */
    const val IN_MEMORY_MAX_BYTES = 4L * 1024 * 1024

    private const val CHUNK_FRAMES = 4096
    private const val WAV_HEADER_BYTES = 44

    /** The stream starts with an Ogg page ("OggS"), whatever the file is called. */
    fun isOgg(data: CPointer<UByteVar>?, size: Int): Boolean =
        data != null && size >= 4 &&
            data[0].toInt() == 'O'.code && data[1].toInt() == 'g'.code &&
            data[2].toInt() == 'g'.code && data[3].toInt() == 'S'.code

    fun isOgg(data: NSData): Boolean = isOgg(data.bytes?.reinterpret(), data.length.toInt())

    fun decode(data: NSData, inMemoryMax: Long = IN_MEMORY_MAX_BYTES): DecodedAudio {
        val size = data.length
        if (size > Int.MAX_VALUE.toULong()) throw VorbisException("stream too large ($size bytes)")
        return decode(data.bytes?.reinterpret(), size.toInt(), inMemoryMax)
    }

    fun decode(bytes: ByteArray, inMemoryMax: Long = IN_MEMORY_MAX_BYTES): DecodedAudio {
        if (bytes.isEmpty()) throw VorbisException("empty stream")
        return bytes.usePinned { decode(it.addressOf(0).reinterpret(), bytes.size, inMemoryMax) }
    }

    fun decode(data: CPointer<UByteVar>?, size: Int, inMemoryMax: Long): DecodedAudio {
        if (data == null || size == 0) throw VorbisException("empty stream")
        OggPages.problem(data, size)?.let { throw VorbisException(it) }
        return memScoped {
            val error = alloc<IntVar>()
            val vorbis = stb_vorbis_open_memory(data, size, error.ptr, null)
                ?: throw VorbisException("stb_vorbis cannot open the stream (error ${error.value})")
            try {
                decodeOpened(vorbis, inMemoryMax)
            } finally {
                stb_vorbis_close(vorbis)
            }
        }
    }

    private fun decodeOpened(vorbis: CPointer<stb_vorbis>, inMemoryMax: Long): DecodedAudio {
        val (sampleRate, channels) = stb_vorbis_get_info(vorbis).useContents { sample_rate.toInt() to channels }
        // PCM WAV beyond stereo needs WAVE_FORMAT_EXTENSIBLE; the content is mono.
        if (channels !in 1..2) throw VorbisException("unsupported channel count $channels")
        if (sampleRate !in 8_000..192_000) throw VorbisException("unsupported sample rate $sampleRate")
        val frames = stb_vorbis_stream_length_in_samples(vorbis).toLong()
        if (frames <= 0) throw VorbisException("stream has no length")
        val pcmBytes = frames * channels * 2
        if (pcmBytes > UInt.MAX_VALUE.toLong() - WAV_HEADER_BYTES) throw VorbisException("stream too long ($frames frames)")
        return if (pcmBytes + WAV_HEADER_BYTES <= inMemoryMax) {
            decodeToMemory(vorbis, sampleRate, channels, frames, pcmBytes)
        } else {
            decodeToFile(vorbis, sampleRate, channels, frames, pcmBytes)
        }
    }

    private fun decodeToMemory(
        vorbis: CPointer<stb_vorbis>,
        sampleRate: Int,
        channels: Int,
        frames: Long,
        pcmBytes: Long,
    ): DecodedAudio.InMemory {
        val wav = NSMutableData.dataWithLength((WAV_HEADER_BYTES + pcmBytes).toULong())
            ?: throw VorbisException("out of memory for ${WAV_HEADER_BYTES + pcmBytes} bytes")
        val base = wav.mutableBytes!!.reinterpret<ByteVar>()
        writeWavHeader(base, sampleRate, channels, pcmBytes)
        val samples = (base + WAV_HEADER_BYTES)!!.reinterpret<ShortVar>()
        var done = 0L
        while (done < frames) {
            val want = minOf(frames - done, CHUNK_FRAMES.toLong()).toInt()
            val got = stb_vorbis_get_samples_short_interleaved(vorbis, channels, samples + done * channels, want * channels)
            if (got <= 0) break
            done += got
        }
        checkComplete(vorbis, channels, frames, done)
        if (done < frames) {
            wav.setLength((WAV_HEADER_BYTES + done * channels * 2).toULong())
            writeWavHeader(wav.mutableBytes!!.reinterpret(), sampleRate, channels, done * channels * 2)
        }
        return DecodedAudio.InMemory(sampleRate, channels, done, wav)
    }

    private fun decodeToFile(
        vorbis: CPointer<stb_vorbis>,
        sampleRate: Int,
        channels: Int,
        frames: Long,
        pcmBytes: Long,
    ): DecodedAudio.OnDisk {
        val path = SpillFiles.newPath()
        val file = fopen(path, "wb") ?: throw VorbisException("cannot create $path")
        var ok = false
        var done = 0L
        val buffer = nativeHeap.allocArray<ShortVar>(CHUNK_FRAMES * channels)
        fun header(pcm: Long) = memScoped {
            val header = allocArray<ByteVar>(WAV_HEADER_BYTES)
            writeWavHeader(header, sampleRate, channels, pcm)
            if (fwrite(header, 1u, WAV_HEADER_BYTES.toULong(), file) != WAV_HEADER_BYTES.toULong()) {
                throw VorbisException("cannot write $path")
            }
        }
        try {
            header(pcmBytes)
            while (done < frames) {
                val want = minOf(frames - done, CHUNK_FRAMES.toLong()).toInt()
                val got = stb_vorbis_get_samples_short_interleaved(vorbis, channels, buffer, want * channels)
                if (got <= 0) break
                val count = (got * channels).toULong()
                if (fwrite(buffer, 2u, count, file) != count) throw VorbisException("cannot write $path")
                done += got
            }
            checkComplete(vorbis, channels, frames, done)
            if (done < frames) {
                fseek(file, 0, SEEK_SET)
                header(done * channels * 2)
            }
            ok = true
        } finally {
            nativeHeap.free(buffer)
            fclose(file)
            if (!ok) SpillFiles.delete(path)
        }
        return DecodedAudio.OnDisk(sampleRate, channels, done, path)
    }

    /**
     * The decoder ran to the end with no error and nothing beyond the declared
     * length. It may stop a few frames short of it: some encoders (ffmpeg's own)
     * put a last granule position past the audio they wrote, and every decoder
     * then plays what is there. Truncation never gets this far: [OggPages]
     * rejects a stream whose last page is missing or cut.
     */
    private fun checkComplete(vorbis: CPointer<stb_vorbis>, channels: Int, frames: Long, done: Long) {
        val error = stb_vorbis_get_error(vorbis)
        if (error != 0) throw VorbisException("decode error $error after $done of $frames frames")
        if (done == 0L) throw VorbisException("no audio in $frames declared frames")
        val extra = memScoped {
            val scratch = allocArray<ShortVar>(channels)
            stb_vorbis_get_samples_short_interleaved(vorbis, channels, scratch, channels)
        }
        if (extra > 0) throw VorbisException("more audio than the declared $frames frames")
    }

    private fun writeWavHeader(out: CPointer<ByteVar>, sampleRate: Int, channels: Int, pcmBytes: Long) {
        var pos = 0
        fun ascii(text: String) = text.forEach { out[pos++] = it.code.toByte() }
        fun le(value: Long, bytes: Int) = repeat(bytes) { out[pos++] = (value shr (8 * it)).toByte() }
        ascii("RIFF")
        le(36 + pcmBytes, 4)
        ascii("WAVE")
        ascii("fmt ")
        le(16, 4)
        le(1, 2) // PCM
        le(channels.toLong(), 2)
        le(sampleRate.toLong(), 4)
        le(sampleRate.toLong() * channels * 2, 4) // byte rate
        le(channels * 2L, 2) // block align
        le(16, 2) // bits per sample
        ascii("data")
        le(pcmBytes, 4)
    }
}

/**
 * Walks the Ogg pages of a whole stream and returns why it is not playable, or
 * null when it is. Every page must be intact (capture pattern, version, length
 * inside the data, CRC), the first must open the stream and the last must close
 * it, ending exactly at the end of the data. That turns a truncated download or
 * a corrupted file into a clean failure before the decoder sees it.
 */
@OptIn(ExperimentalForeignApi::class)
internal object OggPages {

    private const val HEADER_BYTES = 27
    private const val FLAG_BEGIN = 0x02
    private const val FLAG_END = 0x04

    fun problem(bytes: ByteArray): String? =
        if (bytes.isEmpty()) "empty stream" else bytes.usePinned { problem(it.addressOf(0).reinterpret(), bytes.size) }

    fun problem(data: CPointer<UByteVar>, size: Int): String? {
        var pos = 0
        var page = 0
        var flags = 0
        while (pos < size) {
            if (size - pos < HEADER_BYTES) return "truncated page header at byte $pos"
            if (!VorbisDecoder.isOgg((data + pos), size - pos)) return "no Ogg page at byte $pos"
            if (data[pos + 4].toInt() != 0) return "unknown Ogg version at byte $pos"
            flags = data[pos + 5].toInt()
            val segments = data[pos + 26].toInt()
            val tableEnd = pos + HEADER_BYTES + segments
            if (tableEnd > size) return "truncated segment table at byte $pos"
            var body = 0
            for (i in pos + HEADER_BYTES until tableEnd) body += data[i].toInt()
            val end = tableEnd + body
            if (end > size) return "truncated page $page (needs ${end - size} more bytes)"
            if (page == 0 && flags and FLAG_BEGIN == 0) return "first page does not begin the stream"
            if (crc(data, pos, end) != storedCrc(data, pos)) return "bad checksum on page $page"
            pos = end
            page++
        }
        if (flags and FLAG_END == 0) return "stream ends without its last page (truncated)"
        return null
    }

    private fun storedCrc(data: CPointer<UByteVar>, page: Int): UInt {
        var value = 0u
        for (i in 0 until 4) value = value or (data[page + 22 + i].toUInt() shl (8 * i))
        return value
    }

    /** Ogg's CRC-32 (polynomial 0x04C11DB7, not reflected, zero start) with the CRC field as zeros. */
    private fun crc(data: CPointer<UByteVar>, start: Int, end: Int): UInt {
        var crc = 0u
        for (i in start until end) {
            val byte = if (i - start in 22..25) 0u else data[i].toUInt()
            crc = (crc shl 8) xor TABLE[((crc shr 24) xor byte).toInt() and 0xFF]
        }
        return crc
    }

    private val TABLE = UIntArray(256) { index ->
        var r = index.toUInt() shl 24
        repeat(8) { r = if (r and 0x80000000u != 0u) (r shl 1) xor 0x04C11DB7u else r shl 1 }
        r
    }
}

/** The temporary WAV files of [DecodedAudio.OnDisk], in their own folder under tmp. */
@OptIn(ExperimentalForeignApi::class)
internal object SpillFiles {
    private val dir: String by lazy {
        val path = NSTemporaryDirectory() + "glosso-audio"
        val files = NSFileManager.defaultManager
        // Nothing from an earlier run is still playing.
        files.removeItemAtPath(path, null)
        files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        path
    }

    fun newPath(): String = "$dir/${NSUUID().UUIDString}.wav"

    fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
}

/**
 * Recently decoded clips, so replaying a line or a word, or going back over a
 * lesson, does not decode again. Least recently used first out, bounded by
 * [budgetBytes] of WAV (6 MB holds a lesson's words and sentences many times
 * over). Thread-safe: decoding runs off the main thread.
 */
internal class PcmCache(private val budgetBytes: Long) {
    private val lock = platform.Foundation.NSLock()
    private val entries = LinkedHashMap<String, DecodedAudio.InMemory>()
    private var bytes = 0L

    fun get(key: String): DecodedAudio.InMemory? = locked {
        entries.remove(key)?.also { entries[key] = it }
    }

    fun put(key: String, audio: DecodedAudio.InMemory) {
        if (audio.bytes > budgetBytes) return
        locked {
            entries.remove(key)?.let { bytes -= it.bytes }
            entries[key] = audio
            bytes += audio.bytes
            while (bytes > budgetBytes) {
                val eldest = entries.keys.first()
                bytes -= entries.remove(eldest)!!.bytes
            }
        }
    }

    val sizeBytes: Long get() = locked { bytes }

    val count: Int get() = locked { entries.size }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        val shared = PcmCache(6L * 1024 * 1024)
    }
}
