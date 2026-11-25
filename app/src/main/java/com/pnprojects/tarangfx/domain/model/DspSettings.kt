package com.pnprojects.tarangfx.domain.model

import kotlinx.serialization.Serializable

/** Per-clip / master processing parameters (PRD §6.2). Field names match the native JSON parser. */
@Serializable
data class DspSettings(
    val eqEnabled: Boolean = false,
    val eqBands: List<Float> = List(10) { 0f },
    val bassBoostAmount: Float = 0f,
    val bassBoostFreq: Float = 80f,
    val reverbEnabled: Boolean = false,
    val reverbRoomSize: Float = 0.5f,
    val reverbDamping: Float = 0.5f,
    val reverbWetDry: Float = 0.25f,
    val reverbPreDelayMs: Float = 10f,
    val speed: Float = 1f,
    val pitchSemitones: Float = 0f,
    val preserveFormants: Boolean = true,
    val volumeGainDb: Float = 0f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val aiDenoiseMode: String = DenoiseMode.OFF,
    val aiDenoiseStrength: Float = 1f,
    val voiceEnhanceEnabled: Boolean = false,
    val normalizeLufs: Float = 0f,
    val bypass: Boolean = false,
) {
    val isDefault: Boolean get() = this == DspSettings()
    val hasFx: Boolean
        get() = copy(fadeInMs = 0, fadeOutMs = 0, bypass = false) != DspSettings()

    private val hasTimePitch get() = speed != 1f || pitchSemitones != 0f
    private val hasReverb get() = reverbEnabled && reverbWetDry > 0.0005f

    /**
     * True when switching from [other] to this needs the native clip renderer rebuilt (speed,
     * time-stretcher on/off, reverb tail on/off, denoiser model). Otherwise a cheap, glitch-free
     * parameter update is enough. Mirrors ClipRenderer::sameStructure in C++.
     */
    fun needsRebuildFrom(other: DspSettings): Boolean =
        speed != other.speed || hasTimePitch != other.hasTimePitch ||
            hasReverb != other.hasReverb || aiDenoiseMode != other.aiDenoiseMode

    companion object {
        val EQ_FREQUENCIES = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        fun bandLabel(hz: Int) = if (hz >= 1000) "${hz / 1000}k" else "$hz"
    }
}

object DenoiseMode {
    const val OFF = "off"
    const val STUDIO = "studio"
    const val FAST = "fast"
}

data class EqPreset(val id: Long, val name: String, val gains: List<Float>, val builtIn: Boolean)

object BuiltInEqPresets {
    val all = listOf(
        EqPreset(-1, "Flat", List(10) { 0f }, true),
        EqPreset(-2, "Bass Boost", listOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f), true),
        EqPreset(-3, "Vocal Clarity", listOf(-3f, -2f, -1f, 0f, 1f, 2f, 4f, 4f, 2f, 1f), true),
        EqPreset(-4, "Podcast", listOf(-6f, -3f, 0f, 1f, 2f, 3f, 3f, 2f, 0f, -1f), true),
        EqPreset(-5, "Rock", listOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f), true),
        EqPreset(-6, "Pop", listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f), true),
        EqPreset(-7, "Acoustic", listOf(4f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 2f, 1f), true),
        EqPreset(-8, "Treble Boost", listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f), true),
    )
}

data class ReverbPreset(val name: String, val room: Float, val damping: Float, val wet: Float, val preDelayMs: Float)

object ReverbPresets {
    val all = listOf(
        ReverbPreset("Small Room", 0.30f, 0.50f, 0.20f, 5f),
        ReverbPreset("Studio Booth", 0.18f, 0.70f, 0.14f, 2f),
        ReverbPreset("Concert Hall", 0.85f, 0.35f, 0.30f, 25f),
        ReverbPreset("Cathedral", 0.97f, 0.20f, 0.40f, 45f),
        ReverbPreset("Plate", 0.70f, 0.10f, 0.30f, 0f),
    )
}
