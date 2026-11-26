package com.pnprojects.tarangfx.ui.denoise

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pnprojects.tarangfx.audio.ExportManager
import com.pnprojects.tarangfx.audio.PlaybackController
import com.pnprojects.tarangfx.audio.SelectionHolder
import com.pnprojects.tarangfx.data.media.WaveformRepository
import com.pnprojects.tarangfx.data.prefs.SettingsRepository
import com.pnprojects.tarangfx.domain.model.DenoiseMode
import com.pnprojects.tarangfx.domain.model.DspSettings
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.domain.model.OutputCategory
import com.pnprojects.tarangfx.domain.model.Project
import com.pnprojects.tarangfx.nativebridge.NativeAudio
import com.pnprojects.tarangfx.ui.components.EmptyState
import com.pnprojects.tarangfx.ui.components.ExportProgressDialog
import com.pnprojects.tarangfx.ui.components.ExportSheet
import com.pnprojects.tarangfx.ui.components.Fmt
import com.pnprojects.tarangfx.ui.components.LabeledSlider
import com.pnprojects.tarangfx.ui.components.PlayButton
import com.pnprojects.tarangfx.ui.components.PlayerWaveform
import com.pnprojects.tarangfx.ui.components.SavePill
import com.pnprojects.tarangfx.ui.components.ScreenTopBar
import com.pnprojects.tarangfx.ui.fx.AbButton
import com.pnprojects.tarangfx.ui.fx.SingleFileProcessorViewModel
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.AppShapes
import com.pnprojects.tarangfx.ui.theme.TabularNums
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class EnhanceMode(val title: String, val subtitle: String) {
    STUDIO("Studio Denoise", "DeepFilterNet3 · 48 kHz full-band"),
    FAST("Fast Denoise", "GTCRN · low latency, older devices"),
    VOICE("Voice Enhance", "Denoise + HPF + De-esser + Presence EQ + Compressor + −14 LUFS"),
}

data class AiAvailability(val studio: Boolean = false, val fast: Boolean = false)

@HiltViewModel
class AiDenoiseViewModel @Inject constructor(
    selection: SelectionHolder,
    waveforms: WaveformRepository,
    settings: SettingsRepository,
    playback: PlaybackController,
    exports: ExportManager,
) : SingleFileProcessorViewModel(selection, waveforms, settings, playback, exports) {
    override val category = OutputCategory.DENOISE
    override val suffix = "enhanced"

    private val _mode = MutableStateFlow(EnhanceMode.STUDIO)
    val mode: StateFlow<EnhanceMode> = _mode.asStateFlow()
    private val _avail = MutableStateFlow(AiAvailability())
    val availability: StateFlow<AiAvailability> = _avail.asStateFlow()
    private var strength = 1f

    init {
        viewModelScope.launch {
            val a = withContext(Dispatchers.IO) {
                AiAvailability(
                    runCatching { NativeAudio.denoiseAvailable(DenoiseMode.STUDIO) }.getOrDefault(false),
                    runCatching { NativeAudio.denoiseAvailable(DenoiseMode.FAST) }.getOrDefault(false),
                )
            }
            _avail.value = a
            applyMode()
        }
    }

    private fun denoiseModeFor(m: EnhanceMode): String {
        val a = _avail.value
        val preferred = if (m == EnhanceMode.FAST) DenoiseMode.FAST else DenoiseMode.STUDIO
        return when {
            preferred == DenoiseMode.STUDIO && a.studio -> DenoiseMode.STUDIO
            preferred == DenoiseMode.FAST && a.fast -> DenoiseMode.FAST
            a.studio -> DenoiseMode.STUDIO
            a.fast -> DenoiseMode.FAST
            else -> DenoiseMode.OFF
        }
    }

    private fun applyMode() {
        val m = _mode.value
        setDsp(
            DspSettings(
                aiDenoiseMode = denoiseModeFor(m),
                aiDenoiseStrength = strength,
                voiceEnhanceEnabled = m == EnhanceMode.VOICE,
            ),
        )
    }

    fun setMode(m: EnhanceMode) { _mode.value = m; applyMode() }
    fun setStrength(s: Float) { strength = s; setDsp(ui.value.dsp.copy(aiDenoiseStrength = s)) }

    /** EBU R128 normalisation needs the whole programme, so it is applied as a second export pass. */
    override fun exportProject(): Project {
        val p = super.exportProject()
        return if (_mode.value == EnhanceMode.VOICE) p.copy(master = DspSettings(normalizeLufs = -14f)) else p
    }
}

@Composable
fun AiDenoiseScreen(onBack: () -> Unit, onOpenOutputs: () -> Unit, vm: AiDenoiseViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val avail by vm.availability.collectAsStateWithLifecycle()
    val pos by vm.positionMs.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val engineDur by vm.engineDurationMs.collectAsStateWithLifecycle()
    val dur = if (engineDur > 0) engineDur else ui.durationMs
    val statuses by vm.exportStatuses.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var exportCfg by remember { mutableStateOf<ExportConfig?>(null) }

    Column(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        ScreenTopBar(
            "AI Enhance", onBack,
            helpText = "Neural noise removal runs entirely on this device. Pick a mode, adjust strength, and hold A/B to compare with the original. Voice Enhance also normalises loudness to −14 LUFS when you export.",
            actions = { SavePill(enabled = ui.file != null) { vm.pause(); scope.launch { exportCfg = vm.defaultExportConfig() } } },
        )
        if (ui.file == null) { EmptyState("No audio selected", "", Modifier.fillMaxSize()); return@Column }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(ui.file?.title ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            PlayerWaveform(ui.waveform, dur, pos, vm::seekTo, activeColor = if (ui.bypass) AppColors.TextSecondary else AppColors.Crimson)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${Fmt.time(pos)} / ${Fmt.time(dur)}", style = MaterialTheme.typography.bodyMedium.merge(TabularNums), maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
                PlayButton(playing, size = 52.dp, onClick = vm::togglePlay)
                Spacer(Modifier.weight(1f))
                AbButton(ui.bypass, vm::setBypass, labelB = "Enhanced")
            }
            if (!avail.studio && !avail.fast) {
                Text(
                    "Neural denoise models are not bundled in this build, so only the Voice Enhance DSP chain is active. " +
                        "See tools/models/README.md to add the ONNX models.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCard(EnhanceMode.STUDIO, Icons.Rounded.AutoAwesome, mode == EnhanceMode.STUDIO, avail.studio) { vm.setMode(EnhanceMode.STUDIO) }
                ModeCard(EnhanceMode.FAST, Icons.Rounded.Bolt, mode == EnhanceMode.FAST, avail.fast) { vm.setMode(EnhanceMode.FAST) }
                ModeCard(EnhanceMode.VOICE, Icons.Rounded.RecordVoiceOver, mode == EnhanceMode.VOICE, true) { vm.setMode(EnhanceMode.VOICE) }
            }
            Spacer(Modifier.height(12.dp))
            LabeledSlider(
                "Denoise strength", ui.dsp.aiDenoiseStrength, 0f..1f, "${(ui.dsp.aiDenoiseStrength * 100).toInt()}%",
                enabled = ui.dsp.aiDenoiseMode != DenoiseMode.OFF,
            ) { vm.setStrength(it) }
            Spacer(Modifier.height(24.dp).navigationBarsPadding())
        }
    }
    exportCfg?.let { cfg -> ExportSheet(cfg, title = "Export enhanced audio", onDismiss = { exportCfg = null }) { exportCfg = null; vm.export(it) } }
    ExportProgressDialog(statuses, vm::cancelExport, vm::dismissExport) { vm.dismissExport(); onOpenOutputs() }
}

@Composable
private fun ModeCard(mode: EnhanceMode, icon: ImageVector, selected: Boolean, available: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) AppColors.CrimsonContainer else AppColors.Surface, AppShapes.ToolCard)
            .then(if (selected) Modifier.border(1.5.dp, AppColors.Crimson, AppShapes.ToolCard) else Modifier)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) AppColors.Crimson else AppColors.TextSecondary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(mode.title, style = MaterialTheme.typography.titleSmall)
            Text(if (available) mode.subtitle else "${mode.subtitle} · model unavailable", style = MaterialTheme.typography.bodySmall)
        }
    }
}
