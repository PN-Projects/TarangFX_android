package com.pnprojects.tarangfx.ui.record

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pnprojects.tarangfx.ui.components.ChipRow
import com.pnprojects.tarangfx.ui.components.Fmt
import com.pnprojects.tarangfx.ui.components.InfoDialog
import com.pnprojects.tarangfx.ui.components.LabeledSlider
import com.pnprojects.tarangfx.ui.components.ScreenTopBar
import com.pnprojects.tarangfx.ui.components.card
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.TabularNums
import java.util.Locale
import kotlin.math.max
import kotlin.math.sqrt

@Composable
fun RecordScreen(onBack: () -> Unit, onOpenOutputs: () -> Unit, vm: RecordViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.start() }
    val active = ui.state == RecState.RECORDING || ui.state == RecState.PAUSED

    // Keep the screen on while capturing (the OS mutes background mic capture).
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        ScreenTopBar("Record", if (active) null else onBack,
            helpText = "Records high-fidelity audio fully offline. WAV and FLAC are lossless (24-bit, 48 kHz); M4A is compact AAC. Recordings are saved to Outputs.")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp))
            Text(
                Fmt.timeTenths(ui.elapsedMs), style = MaterialTheme.typography.displaySmall.merge(TabularNums).copy(fontSize = 48.sp),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(
                when (ui.state) { RecState.RECORDING -> "Recording"; RecState.PAUSED -> "Paused"; RecState.SAVING -> "Saving…"; else -> "Ready" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (ui.state == RecState.RECORDING) AppColors.Crimson else AppColors.TextSecondary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(16.dp))
            LiveWaveform(ui.peaks, ui.state == RecState.RECORDING)
            Spacer(Modifier.height(8.dp))
            // Input level meter
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(AppColors.SurfaceVariant)) {
                Box(Modifier.fillMaxWidth(sqrt(ui.level).coerceIn(0f, 1f)).height(6.dp)
                    .background(if (ui.level > 0.95f) Color(0xFFFF5370) else AppColors.Crimson))
            }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.card()) {
                Text("Format", style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
                ChipRow(RecordViewModel.FORMATS, { it.label }, ui.format) { if (!active) vm.setFormat(it) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Stereo", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(ui.stereo, { vm.setStereo(it) }, enabled = !active, colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Crimson))
                }
                LabeledSlider("Input gain", ui.gainDb, -12f..12f, String.format(Locale.US, "%+.1f dB", ui.gainDb)) { vm.setGain((it * 2).toInt() / 2f) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Live AI noise suppression", style = MaterialTheme.typography.bodyMedium)
                        Text(if (ui.denoiseAvailable) "GTCRN model · runs on-device" else "Model not installed in this build",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(ui.liveDenoise, { vm.setLiveDenoise(it) }, enabled = ui.denoiseAvailable,
                        colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Crimson))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::pauseResume, enabled = active, modifier = Modifier.size(56.dp).clip(CircleShape).background(AppColors.Surface)) {
                Icon(if (ui.state == RecState.PAUSED) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    if (ui.state == RecState.PAUSED) "Resume" else "Pause", tint = if (active) AppColors.TextPrimary else AppColors.TextDisabled)
            }
            Box(
                Modifier.size(84.dp).clip(CircleShape).border(4.dp, AppColors.TextPrimary, CircleShape).padding(8.dp).clip(CircleShape)
                    .background(AppColors.Crimson).clickable(enabled = ui.state != RecState.SAVING) {
                        if (active) vm.stop()
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.start()
                        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (active) Icons.Rounded.Stop else Icons.Rounded.Mic, if (active) "Stop and save" else "Start recording", tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.size(56.dp))
        }
    }
    ui.saved?.let {
        InfoDialog("Saved", "\"${it.title}\" was saved to Music/TarangFX and added to Outputs.") { vm.clearMessage() }
    }
    ui.error?.let { InfoDialog("Recording error", it) { vm.clearMessage() } }
}

@Composable
private fun LiveWaveform(peaks: List<Float>, active: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(140.dp).background(AppColors.Surface, RoundedCornerShape(16.dp))) {
        val barW = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val pitch = barW + gap
        val maxBars = (size.width / pitch).toInt()
        val shown = peaks.takeLast(maxBars)
        val start = size.width - shown.size * pitch
        val cy = size.height / 2
        shown.forEachIndexed { i, p ->
            val h = max(2f, sqrt(p) * (size.height / 2 - 8.dp.toPx()))
            drawRoundRect(
                if (active) AppColors.Crimson else AppColors.WaveInactive,
                Offset(start + i * pitch, cy - h), Size(barW, 2 * h), CornerRadius(barW / 2),
            )
        }
        drawLine(AppColors.Playhead, Offset(size.width - 1f, 8f), Offset(size.width - 1f, size.height - 8f), 1.5.dp.toPx())
    }
}
