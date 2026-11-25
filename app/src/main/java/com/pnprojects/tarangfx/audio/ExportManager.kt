package com.pnprojects.tarangfx.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.pnprojects.tarangfx.data.db.OutputAudioEntity
import com.pnprojects.tarangfx.data.media.MediaStoreRepository
import com.pnprojects.tarangfx.data.media.OutputRepository
import com.pnprojects.tarangfx.data.media.RingtoneHelper
import com.pnprojects.tarangfx.data.media.RingtoneTarget
import com.pnprojects.tarangfx.data.media.SourceRegistry
import com.pnprojects.tarangfx.domain.model.AudioMetadata
import com.pnprojects.tarangfx.domain.model.Codec
import com.pnprojects.tarangfx.domain.model.EngineJson
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.domain.model.OutputCategory
import com.pnprojects.tarangfx.domain.model.Project
import com.pnprojects.tarangfx.domain.model.toEncoderJson
import com.pnprojects.tarangfx.domain.model.toEngineJson
import com.pnprojects.tarangfx.domain.model.toJson
import com.pnprojects.tarangfx.nativebridge.NativeAudio
import com.pnprojects.tarangfx.nativebridge.NativeProgressListener
import com.pnprojects.tarangfx.service.ExportForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ExportJob {
    val id: String
    val title: String
    val category: OutputCategory

    /** Renders a project graph (trim/merge/mix/fx/denoise/split segment/convert). */
    data class Render(
        override val title: String,
        override val category: OutputCategory,
        val project: Project,
        val config: ExportConfig,
        val startMs: Long = 0,
        val endMs: Long = 0,
        val ringtoneTarget: RingtoneTarget? = null,
        /** When set, tags + cover art are copied from this source (config.metadata overrides). */
        val copyTagsFrom: AudioFileRef? = null,
        override val id: String = UUID.randomUUID().toString(),
    ) : ExportJob

    /** Copies the audio stream out of a video without re-encoding. */
    data class Remux(
        override val title: String,
        val sourceUri: String,
        override val id: String = UUID.randomUUID().toString(),
    ) : ExportJob {
        override val category get() = OutputCategory.VIDEO_TO_AUDIO
    }

    /** Copies a file and writes new tags/cover into the copy (originals untouched). */
    data class Retag(
        override val title: String,
        val sourceUri: String,
        val extension: String,
        val metadata: AudioMetadata,
        val cover: ByteArray?,
        val coverMime: String?,
        val coverMode: Int,
        override val id: String = UUID.randomUUID().toString(),
    ) : ExportJob {
        override val category get() = OutputCategory.TAGS
    }
}

data class AudioFileRef(val uri: String, val displayName: String)

enum class JobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class JobStatus(
    val job: ExportJob,
    val state: JobState = JobState.QUEUED,
    val progress: Float = 0f,
    val error: String? = null,
    val output: OutputAudioEntity? = null,
)

/**
 * Sequential offline job queue executed inside [ExportForegroundService] so long renders and
 * AI denoising survive backgrounding. Progress is exposed for both UI dialogs and notifications.
 */
@Singleton
class ExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: SourceRegistry,
    private val outputs: OutputRepository,
    private val media: MediaStoreRepository,
    private val ringtones: RingtoneHelper,
) {
    private val _jobs = MutableStateFlow<List<JobStatus>>(emptyList())
    val jobs: StateFlow<List<JobStatus>> = _jobs.asStateFlow()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    fun enqueue(vararg newJobs: ExportJob) = enqueue(newJobs.toList())

    fun enqueue(newJobs: List<ExportJob>) {
        if (newJobs.isEmpty()) return
        _jobs.update { list -> list + newJobs.map { JobStatus(it) } }
        ContextCompat.startForegroundService(context, Intent(context, ExportForegroundService::class.java))
    }

    fun cancel(jobId: String) {
        cancelled += jobId
        _jobs.update { list -> list.map { if (it.job.id == jobId && it.state == JobState.QUEUED) it.copy(state = JobState.CANCELLED) else it } }
    }

    fun cancelAll() = _jobs.value.forEach { cancel(it.job.id) }

    fun clearFinished() = _jobs.update { list -> list.filter { it.state == JobState.QUEUED || it.state == JobState.RUNNING } }

    private fun set(id: String, f: (JobStatus) -> JobStatus) =
        _jobs.update { list -> list.map { if (it.job.id == id) f(it) else it } }

    /** Runs until no queued jobs remain. Called by the foreground service. */
    suspend fun processQueue() {
        while (true) {
            val next = _jobs.value.firstOrNull { it.state == JobState.QUEUED } ?: break
            val job = next.job
            set(job.id) { it.copy(state = JobState.RUNNING, progress = 0f) }
            val listener = NativeProgressListener { p ->
                set(job.id) { it.copy(progress = p) }
                job.id !in cancelled
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    when (job) {
                        is ExportJob.Render -> runRender(job, listener)
                        is ExportJob.Remux -> runRemux(job, listener)
                        is ExportJob.Retag -> runRetag(job)
                    }
                }
            }
            result.onSuccess { entity ->
                set(job.id) { it.copy(state = JobState.DONE, progress = 1f, output = entity) }
            }.onFailure { e ->
                val wasCancelled = job.id in cancelled
                set(job.id) {
                    it.copy(state = if (wasCancelled) JobState.CANCELLED else JobState.FAILED,
                        error = if (wasCancelled) null else (e.message ?: e.javaClass.simpleName))
                }
            }
            cancelled.remove(job.id)
        }
    }

    private suspend fun resolveFormat(project: Project, config: ExportConfig): Pair<Int, Int> {
        var rate = config.sampleRate
        var channels = config.channels
        if (rate <= 0 || channels <= 0) {
            val infos = project.allClips.map { it.uri }.distinct().mapNotNull { media.probe(it) }
            if (rate <= 0) rate = infos.maxOfOrNull { it.sampleRate }?.takeIf { it > 0 } ?: 48000
            if (channels <= 0) channels = (infos.maxOfOrNull { it.channels } ?: 2).coerceIn(1, 2)
        }
        return rate.coerceIn(8000, 96000) to channels.coerceIn(1, 2)
    }

    /**
     * Fails fast with a clear message when storage is too low. The render is staged in app storage
     * and then copied to shared storage, so roughly twice the output size is needed temporarily.
     */
    private fun ensureFreeSpace(estimatedBytes: Long, scratchBytes: Long = 0) {
        val dir = outputs.stagingDir()
        val free = android.os.StatFs(dir.absolutePath).availableBytes
        val needed = estimatedBytes * 2 + scratchBytes + 32L * 1024 * 1024
        if (free < needed) {
            error("Not enough free storage: about ${needed / 1_048_576} MB needed, ${free / 1_048_576} MB available")
        }
    }

    private suspend fun runRender(job: ExportJob.Render, listener: NativeProgressListener): OutputAudioEntity {
        val cfg = job.config
        val (rate, channels) = resolveFormat(job.project, cfg)
        val lengthMs = (if (job.endMs > 0) job.endMs else job.project.durationMs) - job.startMs
        // Loudness normalisation renders a float32 stereo temp file first (two-pass EBU R128).
        val scratch = if (job.project.master.normalizeLufs < 0f) (lengthMs / 1000.0 * rate * 2 * 4).toLong() else 0L
        ensureFreeSpace(estimateExportBytes(cfg, rate, channels, lengthMs), scratch)
        val out = File(outputs.stagingDir(), "${UUID.randomUUID()}.${cfg.codec.ext}")
        try {
            return renderAndPublish(job, listener, cfg, rate, channels, out)
        } finally {
            out.delete()  // staged copy is never left behind, whether publishing succeeded or failed
        }
    }

    private suspend fun renderAndPublish(
        job: ExportJob.Render, listener: NativeProgressListener, cfg: ExportConfig, rate: Int, channels: Int, out: File,
    ): OutputAudioEntity {
        val err = NativeAudio.render(
            job.project.toEngineJson(sources),
            cfg.toEncoderJson(rate, channels),
            out.absolutePath,
            job.startMs * 1000, job.endMs * 1000, rate,
            outputs.stagingDir().absolutePath,
            listener,
        )
        if (err != null) {
            out.delete()
            error(err)
        }
        var md = cfg.metadata
        var cover = cfg.coverArt
        var coverMime = cfg.coverMime
        job.copyTagsFrom?.let { ref ->
            sources.resolve(ref.uri)?.let { src ->
                NativeAudio.readTags(src, ref.displayName)?.let { json ->
                    val s = runCatching { tagJson.decodeFromString(AudioMetadata.serializer(), json) }.getOrNull()
                    if (s != null) md = AudioMetadata(
                        title = md.title.ifBlank { s.title }, artist = md.artist.ifBlank { s.artist },
                        album = md.album.ifBlank { s.album }, albumArtist = md.albumArtist.ifBlank { s.albumArtist },
                        year = if (md.year > 0) md.year else s.year, trackNumber = if (md.trackNumber > 0) md.trackNumber else s.trackNumber,
                        genre = md.genre.ifBlank { s.genre }, comment = md.comment.ifBlank { s.comment },
                    )
                    val mime = Json.parseToJsonElement(json).jsonObject["coverMime"]?.jsonPrimitive?.content
                    if (cover == null) NativeAudio.readCover(src, ref.displayName)?.let { cover = it; coverMime = mime?.ifBlank { null } ?: "image/jpeg" }
                }
            }
        }
        writeTagsIfAny(out, cfg.codec, md.copy(title = md.title.ifBlank { job.title }), cover, coverMime)
        val entity = outputs.publish(
            out, job.title, job.category, rate, cfg.bitrateKbps,
            baseDirectory = job.ringtoneTarget?.directory ?: android.os.Environment.DIRECTORY_MUSIC,
        )
        job.ringtoneTarget?.let { target ->
            ringtones.assign(Uri.parse(entity.contentUri), target).getOrThrow()
        }
        return entity
    }

    private fun writeTagsIfAny(file: File, codec: Codec, md: AudioMetadata, cover: ByteArray?, coverMime: String?) {
        if (codec == Codec.AAC) return  // raw ADTS has no tag container
        val err = NativeAudio.writeTags(file.absolutePath, md.toJson(), cover, coverMime, if (cover != null) 1 else 0)
        if (err != null) android.util.Log.w("ExportManager", "Tagging skipped: $err")
    }

    private suspend fun runRemux(job: ExportJob.Remux, listener: NativeProgressListener): OutputAudioEntity {
        val src = sources.resolve(job.sourceUri) ?: error("Cannot open video")
        val planJson = NativeAudio.planRemux(src) ?: error("Cannot read video")
        val plan = Json.parseToJsonElement(planJson).jsonObject
        val muxer = plan["muxer"]?.jsonPrimitive?.content.orEmpty()
        val ext = plan["extension"]?.jsonPrimitive?.content.orEmpty()
        if (muxer.isEmpty()) error("This video has no audio track")
        val out = File(outputs.stagingDir(), "${UUID.randomUUID()}.$ext")
        try {
            NativeAudio.remux(src, out.absolutePath, muxer, listener)?.let { error(it) }
            return outputs.publish(out, job.title, job.category, 0, 0)
        } finally {
            out.delete()
        }
    }

    private suspend fun runRetag(job: ExportJob.Retag): OutputAudioEntity {
        val out = File(outputs.stagingDir(), "${UUID.randomUUID()}.${job.extension}")
        context.contentResolver.openInputStream(Uri.parse(job.sourceUri))?.use { input ->
            out.outputStream().use { input.copyTo(it, 256 * 1024) }
        } ?: error("Cannot read source file")
        try {
            NativeAudio.writeTags(out.absolutePath, job.metadata.toJson(), job.cover, job.coverMime, job.coverMode)
                ?.let { error(it) }
            return outputs.publish(out, job.title, job.category, 0, 0)
        } finally {
            out.delete()
        }
    }

    private val tagJson = Json { ignoreUnknownKeys = true }
}

/**
 * Rough size of an export, used by the free-space guard. Lossy codecs are bitrate-bound; PCM is
 * exact; FLAC/ALAC assume a typical 0.6 compression ratio. Pure function so it is unit-tested.
 */
internal fun estimateExportBytes(cfg: ExportConfig, rate: Int, channels: Int, durationMs: Long): Long {
    val seconds = durationMs / 1000.0
    val bytesPerSec = when {
        cfg.codec.lossy -> cfg.bitrateKbps * 1000.0 / 8
        cfg.codec == Codec.WAV -> rate * channels * (cfg.bitDepth / 8.0)
        else -> rate * channels * (cfg.bitDepth / 8.0) * 0.6  // FLAC/ALAC typical ratio
    }
    return (seconds * bytesPerSec).toLong()
}
