package com.pnprojects.tarangfx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.pnprojects.tarangfx.domain.model.BuiltInEqPresets
import com.pnprojects.tarangfx.domain.model.DspSettings
import com.pnprojects.tarangfx.domain.model.EqPreset
import com.pnprojects.tarangfx.domain.model.ReverbPresets
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.AppShapes
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class FxTab(val label: String) { EQ("EQ"), BASS_REVERB("Bass & Reverb"), SPEED_PITCH("Speed & Pitch") }

/** FX & EQ workspace (PRD Screen 9). Pure UI: every change is reported through [onChange]. */
@Composable
fun FxPanel(
    dsp: DspSettings,
    onChange: (DspSettings) -> Unit,
    userPresets: List<EqPreset>,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (EqPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier) {
        TabRow(
            selectedTabIndex = tab, containerColor = Color.Transparent, contentColor = AppColors.TextPrimary,
            indicator = { pos ->
                TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = AppColors.Crimson)
            },
            divider = {},
        ) {
            FxTab.entries.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = {
                    Text(t.label, style = MaterialTheme.typography.labelMedium,
                        color = if (tab == i) AppColors.TextPrimary else AppColors.TextSecondary)
                })
            }
        }
        Spacer(Modifier.height(12.dp))
        when (FxTab.entries[tab]) {
            FxTab.EQ -> EqTab(dsp, onChange, userPresets, onSavePreset, onDeletePreset)
            FxTab.BASS_REVERB -> BassReverbTab(dsp, onChange)
            FxTab.SPEED_PITCH -> SpeedPitchTab(dsp, onChange)
        }
    }
}

@Composable
private fun EqTab(
    dsp: DspSettings, onChange: (DspSettings) -> Unit, userPresets: List<EqPreset>,
    onSavePreset: (String) -> Unit, onDeletePreset: (EqPreset) -> Unit,
) {
    var presetMenu by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    val allPresets = BuiltInEqPresets.all + userPresets
    val currentName = allPresets.firstOrNull { it.gains == dsp.eqBands }?.name ?: "Custom"

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            SelectPill("Preset: $currentName", selected = false) { presetMenu = true }
            DropdownMenu(presetMenu, { presetMenu = false }, containerColor = AppColors.SurfaceVariant) {
                allPresets.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = { onChange(dsp.copy(eqEnabled = true, eqBands = p.gains)); presetMenu = false },
                        trailingIcon = if (!p.builtIn) {
                            { IconButton(onClick = { onDeletePreset(p); presetMenu = false }) { Icon(Icons.Rounded.Delete, "Delete preset", tint = AppColors.TextSecondary) } }
                        } else null,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("EQ", style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
        Spacer(Modifier.width(6.dp))
        Switch(
            checked = dsp.eqEnabled, onCheckedChange = { onChange(dsp.copy(eqEnabled = it)) },
            colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Crimson),
        )
    }
    Spacer(Modifier.height(12.dp))
    EqCurve(dsp.eqBands, enabled = dsp.eqEnabled) { bands -> onChange(dsp.copy(eqEnabled = true, eqBands = bands)) }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        dsp.eqBands.forEachIndexed { i, g ->
            VerticalBandSlider(
                value = g, label = DspSettings.bandLabel(DspSettings.EQ_FREQUENCIES[i]), enabled = dsp.eqEnabled,
            ) { v -> onChange(dsp.copy(eqEnabled = true, eqBands = dsp.eqBands.toMutableList().also { it[i] = v })) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectPill("Reset", false) { onChange(dsp.copy(eqBands = List(10) { 0f })) }
        SelectPill("Save preset", false) { saveDialog = true }
    }
    if (saveDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveDialog = false }, containerColor = AppColors.Surface,
            title = { Text("Save EQ preset") },
            text = {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, colors = appTextFieldColors())
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { onSavePreset(name.trim()); saveDialog = false }) {
                    Text("Save", color = AppColors.Crimson)
                }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Cancel", color = AppColors.TextSecondary) } },
        )
    }
}

/** Interactive EQ response curve: drag band points vertically; curve is the true RBJ response. */
@Composable
fun EqCurve(bands: List<Float>, enabled: Boolean, onChange: (List<Float>) -> Unit) {
    val measurer = rememberTextMeasurer()
    val current by rememberUpdatedState(bands)
    val change by rememberUpdatedState(onChange)
    var dragBand by remember { mutableIntStateOf(-1) }
    val freqs = DspSettings.EQ_FREQUENCIES
    val minF = 20.0
    val maxF = 20000.0
    fun xOf(f: Double, w: Float) = (ln(f / minF) / ln(maxF / minF) * w).toFloat()
    fun yOf(db: Float, top: Float, h: Float) = top + h / 2f - db / 15f * (h / 2f)

    Canvas(
        Modifier.fillMaxWidth().height(170.dp).background(AppColors.Surface, AppShapes.ToolCard)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { p ->
                        dragBand = freqs.indices.minByOrNull { abs(xOf(freqs[it].toDouble(), size.width.toFloat()) - p.x) } ?: -1
                    },
                    onDragEnd = { dragBand = -1 },
                    onDragCancel = { dragBand = -1 },
                ) { change2, _ ->
                    val i = dragBand
                    if (i >= 0) {
                        val h = size.height.toFloat()
                        val db = ((h / 2f - change2.position.y) / (h / 2f) * 15f).coerceIn(-12f, 12f)
                        change(current.toMutableList().also { it[i] = (db * 2).roundToInt() / 2f })
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { p ->
                    val i = freqs.indices.minByOrNull { abs(xOf(freqs[it].toDouble(), size.width.toFloat()) - p.x) } ?: return@detectTapGestures
                    change(current.toMutableList().also { it[i] = 0f })
                })
            },
    ) {
        val w = size.width
        val h = size.height
        // Grid: 0 dB line and ±6/±12 dB
        listOf(-12f, -6f, 0f, 6f, 12f).forEach { db ->
            val y = yOf(db, 0f, h)
            drawLine(if (db == 0f) AppColors.TextDisabled else AppColors.Divider, Offset(0f, y), Offset(w, y), 1f)
        }
        freqs.forEach { f ->
            val x = xOf(f.toDouble(), w)
            drawLine(AppColors.Divider, Offset(x, 0f), Offset(x, h), 1f)
        }
        // Response curve
        val path = Path()
        val fill = Path()
        val n = 160
        for (k in 0..n) {
            val f = minF * (maxF / minF).pow(k / n.toDouble())
            val db = totalResponseDb(f, current)
            val x = xOf(f, w)
            val y = yOf(db.toFloat().coerceIn(-15f, 15f), 0f, h)
            if (k == 0) { path.moveTo(x, y); fill.moveTo(x, h / 2f); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(w, h / 2f); fill.close()
        val color = if (enabled) AppColors.Crimson else AppColors.TextDisabled
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
        // Handles
        current.forEachIndexed { i, g ->
            val x = xOf(freqs[i].toDouble(), w)
            val y = yOf(g, 0f, h)
            drawCircle(if (i == dragBand) Color.White else color, radius = if (i == dragBand) 8.dp.toPx() else 5.dp.toPx(), center = Offset(x, y))
        }
        val lbl = measurer.measure("+12 dB", RulerTextStyle)
        drawText(lbl, topLeft = Offset(4f, 2f))
    }
}

/** Sum of the 10 RBJ peaking filters' magnitude responses (Q = 1.414) at 48 kHz, in dB. */
private fun totalResponseDb(f: Double, gains: List<Float>): Double {
    val fs = 48000.0
    var total = 0.0
    val w = 2 * PI * f / fs
    for (i in gains.indices) {
        val g = gains[i].toDouble()
        if (abs(g) < 0.01) continue
        val f0 = DspSettings.EQ_FREQUENCIES[i].toDouble()
        val a = 10.0.pow(g / 40.0)
        val w0 = 2 * PI * f0 / fs
        val alpha = sin(w0) / (2 * 1.414)
        val b0 = 1 + alpha * a; val b1 = -2 * cos(w0); val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a; val a1 = -2 * cos(w0); val a2 = 1 - alpha / a
        // |H(e^jw)|
        val cr = cos(w); val ci = -sin(w); val c2r = cos(2 * w); val c2i = -sin(2 * w)
        val nr = b0 + b1 * cr + b2 * c2r; val ni = b1 * ci + b2 * c2i
        val dr = a0 + a1 * cr + a2 * c2r; val di = a1 * ci + a2 * c2i
        val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        total += 20 * log10(mag)
    }
    return total
}

@Composable
fun VerticalBandSlider(value: Float, label: String, enabled: Boolean, onChange: (Float) -> Unit) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(30.dp)) {
        Text(String.format(Locale.US, "%+.0f", value), style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier.width(30.dp).height(150.dp).pointerInput(Unit) {
                detectDragGestures { ch, drag ->
                    val dDb = -drag.y / size.height * 24f
                    change(((current + dDb) * 2).roundToInt().div(2f).coerceIn(-12f, 12f))
                    ch.consume()
                }
            }.pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { change(0f) },
                    onTap = { p -> change(((0.5f - p.y / size.height) * 24f * 2).roundToInt().div(2f).coerceIn(-12f, 12f)) },
                )
            },
        ) {
            val cx = size.width / 2
            val track = 4.dp.toPx()
            drawRoundRect(AppColors.SurfaceVariant, Offset(cx - track / 2, 0f), androidx.compose.ui.geometry.Size(track, size.height),
                androidx.compose.ui.geometry.CornerRadius(track / 2))
            val zeroY = size.height / 2
            val y = zeroY - value / 12f * (size.height / 2)
            val c = if (enabled) AppColors.Crimson else AppColors.TextDisabled
            drawLine(c, Offset(cx, zeroY), Offset(cx, y), strokeWidth = track)
            drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(cx, y))
            drawCircle(c, radius = 4.dp.toPx(), center = Offset(cx, y))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
    }
}

@Composable
fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String,
    steps: Int = 0, enabled: Boolean = true, onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        }
        Slider(
            value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = AppColors.Crimson, inactiveTrackColor = AppColors.SurfaceVariant,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun BassReverbTab(dsp: DspSettings, onChange: (DspSettings) -> Unit) {
    Column(Modifier.card()) {
        Text("Bass Boost", style = MaterialTheme.typography.titleSmall)
        LabeledSlider("Intensity", dsp.bassBoostAmount, 0f..1f, "${(dsp.bassBoostAmount * 100).roundToInt()}%") {
            onChange(dsp.copy(bassBoostAmount = it))
        }
        LabeledSlider("Center frequency", dsp.bassBoostFreq, 50f..120f, "${dsp.bassBoostFreq.roundToInt()} Hz") {
            onChange(dsp.copy(bassBoostFreq = it))
        }
    }
    Spacer(Modifier.height(12.dp))
    Column(Modifier.card()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Studio Reverb", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(
                checked = dsp.reverbEnabled, onCheckedChange = { onChange(dsp.copy(reverbEnabled = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Crimson),
            )
        }
        ChipRow(ReverbPresets.all, { it.name }, ReverbPresets.all.firstOrNull {
            it.room == dsp.reverbRoomSize && it.damping == dsp.reverbDamping && it.wet == dsp.reverbWetDry
        } ?: ReverbPresets.all.first().copy(name = "")) { p ->
            onChange(dsp.copy(reverbEnabled = true, reverbRoomSize = p.room, reverbDamping = p.damping, reverbWetDry = p.wet, reverbPreDelayMs = p.preDelayMs))
        }
        val en = dsp.reverbEnabled
        LabeledSlider("Room size", dsp.reverbRoomSize, 0f..1f, "${(dsp.reverbRoomSize * 100).roundToInt()}%", enabled = en) { onChange(dsp.copy(reverbRoomSize = it)) }
        LabeledSlider("Damping", dsp.reverbDamping, 0f..1f, "${(dsp.reverbDamping * 100).roundToInt()}%", enabled = en) { onChange(dsp.copy(reverbDamping = it)) }
        LabeledSlider("Pre-delay", dsp.reverbPreDelayMs, 0f..200f, "${dsp.reverbPreDelayMs.roundToInt()} ms", enabled = en) { onChange(dsp.copy(reverbPreDelayMs = it)) }
        LabeledSlider("Wet / Dry", dsp.reverbWetDry, 0f..1f, "${(dsp.reverbWetDry * 100).roundToInt()}%", enabled = en) { onChange(dsp.copy(reverbWetDry = it)) }
    }
}

@Composable
private fun SpeedPitchTab(dsp: DspSettings, onChange: (DspSettings) -> Unit) {
    Column(Modifier.card()) {
        LabeledSlider("Speed", dsp.speed, 0.25f..4f, String.format(Locale.US, "%.2fx", dsp.speed)) {
            onChange(dsp.copy(speed = (it * 20).roundToInt() / 20f))
        }
        ChipRow(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), { "${it}x" }, dsp.speed) { onChange(dsp.copy(speed = it)) }
        Spacer(Modifier.height(12.dp))
        LabeledSlider("Pitch", dsp.pitchSemitones, -12f..12f, String.format(Locale.US, "%+.0f st", dsp.pitchSemitones), steps = 23) {
            onChange(dsp.copy(pitchSemitones = it.roundToInt().toFloat()))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Preserve formants", style = MaterialTheme.typography.bodyMedium)
                Text("Keeps voices natural when shifting pitch", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = dsp.preserveFormants, onCheckedChange = { onChange(dsp.copy(preserveFormants = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Crimson),
            )
        }
        Spacer(Modifier.height(8.dp))
        SelectPill("Reset speed & pitch", false) { onChange(dsp.copy(speed = 1f, pitchSemitones = 0f)) }
    }
}
