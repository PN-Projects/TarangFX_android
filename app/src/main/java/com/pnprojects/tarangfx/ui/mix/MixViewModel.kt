package com.pnprojects.tarangfx.ui.mix

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.pnprojects.tarangfx.audio.ExportJob
import com.pnprojects.tarangfx.audio.ExportManager
import com.pnprojects.tarangfx.audio.PlaybackController
import com.pnprojects.tarangfx.audio.SelectionHolder
import com.pnprojects.tarangfx.data.db.EqPresetRepository
import com.pnprojects.tarangfx.data.db.ProjectDraftDao
import com.pnprojects.tarangfx.data.db.ProjectDraftEntity
import com.pnprojects.tarangfx.data.media.WaveformRepository
import com.pnprojects.tarangfx.data.prefs.SettingsRepository
import com.pnprojects.tarangfx.domain.model.AudioFile
import com.pnprojects.tarangfx.domain.model.Clip
import com.pnprojects.tarangfx.domain.model.Codec
import com.pnprojects.tarangfx.domain.model.DspSettings
import com.pnprojects.tarangfx.domain.model.EngineJson
import com.pnprojects.tarangfx.domain.model.EqPreset
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.domain.model.OutputCategory
import com.pnprojects.tarangfx.domain.model.Project
import com.pnprojects.tarangfx.domain.model.Track
import com.pnprojects.tarangfx.domain.model.Waveform
import com.pnprojects.tarangfx.domain.model.toEncoderJson
import com.pnprojects.tarangfx.domain.undo.UndoRedoManager
import com.pnprojects.tarangfx.nativebridge.NativeAudio
import com.pnprojects.tarangfx.ui.EditorViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class MixUi(
    val selectedClipId: Long? = null,
    val pxPerMs: Float = 0.05f,
    val waveforms: Map<String, Waveform> = emptyMap(),
    val recording: Boolean = false,
    val recordStartMs: Long = 0,
    val recordLevel: Float = 0f,
    val recordElapsedMs: Long = 0,
    val error: String? = null,
    val draftOffer: ProjectDraftEntity? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class MixViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val selection: SelectionHolder,
    private val waveforms: WaveformRepository,
    private val settings: SettingsRepository,
    private val presets: EqPresetRepository,
    private val drafts: ProjectDraftDao,
    playback: PlaybackController,
    exports: ExportManager,
) : EditorViewModel(playback, exports) {

    private var nextId = 1L
    private val history = UndoRedoManager(Project())
    val project: StateFlow<Project> = history.state
    val canUndo = history.canUndo
    val canRedo = history.canRedo
    private val _ui = MutableStateFlow(MixUi())
    val ui: StateFlow<MixUi> = _ui.asStateFlow()
    val userPresets: StateFlow<List<EqPreset>> = presets.userPresets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Declared before init{}: Kotlin runs initializers in declaration order.
    private var lastSynced = Project()
    private var dragging = false
    private var draftId: Long = 0
    private var pendingLane: Int? = null
    private var recordFile: File? = null
    private var recordJob: Job? = null

    init {
        val files = selection.peek()
        history.reset(Project(tracks = files.mapIndexed { i, f -> Track(id = i + 1L, clips = listOf(newClip(f, 0))) }))
        viewModelScope.launch {
            lastSynced = history.current
            playback.load(history.current, owner)
            files.forEach { loadWaveform(it.uri, it.sizeBytes) }
            val latest = runCatching { drafts.observeByTool("MIX").first() }.getOrNull()?.firstOrNull()
            if (latest != null) _ui.update { it.copy(draftOffer = latest) }
        }
        history.state.drop(1).onEach { if (!dragging) sync(it) }.launchIn(viewModelScope)
        history.state.drop(1).debounce(1500).onEach { autosave(it) }.launchIn(viewModelScope)
        selection.appended.filter { it.isNotEmpty() }.onEach { addFiles(selection.consumeAppend()) }.launchIn(viewModelScope)
    }

    private fun newClip(f: AudioFile, startMs: Long) = Clip(
        id = nextId++, uri = f.uri, title = f.title, sourceDurationMs = f.durationMs.coerceAtLeast(1), timelineStartMs = startMs,
    )

    // ------------------------------------------------------------------ engine sync
    private fun structureKey(p: Project) = p.tracks.map { t ->
        t.id to t.clips.map { c ->
            listOf(c.id, c.uri, c.sourceStartMs, c.sourceEndMs, c.timelineStartMs, c.dsp.speed,
                c.dsp.speed != 1f || c.dsp.pitchSemitones != 0f,
                c.dsp.reverbEnabled && c.dsp.reverbWetDry > 0.0005f, c.dsp.aiDenoiseMode)
        }
    }

    /** Structural edits reload the engine; parameter-only edits are sent as glitch-free updates. */
    private fun sync(p: Project) {
        val prev = lastSynced
        lastSynced = p
        if (structureKey(prev) != structureKey(p)) {
            playback.update(p)
            return
        }
        val prevClips = prev.allClips.associateBy { it.id }
        p.allClips.forEach { c -> if (prevClips[c.id] != c) playback.updateClip(c) }
        p.tracks.forEachIndexed { i, t ->
            val o = prev.tracks.getOrNull(i)
            if (o != null && (o.muted != t.muted || o.solo != t.solo || o.gainDb != t.gainDb)) {
                playback.setTrackState(t.id, t.gainDb, t.muted, t.solo)
            }
        }
    }

    private fun loadWaveform(uri: String, size: Long) {
        if (_ui.value.waveforms.containsKey(uri)) return
        viewModelScope.launch {
            waveforms.load(uri, size)?.let { wf -> _ui.update { it.copy(waveforms = it.waveforms + (uri to wf)) } }
        }
    }

    // ------------------------------------------------------------------ drafts
    private suspend fun autosave(p: Project) {
        if (p.allClips.isEmpty()) return
        val json = EngineJson.encodeToString(Project.serializer(), p)
        draftId = drafts.upsert(ProjectDraftEntity(id = draftId, name = "Mix draft", toolType = "MIX", serializedGraphJson = json, updatedAt = System.currentTimeMillis()))
    }

    fun resumeDraft() {
        val d = _ui.value.draftOffer ?: return
        _ui.update { it.copy(draftOffer = null) }
        val p = runCatching { EngineJson.decodeFromString(Project.serializer(), d.serializedGraphJson) }.getOrNull() ?: return
        draftId = d.id
        nextId = (p.allClips.maxOfOrNull { it.id } ?: 0) + 1
        history.reset(p)
        lastSynced = p
        playback.update(p)
        p.allClips.forEach { loadWaveform(it.uri, 0) }
    }

    fun dismissDraft() = _ui.update { it.copy(draftOffer = null) }

    // ------------------------------------------------------------------ editing
    private fun edit(label: String, f: (Project) -> Project) = history.push(label, f(history.current))

    private fun mapClip(id: Long, f: (Clip) -> Clip): Project =
        history.current.copy(tracks = history.current.tracks.map { t -> t.copy(clips = t.clips.map { if (it.id == id) f(it) else it }) })

    val selectedClip: Clip? get() = _ui.value.selectedClipId?.let { id -> history.current.allClips.firstOrNull { it.id == id } }

    fun select(id: Long?) = _ui.update { it.copy(selectedClipId = id) }

    /** Called when the user taps "+" on an empty lane or "Add audio"; files arrive via [SelectionHolder]. */
    fun requestAdd(lane: Int?) { pendingLane = lane }

    private fun addFiles(files: List<AudioFile>) {
        if (files.isEmpty()) return
        val at = positionMs.value
        var p = history.current
        files.forEachIndexed { i, f ->
            val lane = if (i == 0) pendingLane else null
            p = if (lane != null && lane < p.tracks.size && p.tracks[lane].clips.isEmpty()) {
                p.copy(tracks = p.tracks.mapIndexed { idx, t -> if (idx == lane) t.copy(clips = listOf(newClip(f, at))) else t })
            } else {
                val firstEmpty = p.tracks.indexOfFirst { it.clips.isEmpty() }
                if (firstEmpty >= 0) p.copy(tracks = p.tracks.mapIndexed { idx, t -> if (idx == firstEmpty) t.copy(clips = listOf(newClip(f, at))) else t })
                else p.copy(tracks = p.tracks + Track(id = (p.tracks.maxOfOrNull { it.id } ?: 0) + 1, clips = listOf(newClip(f, at))))
            }
            loadWaveform(f.uri, f.sizeBytes)
        }
        pendingLane = null
        history.push("Add audio", p)
    }

    fun moveClip(id: Long, newStartMs: Long, toLane: Int?, commit: Boolean) {
        var p = history.current
        val clip = p.allClips.firstOrNull { it.id == id } ?: return
        val moved = clip.copy(timelineStartMs = newStartMs.coerceAtLeast(0))
        p = p.copy(tracks = p.tracks.map { t -> t.copy(clips = t.clips.filterNot { it.id == id }) })
        val fromLane = history.current.tracks.indexOfFirst { t -> t.clips.any { it.id == id } }
        var lane = toLane ?: fromLane
        while (lane >= p.tracks.size) p = p.copy(tracks = p.tracks + Track((p.tracks.maxOfOrNull { it.id } ?: 0) + 1))
        lane = lane.coerceAtLeast(0)
        p = p.copy(tracks = p.tracks.mapIndexed { i, t -> if (i == lane) t.copy(clips = (t.clips + moved).sortedBy { it.timelineStartMs }) else t })
        if (commit) history.push("Move clip", p) else history.replace(p)
    }

    private var dragOrigin: Project? = null
    fun beginDrag() {
        dragOrigin = history.current
        dragging = true
    }
    fun endDrag() {
        val origin = dragOrigin ?: return
        val now = history.current
        dragOrigin = null
        dragging = false
        history.replace(origin)
        history.push("Move clip", now)  // single undo entry + single engine reload
        if (now == origin) sync(now)
    }

    fun splitSelected() {
        val c = selectedClip ?: return
        val t = positionMs.value
        if (t <= c.timelineStartMs + 50 || t >= c.timelineEndMs - 50) return
        val srcSplit = c.sourceStartMs + ((t - c.timelineStartMs) * c.dsp.speed).toLong()
        val left = c.copy(sourceEndMs = srcSplit, fadeOutMs = 0)
        val right = c.copy(id = nextId++, sourceStartMs = srcSplit, timelineStartMs = t, fadeInMs = 0)
        edit("Split") { p -> p.copy(tracks = p.tracks.map { tr -> tr.copy(clips = tr.clips.flatMap { if (it.id == c.id) listOf(left, right) else listOf(it) }) }) }
        select(right.id)
    }

    fun deleteSelected() {
        val id = _ui.value.selectedClipId ?: return
        edit("Delete") { p -> p.copy(tracks = p.tracks.map { t -> t.copy(clips = t.clips.filterNot { it.id == id }) }) }
        select(null)
    }

    fun duplicateSelected() {
        val c = selectedClip ?: return
        val copy = c.copy(id = nextId++, timelineStartMs = c.timelineEndMs)
        edit("Duplicate") { p -> p.copy(tracks = p.tracks.map { t -> if (t.clips.any { it.id == c.id }) t.copy(clips = t.clips + copy) else t }) }
        select(copy.id)
    }

    fun setClipGain(db: Float, commit: Boolean) = updateSelected("Volume", commit) { it.copy(gainDb = db) }
    fun setClipFades(inMs: Long, outMs: Long, commit: Boolean) = updateSelected("Fade", commit) { it.copy(fadeInMs = inMs, fadeOutMs = outMs) }
    fun setClipDsp(dsp: DspSettings) = updateSelected("Effects", true) { it.copy(dsp = dsp) }

    private fun updateSelected(label: String, commit: Boolean, f: (Clip) -> Clip) {
        val id = _ui.value.selectedClipId ?: return
        val p = mapClip(id, f)
        if (commit) history.push(label, p) else history.replace(p)
    }

    fun setTrackFlags(index: Int, muted: Boolean? = null, solo: Boolean? = null) {
        edit("Track") { p ->
            p.copy(tracks = p.tracks.mapIndexed { i, t -> if (i == index) t.copy(muted = muted ?: t.muted, solo = solo ?: t.solo) else t })
        }
    }

    fun removeTrack(index: Int) = edit("Remove track") { p -> p.copy(tracks = p.tracks.filterIndexed { i, _ -> i != index }) }

    fun undo() = history.undo()
    fun redo() = history.redo()

    fun setZoom(pxPerMs: Float) = _ui.update { it.copy(pxPerMs = pxPerMs.coerceIn(0.002f, 1.5f)) }
    fun zoomIn() = setZoom(_ui.value.pxPerMs * 1.6f)
    fun zoomOut() = setZoom(_ui.value.pxPerMs / 1.6f)

    fun savePreset(name: String, gains: List<Float>) = viewModelScope.launch { presets.save(name, gains) }
    fun deletePreset(p: EqPreset) = viewModelScope.launch { presets.delete(p) }

    // ------------------------------------------------------------------ live overdub recording
    fun startRecording() {
        if (_ui.value.recording) return
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "mix_take_${System.currentTimeMillis()}.wav")
        val start = positionMs.value
        viewModelScope.launch {
            val cfg = ExportConfig(codec = Codec.WAV, bitDepth = 24, sampleRate = 48000, channels = 1)
            val err = withContext(Dispatchers.IO) { NativeAudio.recStart(file.absolutePath, cfg.toEncoderJson(48000, 1), 1, false) }
            if (err != null) {
                _ui.update { it.copy(error = err) }
                return@launch
            }
            recordFile = file
            _ui.update { it.copy(recording = true, recordStartMs = start, recordElapsedMs = 0) }
            if (!isPlaying.value) playback.play()
            recordJob = viewModelScope.launch {
                while (isActive) {
                    _ui.update { it.copy(recordLevel = NativeAudio.recLevel(), recordElapsedMs = NativeAudio.recPosition() / 1000) }
                    delay(50)
                }
            }
        }
    }

    fun stopRecording() {
        if (!_ui.value.recording) return
        recordJob?.cancel()
        playback.pause()
        viewModelScope.launch {
            val durUs = withContext(Dispatchers.IO) { NativeAudio.recStop() }
            val file = recordFile
            _ui.update { it.copy(recording = false, recordLevel = 0f) }
            if (durUs <= 0 || file == null) {
                _ui.update { it.copy(error = "Recording failed") }
                return@launch
            }
            val f = AudioFile(file.absolutePath, "Take ${history.current.tracks.size + 1}", file.name, durUs / 1000, file.length(), "audio/wav")
            val p = history.current
            val clip = newClip(f, _ui.value.recordStartMs)
            history.push("Record", p.copy(tracks = p.tracks + Track((p.tracks.maxOfOrNull { it.id } ?: 0) + 1, listOf(clip))))
            loadWaveform(f.uri, f.sizeBytes)
            select(clip.id)
        }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    suspend fun defaultExportConfig(): ExportConfig {
        val st = settings.current()
        return ExportConfig(outputName = "Mix_${System.currentTimeMillis() / 1000}", codec = st.defaultCodec, bitrateKbps = st.defaultBitrate)
    }

    fun export(config: ExportConfig) {
        val p = history.current
        if (p.allClips.isEmpty()) return
        startExport(listOf(ExportJob.Render(config.outputName.ifBlank { "Mix" }, OutputCategory.MIX, p, config)))
    }

    override fun onCleared() {
        if (_ui.value.recording) NativeAudio.recStop()
        super.onCleared()
    }
}
