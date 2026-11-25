package com.pnprojects.tarangfx.it

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pnprojects.tarangfx.domain.model.AudioMetadata
import com.pnprojects.tarangfx.domain.model.Clip
import com.pnprojects.tarangfx.domain.model.Codec
import com.pnprojects.tarangfx.domain.model.DenoiseMode
import com.pnprojects.tarangfx.domain.model.DspSettings
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.domain.model.Project
import com.pnprojects.tarangfx.domain.model.SourceResolver
import com.pnprojects.tarangfx.domain.model.Track
import com.pnprojects.tarangfx.domain.model.toEncoderJson
import com.pnprojects.tarangfx.domain.model.toEngineJson
import com.pnprojects.tarangfx.domain.model.toJson
import com.pnprojects.tarangfx.nativebridge.NativeAudio
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Kotlin -> JNI -> C++ engine -> FFmpeg/TagLib/ONNX, end to end, on the device. The application
 * class has already run nativeInit() with the bundled models when these tests start.
 */
@RunWith(AndroidJUnit4::class)
class NativeEngineIntegrationTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var src: File
    private lateinit var srcPcm: ShortArray
    private val pathResolver = SourceResolver { it }

    @Before
    fun setUp() {
        dir = File(ctx.cacheDir, "it-native").apply { deleteRecursively(); mkdirs() }
        src = File(dir, "sine.wav")
        srcPcm = TestAudio.writeSineWav(src, seconds = 3.0)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun probe(f: File) = Json.parseToJsonElement(NativeAudio.probe(f.absolutePath)!!).jsonObject

    private fun render(project: Project, cfg: ExportConfig, out: File, rate: Int = 44_100, channels: Int = 2, endUs: Long = 0): String? =
        NativeAudio.render(project.toEngineJson(pathResolver), cfg.toEncoderJson(rate, channels), out.absolutePath,
            0, endUs, rate, dir.absolutePath, null)

    private fun clip(start: Long = 0, end: Long = 3_000, dsp: DspSettings = DspSettings()) =
        Clip(id = 1, uri = src.absolutePath, title = "sine", sourceDurationMs = 3_000, sourceStartMs = start, sourceEndMs = end, dsp = dsp)

    @Test
    fun probeReportsFormat() {
        val o = probe(src)
        assertEquals(44_100, o["sampleRate"]!!.jsonPrimitive.int)
        assertEquals(2, o["channels"]!!.jsonPrimitive.int)
        assertEquals(3_000_000.0, o["durationUs"]!!.jsonPrimitive.long.toDouble(), 30_000.0)
    }

    @Test
    fun extractPeaksWritesOnePeakPer10ms() {
        val cache = File(dir, "p.peaks")
        val n = NativeAudio.extractPeaks(src.absolutePath, cache.absolutePath, 100) { true }
        assertEquals(300.0, n.toDouble(), 1.0)
        assertTrue(cache.length() > 300 * 4)
        // Cancelling from the progress callback aborts and leaves no partial cache behind.
        val cancelled = File(dir, "c.peaks")
        val long = File(dir, "long.wav").also { TestAudio.writeSineWav(it, seconds = 20.0) }
        val r = NativeAudio.extractPeaks(long.absolutePath, cancelled.absolutePath, 100) { false }
        assertTrue(r < 0)
        assertTrue(!cancelled.exists() && !File(cancelled.path + ".part").exists())
    }

    @Test
    fun trimExportIsSampleAccurate() {
        val out = File(dir, "trim.wav")
        val err = render(Project.single(clip(start = 1_000, end = 2_000)), ExportConfig(codec = Codec.WAV, bitDepth = 16), out)
        assertNull(err)
        val (ch, pcm) = TestAudio.readWav16(out)
        assertEquals(2, ch)
        assertEquals(44_100 * 2, pcm.size)  // exactly 1.000 s of stereo frames
        val offset = 44_100 * 2             // source starts at 1.000 s
        var maxDiff = 0
        for (i in pcm.indices) maxDiff = maxOf(maxDiff, abs(pcm[i] - srcPcm[offset + i]))
        assertTrue("max sample error $maxDiff LSB", maxDiff <= 1)  // <= 1 LSB (dither/rounding)
    }

    @Test
    fun speedTwoXHalvesDuration() {
        val out = File(dir, "fast.flac")
        assertNull(render(Project.single(clip(dsp = DspSettings(speed = 2f))), ExportConfig(codec = Codec.FLAC), out))
        assertEquals(1_500_000.0, probe(out)["durationUs"]!!.jsonPrimitive.long.toDouble(), 25_000.0)
    }

    @Test
    fun multitrackMixLengthAndFormats() {
        val a = clip()
        val b = clip().copy(id = 2, timelineStartMs = 2_000)  // ends at 5 s
        val project = Project(tracks = listOf(Track(1, listOf(a)), Track(2, listOf(b))))
        for (codec in listOf(Codec.MP3, Codec.M4A, Codec.OPUS, Codec.OGG)) {
            val out = File(dir, "mix.${codec.name.lowercase()}.${codec.ext}")
            val rate = if (codec == Codec.OPUS) 48_000 else 44_100
            assertNull(codec.name, render(project, ExportConfig(codec = codec, bitrateKbps = 128), out, rate = rate))
            val dur = probe(out)["durationUs"]!!.jsonPrimitive.long
            assertEquals("${codec.name} duration", 5_000_000.0, dur.toDouble(), 80_000.0)
        }
    }

    @Test
    fun tagsRoundTripThroughTagLib() {
        val out = File(dir, "tagged.flac")
        assertNull(render(Project.single(clip()), ExportConfig(codec = Codec.FLAC), out))
        val meta = AudioMetadata(title = "Integration Title", artist = "Tester", album = "IT", year = 2026, trackNumber = 7, genre = "Test")
        val cover = ByteArray(64) { it.toByte() }
        assertNull(NativeAudio.writeTags(out.absolutePath, meta.toJson(), cover, "image/png", 1))
        val t = Json.parseToJsonElement(NativeAudio.readTags(out.absolutePath, out.name)!!).jsonObject
        assertEquals("Integration Title", t["title"]!!.jsonPrimitive.content)
        assertEquals("Tester", t["artist"]!!.jsonPrimitive.content)
        assertEquals(2026, t["year"]!!.jsonPrimitive.int)
        assertEquals(7, t["trackNumber"]!!.jsonPrimitive.int)
        assertTrue(t["hasCover"]!!.jsonPrimitive.boolean)
        assertTrue(NativeAudio.readCover(out.absolutePath, out.name)!!.contentEquals(cover))
        // coverMode 2 removes the cover, keeps the text tags.
        assertNull(NativeAudio.writeTags(out.absolutePath, meta.toJson(), null, null, 2))
        val t2 = Json.parseToJsonElement(NativeAudio.readTags(out.absolutePath, out.name)!!).jsonObject
        assertTrue(!t2["hasCover"]!!.jsonPrimitive.boolean)
        assertEquals("Integration Title", t2["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun aiDenoiseModelsLoadAndPreserveLength() {
        assertTrue(NativeAudio.denoiseAvailable(DenoiseMode.FAST))
        assertTrue(NativeAudio.denoiseAvailable(DenoiseMode.STUDIO))
        for (mode in listOf(DenoiseMode.FAST, DenoiseMode.STUDIO)) {
            val out = File(dir, "dn-$mode.wav")
            val err = render(Project.single(clip(dsp = DspSettings(aiDenoiseMode = mode))), ExportConfig(codec = Codec.WAV), out, rate = 48_000)
            assertNull(mode, err)
            val (_, pcm) = TestAudio.readWav16(out)
            assertEquals("$mode keeps length", 48_000 * 3 * 2, pcm.size)
        }
    }

    @Test
    fun voiceEnhanceExportCompletesWithLoudnessNormalisation() {
        val out = File(dir, "ve.mp3")
        val master = DspSettings(voiceEnhanceEnabled = true, normalizeLufs = -14f)
        assertNull(render(Project.single(clip(), master), ExportConfig(codec = Codec.MP3), out))
        assertEquals(3_000_000.0, probe(out)["durationUs"]!!.jsonPrimitive.long.toDouble(), 60_000.0)
    }

    @Test
    fun badInputIsReportedNotCrashed() {
        val bogus = File(dir, "not-audio.mp3").apply { writeText("definitely not audio") }
        assertNull(NativeAudio.probe(bogus.absolutePath))
        val err = NativeAudio.render("{not json", ExportConfig().toEncoderJson(44_100, 2), File(dir, "x.mp3").absolutePath, 0, 0, 44_100, dir.absolutePath, null)
        assertTrue(err!!.startsWith("Bad project"))
    }
}
