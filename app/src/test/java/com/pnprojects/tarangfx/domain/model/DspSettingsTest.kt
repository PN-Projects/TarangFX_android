package com.pnprojects.tarangfx.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspSettingsTest {

    private val base = DspSettings()

    @Test
    fun `parameter-only changes do not rebuild the native renderer`() {
        assertFalse(base.copy(eqEnabled = true, eqBands = List(10) { 3f }).needsRebuildFrom(base))
        assertFalse(base.copy(bassBoostAmount = 0.7f).needsRebuildFrom(base))
        assertFalse(base.copy(volumeGainDb = -6f).needsRebuildFrom(base))
        assertFalse(base.copy(voiceEnhanceEnabled = true).needsRebuildFrom(base))
        assertFalse(base.copy(bypass = true).needsRebuildFrom(base))
        assertFalse(base.copy(aiDenoiseStrength = 0.4f).needsRebuildFrom(base))
        // Reverb already on: changing its amount is a parameter update.
        val rev = base.copy(reverbEnabled = true, reverbWetDry = 0.2f)
        assertFalse(rev.copy(reverbWetDry = 0.5f, reverbRoomSize = 0.9f).needsRebuildFrom(rev))
        // Pitch already engaged: changing it keeps the same time-stretcher.
        val p = base.copy(pitchSemitones = 2f)
        assertFalse(p.copy(pitchSemitones = 5f).needsRebuildFrom(p))
    }

    @Test
    fun `structural changes rebuild the native renderer`() {
        assertTrue(base.copy(speed = 1.5f).needsRebuildFrom(base))
        assertTrue(base.copy(pitchSemitones = 1f).needsRebuildFrom(base))            // stretcher on
        assertTrue(base.copy(reverbEnabled = true).needsRebuildFrom(base))           // reverb tail on
        assertTrue(base.copy(aiDenoiseMode = DenoiseMode.STUDIO).needsRebuildFrom(base))
        val fast = base.copy(aiDenoiseMode = DenoiseMode.FAST)
        assertTrue(fast.copy(aiDenoiseMode = DenoiseMode.STUDIO).needsRebuildFrom(fast))
    }

    @Test
    fun `hasFx ignores fades and bypass`() {
        assertFalse(base.hasFx)
        assertFalse(base.copy(fadeInMs = 500, fadeOutMs = 900, bypass = true).hasFx)
        assertTrue(base.copy(bassBoostAmount = 0.1f).hasFx)
        assertTrue(base.isDefault)
        assertFalse(base.copy(speed = 2f).isDefault)
    }

    @Test
    fun `built-in presets are 10-band and within the +-12 dB EQ range`() {
        assertEquals(10, DspSettings.EQ_FREQUENCIES.size)
        BuiltInEqPresets.all.forEach { p ->
            assertEquals(p.name, 10, p.gains.size)
            assertTrue(p.name, p.gains.all { it in -12f..12f })
            assertTrue(p.builtIn && p.id < 0)
        }
        assertEquals(BuiltInEqPresets.all.size, BuiltInEqPresets.all.map { it.id }.toSet().size)
        ReverbPresets.all.forEach { r ->
            assertTrue(r.name, r.room in 0f..1f && r.damping in 0f..1f && r.wet in 0f..1f && r.preDelayMs in 0f..250f)
        }
    }

    @Test
    fun `band labels`() {
        assertEquals("31", DspSettings.bandLabel(31))
        assertEquals("1k", DspSettings.bandLabel(1000))
        assertEquals("16k", DspSettings.bandLabel(16000))
    }
}
