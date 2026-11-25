package com.pnprojects.tarangfx.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pnprojects.tarangfx.data.db.OutputAudioEntity
import com.pnprojects.tarangfx.data.media.OutputRepository
import com.pnprojects.tarangfx.data.prefs.SettingsRepository
import com.pnprojects.tarangfx.domain.model.Codec
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.domain.model.OutputCategory
import com.pnprojects.tarangfx.domain.model.toEncoderJson
import com.pnprojects.tarangfx.nativebridge.NativeAudio
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

enum class RecState { IDLE, RECORDING, PAUSED, SAVING }

data class RecordUi(
    val state: RecState = RecState.IDLE,
    val format: Codec = Codec.WAV,
    val stereo: Boolean = false,
    val gainDb: Float = 0f,
    val liveDenoise: Boolean = false,
    val denoiseAvailable: Boolean = false,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val peaks: List<Float> = emptyList(),
    val saved: OutputAudioEntity? = null,
    val error: String? = null,
)

@HiltViewModel
class RecordViewModel @Inject constructor(
    private val outputs: OutputRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(RecordUi())
    val ui: StateFlow<RecordUi> = _ui.asStateFlow()
    private var file: File? = null
    private var poll: Job? = null

    init {
        viewModelScope.launch {
            val fmt = settings.current().recordFormat.takeIf { it in FORMATS } ?: Codec.WAV
            val avail = withContext(Dispatchers.IO) { runCatching { NativeAudio.denoiseAvailable("fast") }.getOrDefault(false) }
            _ui.update { it.copy(format = fmt, denoiseAvailable = avail) }
        }
    }

    fun setFormat(c: Codec) {
        if (_ui.value.state != RecState.IDLE) return
        _ui.update { it.copy(format = c) }
        viewModelScope.launch { settings.setRecordFormat(c) }
    }

    fun setStereo(v: Boolean) { if (_ui.value.state == RecState.IDLE) _ui.update { it.copy(stereo = v) } }

    fun setGain(db: Float) {
        _ui.update { it.copy(gainDb = db) }
        NativeAudio.recSetGain(db)
    }

    fun setLiveDenoise(on: Boolean) {
        _ui.update { it.copy(liveDenoise = on) }
        NativeAudio.recSetLiveDenoise(on)
    }

    fun start() {
        val s = _ui.value
        if (s.state != RecState.IDLE) return
        val f = File(outputs.stagingDir(), "rec_${UUID.randomUUID()}.${s.format.ext}")
        val channels = if (s.stereo) 2 else 1
        val cfg = ExportConfig(codec = s.format, sampleRate = 48000, bitDepth = 24, bitrateKbps = 256, channels = channels)
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { NativeAudio.recStart(f.absolutePath, cfg.toEncoderJson(48000, channels), channels, s.liveDenoise) }
            if (err != null) {
                _ui.update { it.copy(error = err) }
                return@launch
            }
            NativeAudio.recSetGain(s.gainDb)
            file = f
            _ui.update { it.copy(state = RecState.RECORDING, elapsedMs = 0, peaks = emptyList(), saved = null) }
            poll = launch {
                while (isActive) {
                    val newPeaks = NativeAudio.recDrainPeaks()
                    _ui.update {
                        it.copy(
                            elapsedMs = NativeAudio.recPosition() / 1000,
                            level = NativeAudio.recLevel(),
                            peaks = (it.peaks + newPeaks.toList()).takeLast(MAX_PEAKS),
                        )
                    }
                    delay(40)
                }
            }
        }
    }

    fun pauseResume() {
        when (_ui.value.state) {
            RecState.RECORDING -> { NativeAudio.recPause(); _ui.update { it.copy(state = RecState.PAUSED) } }
            RecState.PAUSED -> { NativeAudio.recResume(); _ui.update { it.copy(state = RecState.RECORDING) } }
            else -> {}
        }
    }

    fun stop() {
        if (_ui.value.state != RecState.RECORDING && _ui.value.state != RecState.PAUSED) return
        poll?.cancel()
        _ui.update { it.copy(state = RecState.SAVING, level = 0f) }
        viewModelScope.launch {
            val durUs = withContext(Dispatchers.IO) { NativeAudio.recStop() }
            val f = file
            if (durUs <= 0 || f == null || !f.exists()) {
                _ui.update { it.copy(state = RecState.IDLE, error = "Recording could not be saved") }
                return@launch
            }
            val title = "Recording " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
            val saved = runCatching { outputs.publish(f, title, OutputCategory.RECORD, 48000, 0) }
            _ui.update {
                it.copy(state = RecState.IDLE, saved = saved.getOrNull(), error = saved.exceptionOrNull()?.message)
            }
        }
    }

    fun clearMessage() = _ui.update { it.copy(error = null, saved = null) }

    override fun onCleared() {
        if (_ui.value.state == RecState.RECORDING || _ui.value.state == RecState.PAUSED) {
            NativeAudio.recStop()
            file?.delete()
        }
        super.onCleared()
    }

    companion object {
        val FORMATS = listOf(Codec.WAV, Codec.FLAC, Codec.M4A)
        const val MAX_PEAKS = 400
    }
}
