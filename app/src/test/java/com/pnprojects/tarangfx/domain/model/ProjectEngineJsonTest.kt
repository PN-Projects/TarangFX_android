package com.pnprojects.tarangfx.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin -> C++ contract: field names and units the native ProjectModel parser expects. */
class ProjectEngineJsonTest {

    private val resolver = SourceResolver { uri -> if (uri.startsWith("content://ok")) "fd:${uri.length}" else null }

    private fun clip(id: Long, uri: String = "content://ok/$id", start: Long = 0, len: Long = 10_000, dsp: DspSettings = DspSettings()) =
        Clip(id = id, uri = uri, title = "c$id", sourceDurationMs = 60_000, sourceStartMs = 1_000, sourceEndMs = 1_000 + len,
            timelineStartMs = start, dsp = dsp)

    @Test
    fun `timeline length follows speed and project duration is the latest clip end`() {
        val a = clip(1, start = 0, len = 10_000, dsp = DspSettings(speed = 2f))     // 5 s on the timeline
        val b = clip(2, start = 3_000, len = 4_000)                                  // ends at 7 s
        assertEquals(5_000, a.timelineLengthMs)
        val p = Project(tracks = listOf(Track(1, listOf(a)), Track(2, listOf(b))))
        assertEquals(7_000, p.durationMs)
        assertEquals(2, p.allClips.size)
        assertEquals(0, Project().durationMs)
        // Speed is clamped at 0.25x so a bad value can't explode the length.
        assertEquals(40_000, clip(3, len = 10_000, dsp = DspSettings(speed = 0.01f)).timelineLengthMs)
    }

    @Test
    fun `engine json uses microseconds, folds volume into gain and drops unresolvable clips`() {
        val c = clip(7, start = 2_500, dsp = DspSettings(volumeGainDb = -3f, fadeInMs = 200)).copy(gainDb = 1.5f, fadeOutMs = 400)
        val missing = clip(8, uri = "content://gone/8")
        val json = Project(tracks = listOf(Track(1, listOf(c, missing), gainDb = -1f, muted = true))).toEngineJson(resolver)
        val root = Json.parseToJsonElement(json).jsonObject
        val track = root["tracks"]!!.jsonArray.single().jsonObject
        assertEquals(-1f, track["gainDb"]!!.jsonPrimitive.float)
        assertTrue(track["muted"]!!.jsonPrimitive.boolean)
        val clips = track["clips"]!!.jsonArray
        assertEquals("unresolvable source must be skipped", 1, clips.size)
        val e = clips.single().jsonObject
        assertEquals("fd:${"content://ok/7".length}", e["source"]!!.jsonPrimitive.content)
        assertEquals(1_000_000L, e["sourceStartUs"]!!.jsonPrimitive.long)
        assertEquals(11_000_000L, e["sourceEndUs"]!!.jsonPrimitive.long)
        assertEquals(2_500_000L, e["timelineStartUs"]!!.jsonPrimitive.long)
        assertEquals(-1.5f, e["gainDb"]!!.jsonPrimitive.float, 1e-6f)          // 1.5 + (-3)
        assertEquals(200_000L, e["fadeInUs"]!!.jsonPrimitive.long)             // from dsp (clip has none)
        assertEquals(400_000L, e["fadeOutUs"]!!.jsonPrimitive.long)            // clip-level wins
        assertEquals("linear", e["fadeCurve"]!!.jsonPrimitive.content)
        val dsp = e["dsp"] as JsonObject
        assertEquals("volume must not be applied twice", 0f, dsp["volumeGainDb"]!!.jsonPrimitive.float)
    }

    @Test
    fun `dsp json carries every field the native parser reads`() {
        val json = DspSettings(eqEnabled = true, speed = 1.25f, aiDenoiseMode = DenoiseMode.FAST, normalizeLufs = -14f).toEngineJson()
        val o = Json.parseToJsonElement(json).jsonObject
        listOf(
            "eqEnabled", "eqBands", "bassBoostAmount", "bassBoostFreq", "reverbEnabled", "reverbRoomSize",
            "reverbDamping", "reverbWetDry", "reverbPreDelayMs", "speed", "pitchSemitones", "preserveFormants",
            "volumeGainDb", "aiDenoiseMode", "aiDenoiseStrength", "voiceEnhanceEnabled", "normalizeLufs", "bypass",
        ).forEach { assertTrue("missing $it", o.containsKey(it)) }
        assertEquals(10, o["eqBands"]!!.jsonArray.size)
        assertEquals("fast", o["aiDenoiseMode"]!!.jsonPrimitive.content)
        assertEquals(-14f, o["normalizeLufs"]!!.jsonPrimitive.float)
    }

    @Test
    fun `project survives a draft round trip`() {
        val p = Project(
            tracks = listOf(Track(1, listOf(clip(1), clip(2, start = 12_000).copy(equalPowerFade = true)), solo = true)),
            master = DspSettings(voiceEnhanceEnabled = true, normalizeLufs = -14f),
        )
        val back = EngineJson.decodeFromString(Project.serializer(), EngineJson.encodeToString(Project.serializer(), p))
        assertEquals(p, back)
    }

    @Test
    fun `encoder json`() {
        val cfg = ExportConfig(codec = Codec.FLAC, bitDepth = 24, bitrateKbps = 320, vbr = true,
            metadata = AudioMetadata(title = "T", artist = "A", trackNumber = 3))
        val o = Json.parseToJsonElement(cfg.toEncoderJson(48_000, 1)).jsonObject
        assertEquals("flac", o["codec"]!!.jsonPrimitive.content)
        assertEquals(48_000, o["sampleRate"]!!.jsonPrimitive.int)
        assertEquals(1, o["channels"]!!.jsonPrimitive.int)
        assertEquals(24, o["bitDepth"]!!.jsonPrimitive.int)
        assertEquals("T", o["metadata"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(3, o["metadata"]!!.jsonObject["trackNumber"]!!.jsonPrimitive.int)
        // Native encoder names are lower-case enum names; ALAC shares the .m4a container with M4A.
        assertEquals(listOf("mp3", "aac", "m4a", "flac", "wav", "opus", "ogg", "alac"), Codec.entries.map { it.nativeName })
        assertEquals(Codec.M4A.ext, Codec.ALAC.ext)
    }
}
