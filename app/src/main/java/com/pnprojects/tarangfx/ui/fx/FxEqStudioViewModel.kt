package com.pnprojects.tarangfx.ui.fx

import androidx.lifecycle.viewModelScope
import com.pnprojects.tarangfx.audio.ExportJob
import com.pnprojects.tarangfx.audio.ExportManager
import com.pnprojects.tarangfx.audio.PlaybackController
import com.pnprojects.tarangfx.audio.SelectionHolder
import com.pnprojects.tarangfx.data.db.EqPresetRepository
import com.pnprojects.tarangfx.data.media.WaveformRepository
import com.pnprojects.tarangfx.data.prefs.SettingsRepository
import com.pnprojects.tarangfx.domain.model.AudioFile
import com.pnprojects.tarangfx.domain.model.Clip
import com.pnprojects.tarangfx.domain.model.DspSettings
import com.pnprojects.tarangfx.domain.model.EqPreset
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.domain.model.OutputCategory
import com.pnprojects.tarangfx.domain.model.Project
import com.pnprojects.tarangfx.domain.model.Waveform
import com.pnprojects.tarangfx.ui.EditorViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SingleFileUi(
    val file: AudioFile? = null,
    val waveform: Waveform? = null,
    val durationMs: Long = 0,
    val dsp: DspSettings = DspSettings(),
    val bypass: Boolean = false,
)

/**
 * Base for single-file processors (FX studio, AI denoise): loads the file into the engine and
 * pushes DSP changes either as live parameter updates or (for structural changes) a reload.
 */
abstract class SingleFileProcessorViewModel(
    selection: SelectionHolder,
    private val waveforms: WaveformRepository,
    protected val settings: SettingsRepository,
    playback: PlaybackController,
    exports: ExportManager,
    initialDsp: DspSettings = DspSettings(),
) : EditorViewModel(playback, exports) {

    protected val _ui = MutableStateFlow(SingleFileUi(dsp = initialDsp))
    val ui: StateFlow<SingleFileUi> = _ui.asStateFlow()

    init {
        selection.peek().firstOrNull()?.let { f ->
            _ui.update { it.copy(file = f, durationMs = f.durationMs.coerceAtLeast(1)) }
            viewModelScope.launch {
                playback.load(Project.single(clip()), owner)
                playback.setPlayRange(0, 0)
                playback.setSkipRange(0, 0)
                val d = playback.durationMs.value
                if (d > 0) _ui.update { it.copy(durationMs = d) }
                val wf = waveforms.load(f.uri, f.sizeBytes)
                _ui.update { it.copy(waveform = wf) }
            }
        }
    }

    protected fun clip(dsp: DspSettings = _ui.value.dsp, bypass: Boolean = _ui.value.bypass): Clip {
        val f = _ui.value.file!!
        return Clip(1, f.uri, f.title, _ui.value.durationMs, dsp = dsp.copy(bypass = bypass))
    }

    fun setDsp(dsp: DspSettings) {
        val old = _ui.value.dsp
        _ui.update { it.copy(dsp = dsp) }
        if (_ui.value.file == null) return
        if (dsp.needsRebuildFrom(old)) playback.update(Project.single(clip()))
        else playback.updateClip(clip())
    }

    fun setBypass(on: Boolean) {
        if (_ui.value.bypass == on) return
        _ui.update { it.copy(bypass = on) }
        if (_ui.value.file != null) playback.updateClip(clip())
    }

    abstract val category: OutputCategory
    abstract val suffix: String

    suspend fun defaultExportConfig(): ExportConfig {
        val st = settings.current()
        return ExportConfig(outputName = "${_ui.value.file?.title ?: "audio"}_$suffix", codec = st.defaultCodec, bitrateKbps = st.defaultBitrate)
    }

    open fun exportProject(): Project = Project.single(clip(bypass = false))

    fun export(config: ExportConfig) {
        if (_ui.value.file == null) return
        setBypass(false)
        startExport(listOf(ExportJob.Render(config.outputName.ifBlank { suffix }, category, exportProject(), config)))
    }
}

@HiltViewModel
class FxEqStudioViewModel @Inject constructor(
    selection: SelectionHolder,
    waveforms: WaveformRepository,
    settings: SettingsRepository,
    private val presets: EqPresetRepository,
    playback: PlaybackController,
    exports: ExportManager,
) : SingleFileProcessorViewModel(selection, waveforms, settings, playback, exports, DspSettings(eqEnabled = true)) {
    override val category = OutputCategory.FX
    override val suffix = "fx"
    val userPresets: StateFlow<List<EqPreset>> = presets.userPresets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun savePreset(name: String) = viewModelScope.launch { presets.save(name, _ui.value.dsp.eqBands) }
    fun deletePreset(p: EqPreset) = viewModelScope.launch { presets.delete(p) }
}
