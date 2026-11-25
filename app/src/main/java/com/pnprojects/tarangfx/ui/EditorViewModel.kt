package com.pnprojects.tarangfx.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pnprojects.tarangfx.audio.ExportJob
import com.pnprojects.tarangfx.audio.ExportManager
import com.pnprojects.tarangfx.audio.JobStatus
import com.pnprojects.tarangfx.audio.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Common plumbing for editor screens: engine ownership, transport state, export tracking. */
abstract class EditorViewModel(
    protected val playback: PlaybackController,
    protected val exports: ExportManager,
) : ViewModel() {

    protected val owner: String = "${javaClass.simpleName}@${System.identityHashCode(this)}"

    val positionMs: StateFlow<Long> = playback.positionMs
    val isPlaying: StateFlow<Boolean> = playback.isPlaying
    /** Timeline length as rendered by the engine (reflects speed changes). */
    val engineDurationMs: StateFlow<Long> = playback.durationMs

    private val exportIds = MutableStateFlow<Set<String>>(emptySet())
    val exportStatuses: StateFlow<List<JobStatus>> =
        combine(exports.jobs, exportIds) { jobs, ids -> jobs.filter { it.job.id in ids } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    protected fun startExport(jobs: List<ExportJob>) {
        if (jobs.isEmpty()) return
        playback.pause()
        exportIds.value = jobs.map { it.id }.toSet()
        exports.enqueue(jobs)
    }

    fun cancelExport() = exportIds.value.forEach(exports::cancel)

    fun dismissExport() {
        exportIds.value = emptySet()
        exports.clearFinished()
    }

    fun togglePlay() = playback.toggle()
    fun pause() = playback.pause()
    open fun seekTo(ms: Long) = playback.seekTo(ms)

    override fun onCleared() {
        playback.stopAndClear(owner)
        super.onCleared()
    }
}
