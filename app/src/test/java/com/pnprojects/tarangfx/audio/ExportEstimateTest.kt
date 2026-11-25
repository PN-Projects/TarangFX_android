package com.pnprojects.tarangfx.audio

import com.pnprojects.tarangfx.domain.model.Codec
import com.pnprojects.tarangfx.domain.model.ExportConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportEstimateTest {

    @Test
    fun `lossy size is bitrate times duration`() {
        // 192 kbps * 60 s = 1 440 000 bytes, independent of rate/channels.
        assertEquals(1_440_000L, estimateExportBytes(ExportConfig(codec = Codec.MP3, bitrateKbps = 192), 44_100, 2, 60_000))
        assertEquals(1_440_000L, estimateExportBytes(ExportConfig(codec = Codec.OPUS, bitrateKbps = 192), 48_000, 1, 60_000))
    }

    @Test
    fun `wav size is exact pcm size`() {
        // 48 kHz * 2 ch * 3 bytes * 10 s
        assertEquals(2_880_000L, estimateExportBytes(ExportConfig(codec = Codec.WAV, bitDepth = 24), 48_000, 2, 10_000))
        // One hour of 16-bit stereo 44.1 kHz = 635 MB: the guard must see the real number.
        assertEquals(635_040_000L, estimateExportBytes(ExportConfig(codec = Codec.WAV, bitDepth = 16), 44_100, 2, 3_600_000))
    }

    @Test
    fun `lossless compressed codecs assume a 0_6 ratio`() {
        val wav = estimateExportBytes(ExportConfig(codec = Codec.WAV, bitDepth = 16), 44_100, 2, 60_000)
        val flac = estimateExportBytes(ExportConfig(codec = Codec.FLAC, bitDepth = 16), 44_100, 2, 60_000)
        val alac = estimateExportBytes(ExportConfig(codec = Codec.ALAC, bitDepth = 16), 44_100, 2, 60_000)
        assertEquals((wav * 0.6).toLong(), flac)
        assertEquals(flac, alac)
        assertTrue(estimateExportBytes(ExportConfig(), 44_100, 2, 0) == 0L)
    }
}
