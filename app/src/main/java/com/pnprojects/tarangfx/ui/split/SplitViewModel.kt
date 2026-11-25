package com.pnprojects.tarangfx.ui.split

import androidx.lifecycle.viewModelScope
import com.pnprojects.tarangfx.audio.ExportJob
import com.pnprojects.tarangfx.audio.ExportManager
import com.pnprojects.tarangfx.audio.PlaybackController
import com.pnprojects.tarangfx.audio.SelectionHolder
import com.pnprojects.tarangfx.data.media.WaveformRepository
import com.pnprojects.tarangfx.data.prefs.SettingsRepository
import com.pnprojects.tarangfx.domain.model.AudioFile
import com.pnprojects.tarangfx.domain.model.Clip
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.domain.model.OutputCategory
import com.pnprojects.tarangfx.domain.model.Project
import com.pnprojects.tarangfx.domain.model.Track
import com.pnprojects.tarangfx.domain.model.Waveform
import com.pnprojects.tarangfx.domain.undo.UndoRedoManager
import com.pnprojects.tarangfx.ui.EditorViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class Segment(val startMs: Long, val endMs: Long, val deleted: Boolean = false) {
    val lengthMs get() = endMs - startMs
}

data class SplitDoc(val segments: List<Segment>)

data class SplitUi(
    val file: AudioFile? = null,
    val waveform: Waveform? = null,
    val durationMs: Long = 0,
    val selected: Int = -1,
    val pxPerMs: Float = 0.1f,
    val coachStep: Int = -1,
)

@HiltViewModel
class SplitViewModel @Inject constructor(
    selection: SelectionHolder,
    private val waveforms: WaveformRepository,
    private val settings: SettingsRepository,
    playback: PlaybackController,
    exports: ExportManager,
) : EditorViewModel(playback, exports) {

    private val _ui = MutableStateFlow(SplitUi())
    val ui: StateFlow<SplitUi> = _ui.asStateFlow()
    private val history = UndoRedoManager(SplitDoc(emptyList()))
    val doc: StateFlow<SplitDoc> = history.state
    val canUndo = history.canUndo
    val canRedo = history.canRedo
    val activeCount: StateFlow<Int> = history.state.map { d -> d.segments.count { !it.deleted } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        selection.peek().firstOrNull()?.let { f ->
            val dur = f.durationMs.coerceAtLeast(1)
            _ui.value = SplitUi(file = f, durationMs = dur)
            history.reset(SplitDoc(listOf(Segment(0, dur))))
            viewModelScope.launch {
                playback.load(projectFor(history.current), owner)
                val exact = playback.durationMs.value
                if (exact > 0 && exact != dur) {
                    _ui.update { it.copy(durationMs = exact) }
                    history.reset(SplitDoc(listOf(Segment(0, exact))))
                }
                playback.setPlayRange(0, 0)
                playback.setSkipRange(0, 0)
                val wf = waveforms.load(f.uri, f.sizeBytes)
                _ui.update { it.copy(waveform = wf) }
                if (!settings.current().splitCoachSeen) _ui.update { it.copy(coachStep = 0) }
            }
            // Reload the engine whenever segments change (deleted parts become silent).
            history.state.drop(1).onEach { playback.update(projectFor(it)) }.launchIn(viewModelScope)
        }
    }

    private fun projectFor(d: SplitDoc): Project {
        val f = _ui.value.file ?: return Project()
        val dur = _ui.value.durationMs
        val clips = d.segments.mapIndexedNotNull { i, s ->
            if (s.deleted) null else Clip(i + 1L, f.uri, f.title, dur, s.startMs, s.endMs, s.startMs)
        }
        // Clips keep their original timeline positions, so deleted segments play back as silence.
        return Project(listOf(Track(1, clips)))
    }

    fun setZoom(pxPerMs: Float) = _ui.update { it.copy(pxPerMs = pxPerMs.coerceIn(0.005f, 2f)) }
    fun zoomIn() = setZoom(_ui.value.pxPerMs * 1.6f)
    fun zoomOut() = setZoom(_ui.value.pxPerMs / 1.6f)

    fun scrub(ms: Long) {
        val t = ms.coerceIn(0, _ui.value.durationMs)
        playback.seekTo(t)
    }

    fun selectAt(ms: Long) {
        val i = history.current.segments.indexOfFirst { ms >= it.startMs && ms < it.endMs }
        _ui.update { it.copy(selected = if (it.selected == i) -1 else i) }
    }

    fun split() {
        val t = positionMs.value
        val segs = history.current.segments
        val i = segs.indexOfFirst { t > it.startMs + 100 && t < it.endMs - 100 }
        if (i < 0) return
        val s = segs[i]
        val newSegs = segs.toMutableList().apply {
            removeAt(i)
            add(i, Segment(s.startMs, t, s.deleted))
            add(i + 1, Segment(t, s.endMs, s.deleted))
        }
        history.push("Split", SplitDoc(newSegs))
        _ui.update { it.copy(selected = i + 1) }
    }

    fun deleteSelected() {
        val i = _ui.value.selected
        val segs = history.current.segments
        if (i !in segs.indices || segs[i].deleted) return
        history.push("Delete", SplitDoc(segs.toMutableList().also { it[i] = it[i].copy(deleted = true) }))
        _ui.update { it.copy(selected = -1) }
    }

    fun undo() { history.undo(); _ui.update { it.copy(selected = -1) } }
    fun redo() { history.redo(); _ui.update { it.copy(selected = -1) } }

    fun jumpToStart() = playback.seekTo(0)

    fun nextCoach() {
        val n = _ui.value.coachStep + 1
        if (n >= 3) skipCoach() else _ui.update { it.copy(coachStep = n) }
    }

    fun skipCoach() {
        _ui.update { it.copy(coachStep = -1) }
        viewModelScope.launch { settings.setSplitCoachSeen() }
    }

    suspend fun defaultExportConfig(): ExportConfig {
        val st = settings.current()
        return ExportConfig(outputName = (_ui.value.file?.title ?: "audio"), codec = st.defaultCodec, bitrateKbps = st.defaultBitrate)
    }

    fun export(config: ExportConfig) {
        val f = _ui.value.file ?: return
        val dur = _ui.value.durationMs
        val base = config.outputName.ifBlank { f.title }
        val live = history.current.segments.filter { !it.deleted }
        val jobs = live.mapIndexed { n, s ->
            val name = if (live.size == 1) base else "${base}_part${n + 1}"
            ExportJob.Render(
                title = name, category = OutputCategory.SPLIT,
                project = Project.single(Clip(1, f.uri, f.title, dur, s.startMs, s.endMs, 0)),
                config = config.copy(outputName = name, metadata = config.metadata.copy(trackNumber = if (live.size > 1) n + 1 else config.metadata.trackNumber)),
            )
        }
        startExport(jobs)
    }
}
