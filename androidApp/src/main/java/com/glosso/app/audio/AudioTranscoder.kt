package com.glosso.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.glosso.app.platform.appFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Turns any Android-decodable audio (the content TTS is OGG Vorbis at 24 kHz)
 * into the 16 kHz mono 16-bit WAV the recognizer accepts. Used to align an
 * item's reference audio for the rhythm-and-intonation bonus, and by the debug
 * model comparison to drive it with real content audio.
 */
internal object AudioTranscoder {

    fun toWav16k(context: Context, bytes: ByteArray): ByteArray? {
        // The temp file goes through AppFiles: the same cacheDir the app always used.
        val tmp = File.createTempFile("compare", ".audio", appFiles(context).cacheDir.toFile())
        return try {
            tmp.writeBytes(bytes)
            decode(tmp)
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    private fun decode(file: File): ByteArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = candidate
                    break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val raw = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    }

                    in 0..Int.MAX_VALUE -> {
                        val buffer = decoder.getOutputBuffer(outIndex)!!
                        val chunk = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(chunk)
                        raw.write(chunk)
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            val bytes = raw.toByteArray()
            val frames = bytes.size / 2 / channels
            if (frames == 0) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val mono = ShortArray(frames) {
                var sum = 0
                repeat(channels) { sum += buffer.getShort().toInt() }
                (sum / channels).toShort()
            }
            return Wav.pcm16Mono(Wav.bytesOf(resample(mono, sampleRate, Wav.SAMPLE_RATE)))
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || input.isEmpty()) return input
        val outLength = (input.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = ShortArray(outLength)
        for (i in out.indices) {
            val position = i.toDouble() * from / to
            val left = position.toInt().coerceIn(0, input.size - 1)
            val right = (left + 1).coerceAtMost(input.size - 1)
            val fraction = position - left
            out[i] = (input[left] * (1 - fraction) + input[right] * fraction).roundToInt().toShort()
        }
        return out
    }
}
