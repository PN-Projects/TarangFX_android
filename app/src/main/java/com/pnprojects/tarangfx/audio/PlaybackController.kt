package com.pnprojects.tarangfx.audio

import com.pnprojects.tarangfx.data.media.SourceRegistry
import com.pnprojects.tarangfx.domain.model.Clip
import com.pnprojects.tarangfx.domain.model.DspSettings
import com.pnprojects.tarangfx.domain.model.Project
import com.pnprojects.tarangfx.domain.model.toEngineJson
import com.pnprojects.tarangfx.nativebridge.NativeAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kotlin facade over the native real-time engine. One project is "loaded" at a time (the active
 * editor's). All JNI calls run on a single-threaded dispatcher so they never block the UI thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PlaybackController @Inject constructor(
    private val sources: SourceRegistry,
) {
    private val engineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()
    private val _owner = MutableStateFlow<String?>(null)
    /** Identifies which screen/item owns the loaded project (e.g. a preview row id). */
    val owner: StateFlow<String?> = _owner.asStateFlow()

    private var ticker: Job? = null

    suspend fun load(project: Project, owner: String): String? = withContext(engineDispatcher) {
        val err = NativeAudio.engineSetProject(project.toEngineJson(sources))
        _owner.value = owner
        _durationMs.value = NativeAudio.engineDuration() / 1000
        _positionMs.value = NativeAudio.enginePosition() / 1000
        err
    }

    /** Structural update while keeping the playhead (clip moved, trimmed, added, removed). */
    fun update(project: Project) {
        scope.launch {
            NativeAudio.engineSetProject(project.toEngineJson(sources))
            _durationMs.value = NativeAudio.engineDuration() / 1000
        }
    }

    /** Parameter-only update for one clip (EQ/volume/fades/pitch): glitch-free, no reload. */
    fun updateClip(clip: Clip) {
        scope.launch { clip.toEngineJson(sources)?.let { NativeAudio.engineUpdateClip(it) } }
    }

    fun setMasterDsp(dsp: DspSettings) {
        scope.launch { NativeAudio.engineSetMasterDsp(dsp.toEngineJson()) }
    }

    fun setTrackState(trackId: Long, gainDb: Float, muted: Boolean, solo: Boolean) {
        scope.launch { NativeAudio.engineSetTrackState(trackId, gainDb, muted, solo) }
    }

    fun setPlayRange(startMs: Long, endMs: Long, loop: Boolean = false) {
        scope.launch { NativeAudio.engineSetPlayRange(startMs * 1000, if (endMs > 0) endMs * 1000 else 0, loop) }
    }

    fun setSkipRange(startMs: Long, endMs: Long) {
        scope.launch { NativeAudio.engineSetSkipRange(if (endMs > startMs) startMs * 1000 else -1, endMs * 1000) }
    }

    fun play() {
        scope.launch {
            NativeAudio.enginePlay()
            _isPlaying.value = NativeAudio.engineIsPlaying()
            startTicker()
        }
    }

    fun pause() {
        scope.launch {
            NativeAudio.enginePause()
            _isPlaying.value = false
            _positionMs.value = NativeAudio.enginePosition() / 1000
        }
    }

    fun toggle() = if (_isPlaying.value) pause() else play()

    fun seekTo(ms: Long) {
        _positionMs.value = ms.coerceAtLeast(0)
        scope.launch {
            NativeAudio.engineSeek(ms.coerceAtLeast(0) * 1000)
            _positionMs.value = NativeAudio.enginePosition() / 1000
        }
    }

    fun stopAndClear(owner: String? = null) {
        if (owner != null && _owner.value != owner) return
        scope.launch {
            NativeAudio.enginePause()
            NativeAudio.engineClear()
            _isPlaying.value = false
            _positionMs.value = 0
            _durationMs.value = 0
            _owner.value = null
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        // ~60 Hz position polling while playing; stops itself when playback ends.
        ticker = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                val playing = NativeAudio.engineIsPlaying()
                _positionMs.value = NativeAudio.enginePosition() / 1000
                if (!playing) {
                    if (_isPlaying.value) {
                        _isPlaying.value = false
                        scope.launch { NativeAudio.enginePause() }  // stop the stream (battery)
                    }
                    break
                }
                _isPlaying.value = true
                delay(16)
            }
        }
    }
}
