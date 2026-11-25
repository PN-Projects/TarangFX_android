package com.pnprojects.tarangfx.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Non-destructive "Project Graph" (PRD §4.1): only references source URIs, trim points and DSP.
 * Serialized to JSON for drafts (Room) and translated to the engine JSON for playback/export.
 */
@Serializable
data class Clip(
    val id: Long,
    val uri: String,
    val title: String,
    val sourceDurationMs: Long,
    val sourceStartMs: Long = 0,
    val sourceEndMs: Long = sourceDurationMs,
    val timelineStartMs: Long = 0,
    val gainDb: Float = 0f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val equalPowerFade: Boolean = false,
    val dsp: DspSettings = DspSettings(),
) {
    val sourceLengthMs: Long get() = (sourceEndMs - sourceStartMs).coerceAtLeast(0)
    val timelineLengthMs: Long get() = (sourceLengthMs / dsp.speed.coerceAtLeast(0.25f)).toLong()
    val timelineEndMs: Long get() = timelineStartMs + timelineLengthMs
}

@Serializable
data class Track(
    val id: Long,
    val clips: List<Clip> = emptyList(),
    val gainDb: Float = 0f,
    val muted: Boolean = false,
    val solo: Boolean = false,
)

@Serializable
data class Project(
    val tracks: List<Track> = emptyList(),
    val master: DspSettings = DspSettings(),
) {
    val durationMs: Long get() = tracks.flatMap { it.clips }.maxOfOrNull { it.timelineEndMs } ?: 0L
    val allClips: List<Clip> get() = tracks.flatMap { it.clips }

    companion object {
        fun single(clip: Clip, master: DspSettings = DspSettings()) =
            Project(tracks = listOf(Track(id = 1, clips = listOf(clip))), master = master)
    }
}

// ------------------------------------------------------------------ engine JSON (C++ ProjectModel)

@Serializable
private data class EngineClip(
    val id: Long, val source: String,
    val sourceStartUs: Long, val sourceEndUs: Long, val timelineStartUs: Long,
    val gainDb: Float, val fadeInUs: Long, val fadeOutUs: Long, val fadeCurve: String,
    val dsp: DspSettings,
)

@Serializable
private data class EngineTrack(val id: Long, val gainDb: Float, val muted: Boolean, val solo: Boolean, val clips: List<EngineClip>)

@Serializable
private data class EngineProject(val tracks: List<EngineTrack>, val master: DspSettings)

val EngineJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** Resolves a clip URI to a native source string ("fd:N" or a path). */
fun interface SourceResolver {
    fun resolve(uri: String): String?
}

private fun Clip.toEngine(resolver: SourceResolver): EngineClip? {
    val src = resolver.resolve(uri) ?: return null
    return EngineClip(
        id = id, source = src,
        sourceStartUs = sourceStartMs * 1000, sourceEndUs = sourceEndMs * 1000,
        timelineStartUs = timelineStartMs * 1000, gainDb = gainDb + dsp.volumeGainDb,
        fadeInUs = (if (fadeInMs > 0) fadeInMs else dsp.fadeInMs) * 1000,
        fadeOutUs = (if (fadeOutMs > 0) fadeOutMs else dsp.fadeOutMs) * 1000,
        fadeCurve = if (equalPowerFade) "equalPower" else "linear",
        // volume is folded into clip gain above; keep chain volume neutral to avoid double gain
        dsp = dsp.copy(volumeGainDb = 0f),
    )
}

fun Project.toEngineJson(resolver: SourceResolver): String =
    EngineJson.encodeToString(
        EngineProject.serializer(),
        EngineProject(
            tracks = tracks.map { t ->
                EngineTrack(t.id, t.gainDb, t.muted, t.solo, t.clips.mapNotNull { it.toEngine(resolver) })
            },
            master = master,
        ),
    )

fun Clip.toEngineJson(resolver: SourceResolver): String? =
    toEngine(resolver)?.let { EngineJson.encodeToString(EngineClip.serializer(), it) }

fun DspSettings.toEngineJson(): String = EngineJson.encodeToString(DspSettings.serializer(), this)
