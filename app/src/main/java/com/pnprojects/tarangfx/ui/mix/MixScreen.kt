package com.pnprojects.tarangfx.ui.mix

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pnprojects.tarangfx.domain.model.DenoiseMode
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.ui.components.ActionCard
import com.pnprojects.tarangfx.ui.components.ChipRow
import com.pnprojects.tarangfx.ui.components.DockTool
import com.pnprojects.tarangfx.ui.components.DualPill
import com.pnprojects.tarangfx.ui.components.ExportProgressDialog
import com.pnprojects.tarangfx.ui.components.ExportSheet
import com.pnprojects.tarangfx.ui.components.Fmt
import com.pnprojects.tarangfx.ui.components.FxSheet
import com.pnprojects.tarangfx.ui.components.InfoDialog
import com.pnprojects.tarangfx.ui.components.LabeledSlider
import com.pnprojects.tarangfx.ui.components.PlayButton
import com.pnprojects.tarangfx.ui.components.SavePill
import com.pnprojects.tarangfx.ui.components.ScreenTopBar
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.TabularNums
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

private enum class MixDialog { NONE, VOLUME, FADE, FX, DENOISE, TRACK }

@Composable
fun MixScreen(onBack: () -> Unit, onAddAudio: () -> Unit, onOpenOutputs: () -> Unit, vm: MixViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val project by vm.project.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pos by vm.positionMs.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val statuses by vm.exportStatuses.collectAsStateWithLifecycle()
    val presets by vm.userPresets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var exportCfg by remember { mutableStateOf<ExportConfig?>(null) }
    var dialog by remember { mutableStateOf(MixDialog.NONE) }
    var trackMenu by remember { mutableStateOf(-1) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.startRecording() }
    val selected = vm.selectedClip
    val hasSel = selected != null

    Column(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        ScreenTopBar(
            "Mix", onBack,
            helpText = "Each row is a track. Tap a clip to select it, then drag it to move (it snaps to the playhead and other clips). Drag empty space to scrub, pinch to zoom. Tap a track number for mute/solo. Record adds a new take in sync with playback.",
            actions = { SavePill(enabled = project.allClips.isNotEmpty() && !ui.recording) { vm.pause(); scope.launch { exportCfg = vm.defaultExportConfig() } } },
        )
        val lanes = max(MIN_LANES, project.tracks.size + 1)
        Box(Modifier.fillMaxWidth().height(340.dp).verticalScroll(rememberScrollState())) {
            MixTimeline(
                project = project, waveforms = ui.waveforms, selectedClipId = ui.selectedClipId, playheadMs = pos, pxPerMs = ui.pxPerMs,
                onScrub = vm::seekTo, onZoom = vm::setZoom, onSelect = vm::select,
                onLaneTap = { lane ->
                    if (project.tracks.getOrNull(lane)?.clips?.isNotEmpty() == true) { trackMenu = lane; dialog = MixDialog.TRACK }
                    else { vm.requestAdd(lane); onAddAudio() }
                },
                onDragStart = vm::beginDrag,
                onDragClip = { id, start, lane -> vm.moveClip(id, start, lane, commit = false) },
                onDragEnd = vm::endDrag,
                modifier = Modifier.fillMaxWidth().height((28 + 60 * lanes).dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            DualPill(Icons.AutoMirrored.Rounded.Undo, "Undo", canUndo, vm::undo, Icons.AutoMirrored.Rounded.Redo, "Redo", canRedo, vm::redo)
            Spacer(Modifier.weight(1f))
            Text("${Fmt.time(pos)}/${Fmt.time(project.durationMs)}", style = MaterialTheme.typography.titleSmall.merge(TabularNums))
            Spacer(Modifier.weight(1f))
            DualPill(Icons.Rounded.ZoomOut, "Zoom out", true, vm::zoomOut, Icons.Rounded.ZoomIn, "Zoom in", true, vm::zoomIn)
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            PlayButton(playing) { if (!ui.recording) vm.togglePlay() }
        }
        if (ui.recording) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("Recording  ${Fmt.timeTenths(ui.recordElapsedMs)}", style = MaterialTheme.typography.titleSmall, color = AppColors.Crimson)
                LinearProgressIndicator(progress = { ui.recordLevel }, color = AppColors.Crimson, trackColor = AppColors.SurfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionCard(Icons.Rounded.AddCircleOutline, "Add audio", Modifier.weight(1f), enabled = !ui.recording) { vm.requestAdd(null); onAddAudio() }
            if (ui.recording) ActionCard(Icons.Rounded.StopCircle, "Stop", Modifier.weight(1f), highlighted = true) { vm.stopRecording() }
            else ActionCard(Icons.Rounded.Mic, "Record", Modifier.weight(1f)) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startRecording()
                else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().background(AppColors.Surface).horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp).navigationBarsPadding(),
        ) {
            DockTool(Icons.Rounded.ContentCut, "Split", hasSel) { vm.splitSelected() }
            DockTool(Icons.Rounded.DeleteOutline, "Delete", hasSel) { vm.deleteSelected() }
            DockTool(Icons.AutoMirrored.Rounded.VolumeUp, "Volume", hasSel) { dialog = MixDialog.VOLUME }
            DockTool(Icons.Rounded.GraphicEq, "Fade", hasSel) { dialog = MixDialog.FADE }
            DockTool(Icons.Rounded.Speed, "Speed", hasSel) { dialog = MixDialog.FX }
            DockTool(Icons.Rounded.Equalizer, "EQ & FX", hasSel, active = selected?.dsp?.hasFx == true) { dialog = MixDialog.FX }
            DockTool(Icons.Rounded.AutoAwesome, "Denoise", hasSel, active = selected?.dsp?.aiDenoiseMode?.let { it != DenoiseMode.OFF } == true) { dialog = MixDialog.DENOISE }
            DockTool(Icons.Rounded.ContentCopy, "Duplicate", hasSel) { vm.duplicateSelected() }
        }
    }

    val clip = selected
    when (dialog) {
        MixDialog.VOLUME -> if (clip != null) {
            SimpleDialog("Clip volume", { dialog = MixDialog.NONE }) {
                LabeledSlider("Gain", clip.gainDb, -24f..12f, String.format(Locale.US, "%+.1f dB", clip.gainDb)) { vm.setClipGain((it * 2).toInt() / 2f, commit = false) }
                TextButton(onClick = { vm.setClipGain(clip.gainDb, commit = true); dialog = MixDialog.NONE }) { Text("Done", color = AppColors.Crimson) }
            }
        }
        MixDialog.FADE -> if (clip != null) {
            val maxFade = (clip.timelineLengthMs / 2f / 1000f).coerceAtLeast(0.1f)
            SimpleDialog("Fade in / out", { dialog = MixDialog.NONE }) {
                LabeledSlider("Fade in", clip.fadeInMs / 1000f, 0f..maxFade, String.format(Locale.US, "%.1f s", clip.fadeInMs / 1000f)) {
                    vm.setClipFades((it * 1000).toLong(), clip.fadeOutMs, commit = false)
                }
                LabeledSlider("Fade out", clip.fadeOutMs / 1000f, 0f..maxFade, String.format(Locale.US, "%.1f s", clip.fadeOutMs / 1000f)) {
                    vm.setClipFades(clip.fadeInMs, (it * 1000).toLong(), commit = false)
                }
                TextButton(onClick = { vm.setClipFades(clip.fadeInMs, clip.fadeOutMs, commit = true); dialog = MixDialog.NONE }) { Text("Done", color = AppColors.Crimson) }
            }
        }
        MixDialog.FX -> if (clip != null) {
            FxSheet(clip.title, clip.dsp, presets, vm::setClipDsp, { vm.savePreset(it, clip.dsp.eqBands) }, { vm.deletePreset(it) }) { dialog = MixDialog.NONE }
        }
        MixDialog.DENOISE -> if (clip != null) {
            SimpleDialog("AI Denoise (clip)", { dialog = MixDialog.NONE }) {
                ChipRow(listOf(DenoiseMode.OFF, DenoiseMode.FAST, DenoiseMode.STUDIO), {
                    when (it) { DenoiseMode.FAST -> "Fast"; DenoiseMode.STUDIO -> "Studio"; else -> "Off" }
                }, clip.dsp.aiDenoiseMode) { vm.setClipDsp(clip.dsp.copy(aiDenoiseMode = it)) }
                LabeledSlider("Strength", clip.dsp.aiDenoiseStrength, 0f..1f, "${(clip.dsp.aiDenoiseStrength * 100).toInt()}%",
                    enabled = clip.dsp.aiDenoiseMode != DenoiseMode.OFF) { vm.setClipDsp(clip.dsp.copy(aiDenoiseStrength = it)) }
            }
        }
        MixDialog.TRACK -> project.tracks.getOrNull(trackMenu)?.let { t ->
            SimpleDialog("Track ${trackMenu + 1}", { dialog = MixDialog.NONE }) {
                TextButton(onClick = { vm.setTrackFlags(trackMenu, muted = !t.muted); dialog = MixDialog.NONE }) { Text(if (t.muted) "Unmute" else "Mute") }
                TextButton(onClick = { vm.setTrackFlags(trackMenu, solo = !t.solo); dialog = MixDialog.NONE }) { Text(if (t.solo) "Unsolo" else "Solo") }
                TextButton(onClick = { vm.removeTrack(trackMenu); dialog = MixDialog.NONE }) { Text("Remove track", color = MaterialTheme.colorScheme.error) }
            }
        }
        MixDialog.NONE -> {}
    }
    ui.draftOffer?.let {
        AlertDialog(
            onDismissRequest = vm::dismissDraft, containerColor = AppColors.Surface,
            title = { Text("Resume previous mix?") },
            text = { Text("You have an unfinished mix saved on this device.", color = AppColors.TextSecondary) },
            confirmButton = { TextButton(onClick = vm::resumeDraft) { Text("Resume", color = AppColors.Crimson) } },
            dismissButton = { TextButton(onClick = vm::dismissDraft) { Text("Start new", color = AppColors.TextSecondary) } },
        )
    }
    ui.error?.let { InfoDialog("Something went wrong", it, vm::clearError) }
    exportCfg?.let { cfg -> ExportSheet(cfg, title = "Save mix", onDismiss = { exportCfg = null }) { exportCfg = null; vm.export(it) } }
    ExportProgressDialog(statuses, vm::cancelExport, vm::dismissExport) { vm.dismissExport(); onOpenOutputs() }
}

@Composable
private fun SimpleDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AppColors.Surface,
        title = { Text(title) },
        text = { Column { content() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = AppColors.TextSecondary) } },
    )
}
