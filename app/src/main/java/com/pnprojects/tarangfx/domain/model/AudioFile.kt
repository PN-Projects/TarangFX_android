package com.pnprojects.tarangfx.domain.model

import kotlinx.serialization.Serializable

/** A local media item (MediaStore row, SAF document, or app output). */
@Serializable
data class AudioFile(
    val uri: String,
    val title: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    val dateAddedSec: Long = 0,
    val folder: String = "",
    val isVideo: Boolean = false,
    val artist: String? = null,
) {
    val extension: String
        get() = displayName.substringAfterLast('.', "").lowercase().ifEmpty {
            when {
                mimeType == null -> ""
                mimeType.contains("mpeg") -> "mp3"
                mimeType.contains("mp4") || mimeType.contains("m4a") || mimeType.contains("aac") -> "m4a"
                mimeType.contains("flac") -> "flac"
                mimeType.contains("ogg") -> "ogg"
                mimeType.contains("opus") -> "opus"
                mimeType.contains("wav") -> "wav"
                else -> ""
            }
        }
    val formatLabel: String get() = extension.uppercase().ifEmpty { "AUDIO" }
}

@Serializable
data class MediaInfo(
    val durationUs: Long = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val bitRate: Long = 0,
    val codec: String = "",
    val container: String = "",
    val hasAudio: Boolean = false,
    val hasVideo: Boolean = false,
    val bitsPerSample: Int = 0,
)

/**
 * Peak data loaded from the native `.peaks` cache. Construct off the main thread: the
 * range-max index is built eagerly here (O(n)) so drawing never pays for it.
 */
class Waveform(val peaks: FloatArray, val peaksPerSecond: Int, val durationMs: Long) {
    val isEmpty: Boolean get() = peaks.isEmpty()
    private val rangeMax = PeakRangeMax(peaks)

    /** Loudest peak in `peaks[from until to]`, in O(log n) regardless of range length. */
    fun maxPeak(from: Int, to: Int): Float = rangeMax.max(from, to)
}
