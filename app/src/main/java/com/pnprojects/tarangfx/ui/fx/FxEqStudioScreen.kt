package com.pnprojects.tarangfx.ui.fx

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.ui.components.EmptyState
import com.pnprojects.tarangfx.ui.components.ExportProgressDialog
import com.pnprojects.tarangfx.ui.components.ExportSheet
import com.pnprojects.tarangfx.ui.components.Fmt
import com.pnprojects.tarangfx.ui.components.FxPanel
import com.pnprojects.tarangfx.ui.components.PlayButton
import com.pnprojects.tarangfx.ui.components.PlayerWaveform
import com.pnprojects.tarangfx.ui.components.SavePill
import com.pnprojects.tarangfx.ui.components.ScreenTopBar
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.AppShapes
import com.pnprojects.tarangfx.ui.theme.TabularNums
import kotlinx.coroutines.launch

@Composable
fun FxEqStudioScreen(onBack: () -> Unit, onOpenOutputs: () -> Unit, vm: FxEqStudioViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pos by vm.positionMs.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val engineDur by vm.engineDurationMs.collectAsStateWithLifecycle()
    val dur = if (engineDur > 0) engineDur else ui.durationMs
    val presets by vm.userPresets.collectAsStateWithLifecycle()
    val statuses by vm.exportStatuses.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var exportCfg by remember { mutableStateOf<ExportConfig?>(null) }

    Column(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        ScreenTopBar(
            "FX & EQ", onBack,
            helpText = "Shape the sound with a 10-band EQ, bass boost, studio reverb and independent speed/pitch. Changes are heard instantly; hold A/B to compare with the original.",
            actions = { SavePill(enabled = ui.file != null) { vm.pause(); scope.launch { exportCfg = vm.defaultExportConfig() } } },
        )
        if (ui.file == null) { EmptyState("No audio selected", "", Modifier.fillMaxSize()); return@Column }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(ui.file?.title ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            PlayerWaveform(ui.waveform, dur, pos, vm::seekTo, height = 110.dp)
            // Time readout gets its own line: beside the play and A/B controls it was clipped on phones
            // (and could never fit hour-long files such as "1:02:03 / 1:10:00").
            Text(
                "${Fmt.time(pos)} / ${Fmt.time(dur)}",
                style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                color = AppColors.TextSecondary,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayButton(playing, size = 52.dp, onClick = vm::togglePlay)
                Spacer(Modifier.weight(1f))
                AbButton(ui.bypass, vm::setBypass)
            }
            FxPanel(ui.dsp, vm::setDsp, presets, { vm.savePreset(it) }, { vm.deletePreset(it) })
            Spacer(Modifier.height(24.dp).navigationBarsPadding())
        }
    }
    exportCfg?.let { cfg -> ExportSheet(cfg, title = "Export with effects", onDismiss = { exportCfg = null }) { exportCfg = null; vm.export(it) } }
    ExportProgressDialog(statuses, vm::cancelExport, vm::dismissExport) { vm.dismissExport(); onOpenOutputs() }
}

/** Press-and-hold A/B: shows "Original" while held, "Processed" otherwise. */
@Composable
fun AbButton(bypassed: Boolean, onBypass: (Boolean) -> Unit, labelA: String = "Original", labelB: String = "Processed") {
    Box(
        Modifier.width(196.dp).background(if (bypassed) AppColors.TextPrimary else AppColors.SurfaceVariant, AppShapes.Chip)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onBypass(true)
                    tryAwaitRelease()
                    onBypass(false)
                })
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (bypassed) "A · $labelA" else "Hold A/B · $labelB", style = MaterialTheme.typography.labelMedium,
            color = if (bypassed) Color.Black else AppColors.TextPrimary)
    }
}
