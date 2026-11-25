package com.pnprojects.tarangfx.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class AudioMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: Int = 0,
    val trackNumber: Int = 0,
    val genre: String = "",
    val comment: String = "",
)

enum class Codec(val label: String, val ext: String, val mime: String, val lossy: Boolean, val supportsBitDepth: Boolean) {
    MP3("MP3", "mp3", "audio/mpeg", true, false),
    AAC("AAC", "aac", "audio/aac", true, false),
    M4A("M4A", "m4a", "audio/mp4", true, false),
    FLAC("FLAC", "flac", "audio/flac", false, true),
    WAV("WAV", "wav", "audio/wav", false, true),
    OPUS("OPUS", "opus", "audio/ogg", true, false),
    OGG("OGG", "ogg", "audio/ogg", true, false),
    ALAC("ALAC", "m4a", "audio/mp4", false, true);

    /** Value understood by the native encoder. */
    val nativeName: String get() = name.lowercase()
}

/** PRD §6.2 ExportConfig. sampleRate 0 = original, channels 0 = original. */
@Serializable
data class ExportConfig(
    val outputName: String = "",
    val codec: Codec = Codec.MP3,
    val sampleRate: Int = 0,
    val bitrateKbps: Int = 192,
    val vbr: Boolean = false,
    val bitDepth: Int = 16,
    val channels: Int = 0,
    val metadata: AudioMetadata = AudioMetadata(),
    /** Optional cover art bytes (JPEG/PNG); not serialized into the engine config. */
    @kotlinx.serialization.Transient val coverArt: ByteArray? = null,
    @kotlinx.serialization.Transient val coverMime: String? = null,
) {
    companion object {
        val SAMPLE_RATES = listOf(0, 8000, 16000, 22050, 44100, 48000, 96000)
        val BITRATES = listOf(64, 96, 128, 192, 256, 320)
        val BIT_DEPTHS = listOf(16, 24, 32)
    }
}

@Serializable
private data class EncoderJson(
    val codec: String, val sampleRate: Int, val channels: Int,
    val bitrateKbps: Int, val vbr: Boolean, val bitDepth: Int, val metadata: AudioMetadata,
)

fun ExportConfig.toEncoderJson(resolvedRate: Int, resolvedChannels: Int): String =
    EngineJson.encodeToString(
        EncoderJson.serializer(),
        EncoderJson(codec.nativeName, resolvedRate, resolvedChannels, bitrateKbps, vbr, bitDepth, metadata),
    )

fun AudioMetadata.toJson(): String = EngineJson.encodeToString(AudioMetadata.serializer(), this)

/** Output categories (PRD §6.1) used by the Outputs filter chips. */
enum class OutputCategory(val label: String) {
    TRIM("Trim"), MERGE("Merge"), MIX("Mix"), SPLIT("Split"), CONVERT("Convert"),
    DENOISE("Denoise"), FX("FX"), RECORD("Record"), VIDEO_TO_AUDIO("Video"),
    RINGTONE("Ringtone"), TAGS("Tags"),
}
