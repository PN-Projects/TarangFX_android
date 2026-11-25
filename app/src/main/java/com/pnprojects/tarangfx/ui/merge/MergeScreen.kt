package com.pnprojects.tarangfx.ui.merge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.ui.components.EmptyState
import com.pnprojects.tarangfx.ui.components.ExportProgressDialog
import com.pnprojects.tarangfx.ui.components.ExportSheet
import com.pnprojects.tarangfx.ui.components.Fmt
import com.pnprojects.tarangfx.ui.components.FxSheet
import com.pnprojects.tarangfx.ui.components.PlayButton
import com.pnprojects.tarangfx.ui.components.SavePill
import com.pnprojects.tarangfx.ui.components.ScreenTopBar
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.TabularNums
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MergeScreen(onBack: () -> Unit, onAddAudio: () -> Unit, onOpenOutputs: () -> Unit, vm: MergeViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pos by vm.positionMs.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val statuses by vm.exportStatuses.collectAsStateWithLifecycle()
    val presets by vm.userPresets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var exportCfg by remember { mutableStateOf<ExportConfig?>(null) }
    var trimId by remember { mutableStateOf<Long?>(null) }
    var fxId by remember { mutableStateOf<Long?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    val currentIdx = vm.currentIndex(pos)

    Column(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        ScreenTopBar(
            "Merge", onBack,
            helpText = "Files are joined top to bottom. Long-press a row's handle to reorder, use the scissors to trim a clip and the sliders for per-clip volume/EQ/FX. Add a crossfade to blend clips smoothly.",
            actions = { SavePill(enabled = ui.items.size >= 1) { vm.pause(); scope.launch { exportCfg = vm.defaultExportConfig() } } },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ui.items.size} audios", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort", tint = AppColors.TextPrimary) }
                DropdownMenu(sortMenu, { sortMenu = false }, containerColor = AppColors.SurfaceVariant) {
                    DropdownMenuItem(text = { Text("Sort by name") }, onClick = { vm.sortByName(); sortMenu = false })
                    DropdownMenuItem(text = { Text("Sort by duration") }, onClick = { vm.sortByDuration(); sortMenu = false })
                    DropdownMenuItem(text = { Text("Reverse order") }, onClick = { vm.reverse(); sortMenu = false })
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (ui.items.isEmpty()) {
                EmptyState("No audio yet", "Tap + to add files to merge.", Modifier.align(Alignment.Center))
            }
            val rowH = 84.dp
            val rowPx = with(LocalDensity.current) { rowH.toPx() }
            var draggingId by remember { mutableStateOf<Long?>(null) }
            var dragDy by remember { mutableFloatStateOf(0f) }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(ui.items, key = { _, it -> it.id }) { index, item ->
                    val isDragging = draggingId == item.id
                    Row(
                        Modifier.fillMaxWidth().height(rowH)
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragging) dragDy else 0f; shadowElevation = if (isDragging) 12f else 0f }
                            .background(if (index == currentIdx && ui.items.size > 1) AppColors.CrimsonContainer.copy(alpha = 0.6f) else AppColors.Canvas)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (index == currentIdx) Box(Modifier.width(3.dp).height(44.dp).background(AppColors.Crimson))
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(AppColors.Surface)
                                .clickable { if (playing && index == currentIdx) vm.pause() else vm.playItem(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (playing && index == currentIdx) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", tint = AppColors.TextPrimary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.file.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val trimmed = item.startMs > 0 || item.endMs < item.file.durationMs
                            Text(
                                "${Fmt.time(item.lengthMs)} | ${Fmt.size(item.file.sizeBytes)} | ${item.file.formatLabel}" +
                                    (if (trimmed) " | trimmed" else "") + (if (item.dsp.hasFx) " | FX" else ""),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            )
                        }
                        IconButton(onClick = { trimId = item.id }) { Icon(Icons.Rounded.ContentCut, "Trim clip", tint = AppColors.TextSecondary) }
                        IconButton(onClick = { fxId = item.id }) { Icon(Icons.Rounded.Tune, "Clip effects", tint = AppColors.TextSecondary) }
                        Icon(
                            Icons.Rounded.DragIndicator, "Drag to reorder", tint = AppColors.TextDisabled,
                            modifier = Modifier.size(32.dp).pointerInput(item.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { draggingId = item.id; dragDy = 0f },
                                    onDragEnd = { draggingId = null; dragDy = 0f },
                                    onDragCancel = { draggingId = null; dragDy = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    dragDy += amount.y
                                    val from = vm.ui.value.items.indexOfFirst { it.id == item.id }
                                    if (dragDy > rowPx / 2 && from < vm.ui.value.items.lastIndex) { vm.move(from, from + 1); dragDy -= rowPx }
                                    else if (dragDy < -rowPx / 2 && from > 0) { vm.move(from, from - 1); dragDy += rowPx }
                                }
                            },
                        )
                    }
                }
            }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(20.dp).size(56.dp).clip(CircleShape).background(AppColors.Crimson)
                    .clickable(onClick = onAddAudio),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Add, "Add audio", tint = Color.White) }
        }
        // Crossfade bar
        Column(Modifier.fillMaxWidth().background(AppColors.Surface).padding(horizontal = 20.dp, vertical = 6.dp)) {
            Row {
                Text("Crossfade", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(String.format(Locale.US, "%.1f s", ui.crossfadeMs / 1000f), style = MaterialTheme.typography.bodyMedium.merge(TabularNums), color = AppColors.TextSecondary)
            }
            Slider(
                value = ui.crossfadeMs / 1000f, onValueChange = { vm.setCrossfade((it * 10).roundToInt() * 100L) }, valueRange = 0f..10f,
                enabled = ui.items.size > 1,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AppColors.Crimson, inactiveTrackColor = AppColors.SurfaceVariant),
            )
        }
        // Mini player
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(AppColors.Surface)
                .padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if (ui.items.isEmpty()) 0 else currentIdx + 1}", style = MaterialTheme.typography.titleLarge)
                Text("/${ui.items.size}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(12.dp))
                Text(ui.items.getOrNull(currentIdx)?.file?.title ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${Fmt.time(pos)} / ${Fmt.time(ui.totalMs)}", style = MaterialTheme.typography.bodySmall.merge(TabularNums))
            }
            Slider(
                value = if (ui.totalMs > 0) pos.toFloat() / ui.totalMs else 0f,
                onValueChange = { vm.seekTo((it * ui.totalMs).toLong()) },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AppColors.Crimson, inactiveTrackColor = AppColors.CrimsonContainer),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::prev) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = AppColors.TextPrimary) }
                PlayButton(playing, onClick = vm::togglePlay)
                IconButton(onClick = vm::next) { Icon(Icons.Rounded.SkipNext, "Next", tint = AppColors.TextPrimary) }
            }
        }
    }

    trimId?.let { id ->
        val item = ui.items.firstOrNull { it.id == id }
        if (item == null) { trimId = null; return@let }
        var range by remember(id) { mutableStateOf(item.startMs.toFloat()..item.endMs.toFloat()) }
        AlertDialog(
            onDismissRequest = { trimId = null }, containerColor = AppColors.Surface,
            title = { Text("Trim clip") },
            text = {
                Column {
                    Text(item.file.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    RangeSlider(
                        value = range, onValueChange = { range = it }, valueRange = 0f..item.file.durationMs.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(thumbColor = AppColors.Crimson, activeTrackColor = AppColors.Crimson, inactiveTrackColor = AppColors.SurfaceVariant),
                    )
                    Row {
                        Text(Fmt.timeTenths(range.start.toLong()), style = MaterialTheme.typography.bodySmall.merge(TabularNums), modifier = Modifier.weight(1f))
                        Text(Fmt.timeTenths(range.endInclusive.toLong()), style = MaterialTheme.typography.bodySmall.merge(TabularNums))
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.remove(id); trimId = null }) {
                        Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.error)
                        Text(" Remove from merge", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.setTrim(id, range.start.toLong(), range.endInclusive.toLong()); trimId = null }) { Text("Apply", color = AppColors.Crimson) } },
            dismissButton = { TextButton(onClick = { trimId = null }) { Text("Cancel", color = AppColors.TextSecondary) } },
        )
    }
    fxId?.let { id ->
        val item = ui.items.firstOrNull { it.id == id } ?: return@let
        FxSheet(
            title = item.file.title, dsp = item.dsp, userPresets = presets,
            onChange = { vm.setDsp(id, it) }, onSavePreset = { vm.savePreset(it, item.dsp.eqBands) },
            onDeletePreset = { vm.deletePreset(it) }, onDismiss = { fxId = null },
        )
    }
    exportCfg?.let { cfg ->
        ExportSheet(cfg, title = "Save merged audio", onDismiss = { exportCfg = null }) { exportCfg = null; vm.export(it) }
    }
    ExportProgressDialog(statuses, vm::cancelExport, vm::dismissExport) { vm.dismissExport(); onOpenOutputs() }
}
