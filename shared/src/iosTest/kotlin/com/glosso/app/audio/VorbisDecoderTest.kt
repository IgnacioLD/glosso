package com.glosso.app.audio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.isEqualToData
import platform.Foundation.timeIntervalSince1970
import platform.posix.getenv
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * stb_vorbis through cinterop on real content clips, served by the production
 * asset server (`/assets/content/en/audio/...`), and one stereo 48 kHz tone
 * made with ffmpeg. Expected values come from `ffprobe` (stream sample_rate,
 * channels, duration_ts), which the decoder must match to the frame.
 */
@OptIn(ExperimentalForeignApi::class)
class VorbisDecoderTest {

    private class Fixture(val file: String, val sampleRate: Int, val channels: Int, val frames: Long)

    private val fixtures = listOf(
        Fixture("en.word.city.014.en-US-female.ogg", 24_000, 1, 17_280), // 0.72 s
        Fixture("en.eh.ey.plans.003.en-GB-female.slow.ogg", 24_000, 1, 71_040), // 2.96 s
        Fixture("en.dialogue.job-interview.004.t4.en-GB-female.ogg", 24_000, 1, 84_480), // 3.52 s
        Fixture("tone-48k-stereo.ogg", 48_000, 2, 48_000), // 1.00 s, 440 Hz left, 660 Hz right
    )

    private fun fixtureDir(): String =
        getenv("GLOSSO_VORBIS_FIXTURES")?.toKString()
            ?: error("GLOSSO_VORBIS_FIXTURES is not set (the :shared iosSimulatorArm64Test task sets it)")

    private fun read(name: String): NSData =
        assertNotNull(NSData.dataWithContentsOfFile("${fixtureDir()}/$name"), "missing fixture $name")

    private fun bytes(name: String): ByteArray {
        val data = read(name)
        val pointer = data.bytes!!.reinterpret<kotlinx.cinterop.ByteVar>()
        return ByteArray(data.length.toInt()) { pointer[it] }
    }

    @Test
    fun decodesContentClipsAtTheirOwnRateChannelsAndLength() {
        for (fixture in fixtures) {
            val data = read(fixture.file)
            assertTrue(VorbisDecoder.isOgg(data), fixture.file)
            val began = NSDate().timeIntervalSince1970
            val audio = VorbisDecoder.decode(data)
            val millis = (NSDate().timeIntervalSince1970 - began) * 1000
            println("decoded ${fixture.file}: ${audio.sampleRate} Hz x${audio.channels}, ${audio.frames} frames in ${millis.toInt()} ms")
            assertTrue(audio is DecodedAudio.InMemory, fixture.file)
            assertEquals(fixture.sampleRate, audio.sampleRate, fixture.file)
            assertEquals(fixture.channels, audio.channels, fixture.file)
            assertTrue(kotlin.math.abs(audio.frames - fixture.frames) <= 1, "${fixture.file}: ${audio.frames} vs ${fixture.frames}")
            val wav = audio.wav
            assertEquals(44L + audio.frames * audio.channels * 2, wav.length.toLong(), fixture.file)
            checkWavHeader(wav, fixture.sampleRate, fixture.channels)
            for (channel in 0 until audio.channels) {
                val rms = rms(wav, audio.channels, channel)
                assertTrue(rms > 0.01, "${fixture.file} channel $channel is silent (RMS $rms)")
            }
        }
    }

    @Test
    fun spillsLongAudioToATemporaryWavWithTheSameSamples() {
        val data = read("en.dialogue.job-interview.004.t4.en-GB-female.ogg")
        val inMemory = VorbisDecoder.decode(data) as DecodedAudio.InMemory
        val onDisk = VorbisDecoder.decode(data, inMemoryMax = 64 * 1024)
        assertTrue(onDisk is DecodedAudio.OnDisk)
        try {
            assertEquals(inMemory.frames, onDisk.frames)
            val written = assertNotNull(NSData.dataWithContentsOfFile(onDisk.path))
            assertTrue(written.isEqualToData(inMemory.wav), "the spilled WAV differs from the in-memory one")
        } finally {
            SpillFiles.delete(onDisk.path)
        }
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(onDisk.path))
    }

    @Test
    fun truncatedStreamsFailCleanly() {
        val whole = bytes("en.dialogue.job-interview.004.t4.en-GB-female.ogg")
        assertNull(OggPages.problem(whole))
        // Inside the headers, mid-audio, and inside the last page (whose header is whole).
        for (cut in listOf(100, whole.size / 2, whole.size - 30)) {
            val truncated = whole.copyOf(cut)
            assertNotNull(OggPages.problem(truncated), "cut at $cut")
            assertFailsWith<VorbisException>("cut at $cut") { VorbisDecoder.decode(truncated) }
        }
    }

    @Test
    fun corruptedStreamsFailCleanly() {
        val whole = bytes("en.word.city.014.en-US-female.ogg")
        val flipped = whole.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x5A).toByte() }
        assertFailsWith<VorbisException> { VorbisDecoder.decode(flipped) }
        assertFailsWith<VorbisException> { VorbisDecoder.decode("OggS but nothing else".encodeToByteArray()) }
        assertFailsWith<VorbisException> { VorbisDecoder.decode(ByteArray(0)) }
        val notOgg = "RIFF....WAVEfmt ".encodeToByteArray()
        assertFalse(notOgg.usePinned { VorbisDecoder.isOgg(it.addressOf(0).reinterpret(), notOgg.size) })
    }

    @Test
    fun cacheEvictsTheLeastRecentlyUsedClipWithinItsBudget() {
        val clip = VorbisDecoder.decode(read("en.word.city.014.en-US-female.ogg")) as DecodedAudio.InMemory
        val cache = PcmCache(budgetBytes = clip.bytes * 2)
        cache.put("a", clip)
        cache.put("b", clip)
        assertNotNull(cache.get("a")) // a is now the most recent
        cache.put("c", clip)
        assertNull(cache.get("b"))
        assertNotNull(cache.get("a"))
        assertNotNull(cache.get("c"))
        assertEquals(2, cache.count)
        assertEquals(clip.bytes * 2, cache.sizeBytes)
        // A clip bigger than the whole budget is never kept.
        PcmCache(budgetBytes = clip.bytes - 1).apply {
            put("big", clip)
            assertEquals(0, count)
        }
    }

    /** Decode time and memory for a long clip given by GLOSSO_VORBIS_LONG (skipped when unset). */
    @Test
    fun measuresALongClipWhenGiven() {
        val path = getenv("GLOSSO_VORBIS_LONG")?.toKString() ?: return
        val data = assertNotNull(NSData.dataWithContentsOfFile(path))
        val before = MemoryProbe.footprint()
        val began = NSDate().timeIntervalSince1970
        val audio = VorbisDecoder.decode(data)
        val millis = (NSDate().timeIntervalSince1970 - began) * 1000
        val after = MemoryProbe.footprint()
        println(
            "long clip ${path.substringAfterLast('/')}: ${data.length} bytes Ogg, ${audio.sampleRate} Hz x${audio.channels}, " +
                "${audio.seconds} s, ${audio::class.simpleName}, decoded in ${millis.toInt()} ms, " +
                "phys_footprint ${before / 1024} KB -> ${after / 1024} KB",
        )
        if (audio is DecodedAudio.OnDisk) SpillFiles.delete(audio.path)
    }

    private fun checkWavHeader(wav: NSData, sampleRate: Int, channels: Int) {
        val p = wav.bytes!!.reinterpret<kotlinx.cinterop.UByteVar>()
        fun ascii(at: Int) = (0 until 4).map { p[at + it].toInt().toChar() }.joinToString("")
        fun le(at: Int, n: Int) = (0 until n).fold(0L) { acc, i -> acc or (p[at + i].toLong() shl (8 * i)) }
        assertEquals("RIFF", ascii(0))
        assertEquals("WAVE", ascii(8))
        assertEquals("fmt ", ascii(12))
        assertEquals(1L, le(20, 2))
        assertEquals(channels.toLong(), le(22, 2))
        assertEquals(sampleRate.toLong(), le(24, 4))
        assertEquals(16L, le(34, 2))
        assertEquals("data", ascii(36))
        assertEquals(wav.length.toLong() - 44, le(40, 4))
    }

    private fun rms(wav: NSData, channels: Int, channel: Int): Double {
        val samples = (wav.bytes!!.reinterpret<kotlinx.cinterop.ByteVar>() + 44)!!.reinterpret<ShortVar>()
        val frames = ((wav.length.toLong() - 44) / 2 / channels).toInt()
        var sum = 0.0
        for (i in 0 until frames) {
            val v = samples[i * channels + channel] / 32768.0
            sum += v * v
        }
        return sqrt(sum / frames)
    }
}
