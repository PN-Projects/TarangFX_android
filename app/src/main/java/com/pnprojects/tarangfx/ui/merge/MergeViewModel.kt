package com.pnprojects.tarangfx.ui.merge

import androidx.lifecycle.viewModelScope
import com.pnprojects.tarangfx.audio.ExportJob
import com.pnprojects.tarangfx.audio.ExportManager
import com.pnprojects.tarangfx.audio.PlaybackController
import com.pnprojects.tarangfx.audio.SelectionHolder
import com.pnprojects.tarangfx.data.db.EqPresetRepository
import com.pnprojects.tarangfx.data.prefs.SettingsRepository
import com.pnprojects.tarangfx.domain.model.AudioFile
import com.pnprojects.tarangfx.domain.model.Clip
import com.pnprojects.tarangfx.domain.model.DspSettings
import com.pnprojects.tarangfx.domain.model.EqPreset
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.domain.model.OutputCategory
import com.pnprojects.tarangfx.domain.model.Project
import com.pnprojects.tarangfx.domain.model.Track
import com.pnprojects.tarangfx.ui.EditorViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MergeItem(
    val id: Long,
    val file: AudioFile,
    val startMs: Long = 0,
    val endMs: Long = file.durationMs,
    val dsp: DspSettings = DspSettings(),
) {
    val lengthMs: Long get() = ((endMs - startMs) / dsp.speed).toLong()
}

data class MergeUi(val items: List<MergeItem> = emptyList(), val crossfadeMs: Long = 0) {
    /** Timeline start of each item, accounting for crossfade overlap. */
    val starts: List<Long>
        get() {
            var t = 0L
            return items.map { item -> t.also { t += (item.lengthMs - crossfadeMs).coerceAtLeast(0) } }
        }
    val totalMs: Long get() = if (items.isEmpty()) 0 else starts.last() + items.last().lengthMs
}

@HiltViewModel
class MergeViewModel @Inject constructor(
    private val selection: SelectionHolder,
    private val settings: SettingsRepository,
    private val presets: EqPresetRepository,
    playback: PlaybackController,
    exports: ExportManager,
) : EditorViewModel(playback, exports) {

    private var nextId = 1L
    private val _ui = MutableStateFlow(MergeUi())
    val ui: StateFlow<MergeUi> = _ui.asStateFlow()
    val userPresets: StateFlow<List<EqPreset>> = presets.userPresets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        _ui.value = MergeUi(selection.peek().map { MergeItem(nextId++, it) })
        viewModelScope.launch { playback.load(project(), owner) }
        selection.appended.filter { it.isNotEmpty() }.onEach {
            val more = selection.consumeAppend()
            _ui.update { s -> s.copy(items = s.items + more.map { f -> MergeItem(nextId++, f) }) }
            reload()
        }.launchIn(viewModelScope)
    }

    private fun clipFor(item: MergeItem, index: Int, s: MergeUi): Clip {
        val xf = s.crossfadeMs
        return Clip(
            id = item.id, uri = item.file.uri, title = item.file.title, sourceDurationMs = item.file.durationMs,
            sourceStartMs = item.startMs, sourceEndMs = item.endMs, timelineStartMs = s.starts[index],
            fadeInMs = if (index > 0 && xf > 0) xf else item.dsp.fadeInMs,
            fadeOutMs = if (index < s.items.lastIndex && xf > 0) xf else item.dsp.fadeOutMs,
            equalPowerFade = xf > 0, dsp = item.dsp,
        )
    }

    private fun project(): Project {
        val s = _ui.value
        return Project(listOf(Track(1, s.items.mapIndexed { i, it -> clipFor(it, i, s) })))
    }

    private fun reload() = playback.update(project())

    fun move(from: Int, to: Int) {
        _ui.update { s ->
            if (from !in s.items.indices || to !in s.items.indices) return@update s
            s.copy(items = s.items.toMutableList().apply { add(to, removeAt(from)) })
        }
        reload()
    }

    fun remove(id: Long) {
        _ui.update { s -> s.copy(items = s.items.filterNot { it.id == id }) }
        reload()
    }

    fun sortByName() { _ui.update { s -> s.copy(items = s.items.sortedBy { it.file.title.lowercase() }) }; reload() }
    fun sortByDuration() { _ui.update { s -> s.copy(items = s.items.sortedBy { it.lengthMs }) }; reload() }
    fun reverse() { _ui.update { s -> s.copy(items = s.items.reversed()) }; reload() }

    fun setTrim(id: Long, start: Long, end: Long) {
        _ui.update { s -> s.copy(items = s.items.map { if (it.id == id) it.copy(startMs = start, endMs = end.coerceAtLeast(start + 100)) else it }) }
        reload()
    }

    fun setDsp(id: Long, dsp: DspSettings) {
        val old = _ui.value.items.firstOrNull { it.id == id } ?: return
        _ui.update { s -> s.copy(items = s.items.map { if (it.id == id) it.copy(dsp = dsp) else it }) }
        val s = _ui.value
        val idx = s.items.indexOfFirst { it.id == id }
        if (dsp.needsRebuildFrom(old.dsp)) reload()
        else playback.updateClip(clipFor(s.items[idx], idx, s))
    }

    fun setCrossfade(ms: Long) {
        val maxXf = (_ui.value.items.minOfOrNull { it.lengthMs } ?: 0L) / 2
        _ui.update { it.copy(crossfadeMs = ms.coerceIn(0, maxXf.coerceAtMost(10_000))) }
        reload()
    }

    fun currentIndex(pos: Long): Int {
        val s = _ui.value
        val starts = s.starts
        return starts.indexOfLast { it <= pos }.coerceAtLeast(0)
    }

    fun playItem(index: Int) {
        val starts = _ui.value.starts
        if (index !in starts.indices) return
        playback.seekTo(starts[index])
        if (!isPlaying.value) playback.play()
    }

    fun prev() = playItem((currentIndex(positionMs.value) - 1).coerceAtLeast(0))
    fun next() = playItem((currentIndex(positionMs.value) + 1).coerceAtMost(_ui.value.items.lastIndex))

    fun savePreset(name: String, gains: List<Float>) = viewModelScope.launch { presets.save(name, gains) }
    fun deletePreset(p: EqPreset) = viewModelScope.launch { presets.delete(p) }

    suspend fun defaultExportConfig(): ExportConfig {
        val st = settings.current()
        return ExportConfig(outputName = "Merged_${System.currentTimeMillis() / 1000}", codec = st.defaultCodec, bitrateKbps = st.defaultBitrate)
    }

    fun export(config: ExportConfig) {
        if (_ui.value.items.isEmpty()) return
        startExport(listOf(ExportJob.Render(config.outputName.ifBlank { "Merged" }, OutputCategory.MERGE, project(), config)))
    }
}
