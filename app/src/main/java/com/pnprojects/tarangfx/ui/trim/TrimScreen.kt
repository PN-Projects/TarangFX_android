package com.pnprojects.tarangfx.ui.trim

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pnprojects.tarangfx.data.media.RingtoneTarget
import com.pnprojects.tarangfx.domain.model.BuiltInEqPresets
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.ui.components.ChipRow
import com.pnprojects.tarangfx.ui.components.DualPill
import com.pnprojects.tarangfx.ui.components.EmptyState
import com.pnprojects.tarangfx.ui.components.ExportProgressDialog
import com.pnprojects.tarangfx.ui.components.ExportSheet
import com.pnprojects.tarangfx.ui.components.Fmt
import com.pnprojects.tarangfx.ui.components.LabeledSlider
import com.pnprojects.tarangfx.ui.components.ModePill
import com.pnprojects.tarangfx.ui.components.PlayButton
import com.pnprojects.tarangfx.ui.components.PrimaryButton
import com.pnprojects.tarangfx.ui.components.ScreenTopBar
import com.pnprojects.tarangfx.ui.components.TimeStepper
import com.pnprojects.tarangfx.ui.components.TrimWaveform
import com.pnprojects.tarangfx.ui.components.card
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.TabularNums
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun TrimScreen(ringtoneMode: Boolean, onBack: () -> Unit, onOpenOutputs: () -> Unit, vm: TrimViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pos by vm.positionMs.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val exportStatuses by vm.exportStatuses.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var exportCfg by remember { mutableStateOf<ExportConfig?>(null) }
    var ringtoneTarget by remember { mutableStateOf<RingtoneTarget?>(null) }
    var chooseTarget by remember { mutableStateOf(false) }
    var needSettingsPermission by remember { mutableStateOf(false) }
    var fxOpen by remember { mutableStateOf(ringtoneMode) }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (vm.canWriteSettings()) scope.launch { exportCfg = vm.defaultExportConfig(true) }
    }

    Column(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        ScreenTopBar(
            title = ui.file?.title ?: if (ringtoneMode) "Ringtone" else "Trim",
            onBack = onBack,
            helpTitle = if (ringtoneMode) "Ringtone maker" else "Trim audio",
            helpText = "Drag the pink handles or use the − / + steppers (0.1 s; hold to repeat) to choose a range. " +
                "\"Keep selected\" exports only the selection; \"Delete selected\" removes it and joins the rest. " +
                "Pinch the waveform to zoom, tap to move the playhead.",
        )
        if (ui.file == null) {
            EmptyState("No audio selected", ui.error ?: "", Modifier.fillMaxSize())
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TimeStepper(ui.startMs, vm::stepStart, vm::commitSelection)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (ui.keepMode) "Selected" else "Result", style = MaterialTheme.typography.bodySmall)
                    Text(Fmt.timeTenths(ui.selectedMs), style = MaterialTheme.typography.titleSmall.merge(TabularNums))
                }
                TimeStepper(ui.endMs, vm::stepEnd, vm::commitSelection)
            }
            Box {
                TrimWaveform(
                    waveform = ui.waveform, durationMs = ui.durationMs, startMs = ui.startMs, endMs = ui.endMs,
                    playheadMs = pos, keepMode = ui.keepMode, zoom = ui.zoom, isPlaying = playing,
                    onZoomChange = vm::setZoom, onSelectionChange = vm::setSelection,
                    onSelectionCommit = vm::commitSelection, onSeek = vm::seekTo,
                )
                if (ui.waveform == null && ui.error == null) {
                    LinearProgressIndicator(
                        progress = { ui.loadProgress }, color = AppColors.Crimson, trackColor = AppColors.SurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).align(Alignment.Center),
                    )
                }
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Text("${Fmt.time(pos)}/${Fmt.time(ui.durationMs)}", style = MaterialTheme.typography.titleSmall.merge(TabularNums))
                Spacer(Modifier.weight(1f))
                DualPill(
                    Icons.Rounded.ZoomOut, "Zoom out", ui.zoom > 1f, vm::zoomOut,
                    Icons.Rounded.ZoomIn, "Zoom in", true, vm::zoomIn,
                )
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                PlayButton(playing, onClick = vm::play)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModePill("Keep selected", ui.keepMode, Modifier.weight(1f)) { vm.setKeepMode(true) }
                ModePill("Delete selected", !ui.keepMode, Modifier.weight(1f)) { vm.setKeepMode(false) }
            }
            // Collapsible Quick FX strip
            Row(
                Modifier.fillMaxWidth().clickable { fxOpen = !fxOpen }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Quick FX", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (fxOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = AppColors.TextSecondary)
            }
            AnimatedVisibility(fxOpen) {
                Column(Modifier.padding(horizontal = 16.dp).card()) {
                    val fx = ui.fx
                    LabeledSlider("Fade in", fx.fadeInMs / 1000f, 0f..10f, String.format(Locale.US, "%.1f s", fx.fadeInMs / 1000f)) {
                        vm.setFx(fx.copy(fadeInMs = (it * 10).toLong() * 100))
                    }
                    LabeledSlider("Fade out", fx.fadeOutMs / 1000f, 0f..10f, String.format(Locale.US, "%.1f s", fx.fadeOutMs / 1000f)) {
                        vm.setFx(fx.copy(fadeOutMs = (it * 10).toLong() * 100))
                    }
                    LabeledSlider("Volume", fx.volumeDb, -12f..12f, String.format(Locale.US, "%+.1f dB", fx.volumeDb)) {
                        vm.setFx(fx.copy(volumeDb = (it * 2).toInt() / 2f))
                    }
                    Text("EQ", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
                    ChipRow(BuiltInEqPresets.all.map { it.name }, { it }, fx.eqPreset) { vm.setFx(fx.copy(eqPreset = it)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        PrimaryButton(
            if (ringtoneMode) "Save & set as…" else "Confirm",
            modifier = Modifier.padding(16.dp).navigationBarsPadding(),
            enabled = ui.waveform != null || ui.durationMs > 0,
        ) {
            vm.pause()
            if (ringtoneMode) chooseTarget = true
            else scope.launch { ringtoneTarget = null; exportCfg = vm.defaultExportConfig(false) }
        }
    }

    if (chooseTarget) {
        AlertDialog(
            onDismissRequest = { chooseTarget = false }, containerColor = AppColors.Surface,
            title = { Text("Set as") },
            text = {
                Column {
                    RingtoneTarget.entries.forEach { t ->
                        Text(t.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().clickable {
                            ringtoneTarget = t
                            chooseTarget = false
                            if (vm.canWriteSettings()) scope.launch { exportCfg = vm.defaultExportConfig(true) }
                            else needSettingsPermission = true
                        }.padding(vertical = 14.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chooseTarget = false }) { Text("Cancel", color = AppColors.TextSecondary) } },
        )
    }
    if (needSettingsPermission) {
        AlertDialog(
            onDismissRequest = { needSettingsPermission = false }, containerColor = AppColors.Surface,
            title = { Text("Allow changing ringtones") },
            text = { Text("Android requires you to allow \"Modify system settings\" for this app before it can set a ringtone. Nothing else is changed.",
                color = AppColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { needSettingsPermission = false; settingsLauncher.launch(vm.writeSettingsIntent()) }) {
                    Text("Open settings", color = AppColors.Crimson)
                }
            },
            dismissButton = { TextButton(onClick = { needSettingsPermission = false }) { Text("Cancel", color = AppColors.TextSecondary) } },
        )
    }
    exportCfg?.let { cfg ->
        ExportSheet(
            initial = cfg, title = if (ringtoneMode) "Save ringtone" else "Export",
            onDismiss = { exportCfg = null },
            onConfirm = { c -> exportCfg = null; vm.export(c, if (ringtoneMode) ringtoneTarget else null) },
        )
    }
    ExportProgressDialog(exportStatuses, vm::cancelExport, vm::dismissExport) { vm.dismissExport(); onOpenOutputs() }
}
