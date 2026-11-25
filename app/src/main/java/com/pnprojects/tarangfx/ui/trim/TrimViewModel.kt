package com.pnprojects.tarangfx.ui.trim

import androidx.lifecycle.viewModelScope
import com.pnprojects.tarangfx.audio.ExportJob
import com.pnprojects.tarangfx.audio.ExportManager
import com.pnprojects.tarangfx.audio.PlaybackController
import com.pnprojects.tarangfx.audio.SelectionHolder
import com.pnprojects.tarangfx.data.media.RingtoneHelper
import com.pnprojects.tarangfx.data.media.RingtoneTarget
import com.pnprojects.tarangfx.data.media.WaveformRepository
import com.pnprojects.tarangfx.data.prefs.SettingsRepository
import com.pnprojects.tarangfx.domain.model.AudioFile
import com.pnprojects.tarangfx.domain.model.BuiltInEqPresets
import com.pnprojects.tarangfx.domain.model.Clip
import com.pnprojects.tarangfx.domain.model.DspSettings
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.domain.model.OutputCategory
import com.pnprojects.tarangfx.domain.model.Project
import com.pnprojects.tarangfx.domain.model.Track
import com.pnprojects.tarangfx.domain.model.Waveform
import com.pnprojects.tarangfx.ui.EditorViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuickFx(
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val volumeDb: Float = 0f,
    val eqPreset: String = "Flat",
)

data class TrimUi(
    val file: AudioFile? = null,
    val waveform: Waveform? = null,
    val loadProgress: Float = 0f,
    val durationMs: Long = 0,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val keepMode: Boolean = true,
    val zoom: Float = 1f,
    val fx: QuickFx = QuickFx(),
    val error: String? = null,
) {
    val selectedMs: Long get() = if (keepMode) endMs - startMs else durationMs - (endMs - startMs)
}

@HiltViewModel
class TrimViewModel @Inject constructor(
    selection: SelectionHolder,
    private val waveforms: WaveformRepository,
    private val settings: SettingsRepository,
    private val ringtones: RingtoneHelper,
    playback: PlaybackController,
    exports: ExportManager,
) : EditorViewModel(playback, exports) {

    private val _ui = MutableStateFlow(TrimUi())
    val ui: StateFlow<TrimUi> = _ui.asStateFlow()

    init {
        selection.peek().firstOrNull()?.let { open(it) }
            ?: _ui.update { it.copy(error = "No file selected") }
    }

    private fun open(file: AudioFile) {
        val dur = file.durationMs.coerceAtLeast(1)
        _ui.value = TrimUi(file = file, durationMs = dur, startMs = 0, endMs = dur)
        viewModelScope.launch {
            val err = playback.load(project(), owner)
            if (err != null) _ui.update { it.copy(error = err) }
            // Prefer the decoder's exact duration over MediaStore's rounded value.
            val engineDur = playback.durationMs.value
            if (engineDur > 0 && engineDur != dur) _ui.update { it.copy(durationMs = engineDur, endMs = engineDur) }
            applyRange()
            val wf = waveforms.load(file.uri, file.sizeBytes) { p -> _ui.update { it.copy(loadProgress = p) } }
            _ui.update { it.copy(waveform = wf, error = if (wf == null) "Could not read this file" else it.error) }
        }
    }

    private fun dspFor(fx: QuickFx) = DspSettings(
        eqEnabled = fx.eqPreset != "Flat",
        eqBands = BuiltInEqPresets.all.firstOrNull { it.name == fx.eqPreset }?.gains ?: List(10) { 0f },
        volumeGainDb = fx.volumeDb,
    )

    /** Preview project: the full source; range/skip is enforced by the engine. */
    private fun project(): Project {
        val s = _ui.value
        val f = s.file ?: return Project()
        return Project.single(Clip(1, f.uri, f.title, s.durationMs, dsp = dspFor(s.fx)))
    }

    private fun applyRange() {
        val s = _ui.value
        if (s.keepMode) {
            playback.setSkipRange(0, 0)
            playback.setPlayRange(s.startMs, s.endMs)
        } else {
            playback.setPlayRange(0, 0)
            playback.setSkipRange(s.startMs, s.endMs)
        }
    }

    fun setSelection(start: Long, end: Long) {
        _ui.update {
            val st = start.coerceIn(0, it.durationMs - 100)
            val en = end.coerceIn(st + 100, it.durationMs)
            it.copy(startMs = st, endMs = en)
        }
    }

    fun commitSelection() = applyRange()

    fun stepStart(delta: Long) = setSelection(_ui.value.startMs + delta, _ui.value.endMs)
    fun stepEnd(delta: Long) = setSelection(_ui.value.startMs, _ui.value.endMs + delta)

    fun setKeepMode(keep: Boolean) {
        _ui.update { it.copy(keepMode = keep) }
        applyRange()
    }

    fun setZoom(z: Float) = _ui.update { it.copy(zoom = z.coerceIn(1f, 400f)) }
    fun zoomIn() = setZoom(_ui.value.zoom * 1.6f)
    fun zoomOut() = setZoom(_ui.value.zoom / 1.6f)

    fun setFx(fx: QuickFx) {
        _ui.update { it.copy(fx = fx) }
        val s = _ui.value
        s.file?.let { playback.updateClip(Clip(1, it.uri, it.title, s.durationMs, dsp = dspFor(fx))) }
    }

    override fun seekTo(ms: Long) = playback.seekTo(ms.coerceIn(0, _ui.value.durationMs))

    fun play() {
        val s = _ui.value
        val pos = positionMs.value
        if (!isPlaying.value) {
            if (s.keepMode && (pos < s.startMs || pos >= s.endMs - 50)) playback.seekTo(s.startMs)
            if (!s.keepMode && pos in s.startMs until s.endMs) playback.seekTo(s.endMs)
        }
        playback.toggle()
    }

    fun canWriteSettings() = ringtones.canWriteSettings()
    fun writeSettingsIntent() = ringtones.writeSettingsIntent()

    suspend fun defaultExportConfig(ringtone: Boolean): ExportConfig {
        val st = settings.current()
        val name = (_ui.value.file?.title ?: "audio") + if (ringtone) "_ringtone" else "_trim"
        return ExportConfig(outputName = name, codec = st.defaultCodec, bitrateKbps = st.defaultBitrate)
    }

    fun export(config: ExportConfig, ringtoneTarget: RingtoneTarget?) {
        val s = _ui.value
        val f = s.file ?: return
        val dsp = dspFor(s.fx)
        val clips = if (s.keepMode) {
            listOf(Clip(1, f.uri, f.title, s.durationMs, s.startMs, s.endMs, 0, fadeInMs = s.fx.fadeInMs, fadeOutMs = s.fx.fadeOutMs, dsp = dsp))
        } else {
            buildList {
                if (s.startMs > 0) add(Clip(1, f.uri, f.title, s.durationMs, 0, s.startMs, 0, fadeInMs = s.fx.fadeInMs, dsp = dsp))
                if (s.endMs < s.durationMs) add(Clip(2, f.uri, f.title, s.durationMs, s.endMs, s.durationMs, s.startMs, fadeOutMs = s.fx.fadeOutMs, dsp = dsp))
            }
        }
        if (clips.isEmpty()) return
        val project = Project(listOf(Track(1, clips)))
        val title = config.outputName.ifBlank { f.title }
        startExport(listOf(ExportJob.Render(
            title = title,
            category = if (ringtoneTarget != null) OutputCategory.RINGTONE else OutputCategory.TRIM,
            project = project, config = config, ringtoneTarget = ringtoneTarget,
        )))
    }
}
