package com.pnprojects.tarangfx.ui.split

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pnprojects.tarangfx.domain.model.ExportConfig
import com.pnprojects.tarangfx.domain.model.Waveform
import com.pnprojects.tarangfx.ui.components.ActionCard
import com.pnprojects.tarangfx.ui.components.CoachMarks
import com.pnprojects.tarangfx.ui.components.CoachStep
import com.pnprojects.tarangfx.ui.components.DualPill
import com.pnprojects.tarangfx.ui.components.ExportProgressDialog
import com.pnprojects.tarangfx.ui.components.ExportSheet
import com.pnprojects.tarangfx.ui.components.Fmt
import com.pnprojects.tarangfx.ui.components.PlayButton
import com.pnprojects.tarangfx.ui.components.RulerTextStyle
import com.pnprojects.tarangfx.ui.components.SavePill
import com.pnprojects.tarangfx.ui.components.ScreenTopBar
import com.pnprojects.tarangfx.ui.components.drawEnvelope
import com.pnprojects.tarangfx.ui.components.drawRuler
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.TabularNums
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

@Composable
fun SplitScreen(onBack: () -> Unit, onOpenOutputs: () -> Unit, vm: SplitViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val doc by vm.doc.collectAsStateWithLifecycle()
    val pos by vm.positionMs.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val active by vm.activeCount.collectAsStateWithLifecycle()
    val statuses by vm.exportStatuses.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var exportCfg by remember { mutableStateOf<ExportConfig?>(null) }

    Box(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(
                "Split", onBack,
                helpText = "Drag the waveform to move under the white playhead, tap Split to cut there. Tap a segment to select it and Delete to drop it. Save exports every remaining segment as its own file.",
                actions = { SavePill(enabled = active > 0) { vm.pause(); scope.launch { exportCfg = vm.defaultExportConfig() } } },
            )
            val segments = doc.segments
            SplitTimeline(
                waveform = ui.waveform, title = ui.file?.title ?: "", durationMs = ui.durationMs,
                segments = segments, selected = ui.selected, playheadMs = pos, pxPerMs = ui.pxPerMs,
                onScrub = vm::scrub, onZoom = vm::setZoom, onTap = vm::selectAt,
                modifier = Modifier.fillMaxWidth().height(340.dp),
            )
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                DualPill(Icons.AutoMirrored.Rounded.Undo, "Undo", canUndo, vm::undo, Icons.AutoMirrored.Rounded.Redo, "Redo", canRedo, vm::redo)
                Spacer(Modifier.weight(1f))
                Text(Fmt.timeTenths(ui.durationMs), style = MaterialTheme.typography.titleSmall.merge(TabularNums), color = AppColors.TextSecondary)
                Spacer(Modifier.weight(1f))
                DualPill(Icons.Rounded.ZoomOut, "Zoom out", true, vm::zoomOut, Icons.Rounded.ZoomIn, "Zoom in", true, vm::zoomIn)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = vm::jumpToStart) { Icon(Icons.Rounded.SkipPrevious, "Jump to start", tint = AppColors.TextPrimary) }
                Spacer(Modifier.width(48.dp))
                PlayButton(playing, onClick = vm::togglePlay)
                Spacer(Modifier.width(96.dp))
            }
            Row(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionCard(Icons.Rounded.ContentCut, "Split", Modifier.weight(1f), onClick = vm::split)
                ActionCard(Icons.Rounded.DeleteOutline, "Delete", Modifier.weight(1f), enabled = ui.selected >= 0, onClick = vm::deleteSelected)
            }
        }
        if (ui.coachStep >= 0) {
            CoachMarks(
                listOf(
                    CoachStep("Drag the waveform left or right to move to the point you want to cut.", Alignment.Center),
                    CoachStep("Tap \"Split\" to divide the audio at the playhead.", Alignment.BottomStart),
                    CoachStep("Tap \"Save\" to export every segment as a separate file.", Alignment.TopEnd),
                ),
                ui.coachStep, vm::nextCoach, vm::skipCoach,
            )
        }
    }
    exportCfg?.let { cfg ->
        ExportSheet(cfg, title = "Save $active segment${if (active == 1) "" else "s"}", onDismiss = { exportCfg = null }) {
            exportCfg = null
            vm.export(it)
        }
    }
    ExportProgressDialog(statuses, vm::cancelExport, vm::dismissExport) { vm.dismissExport(); onOpenOutputs() }
}

/** Fixed centre playhead; the clip scrolls beneath it. Drag = scrub, pinch = zoom, tap = select. */
@Composable
fun SplitTimeline(
    waveform: Waveform?, title: String, durationMs: Long, segments: List<Segment>, selected: Int,
    playheadMs: Long, pxPerMs: Float, onScrub: (Long) -> Unit, onZoom: (Float) -> Unit, onTap: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var localPlayhead by remember { mutableFloatStateOf(playheadMs.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(playheadMs) { if (!dragging) localPlayhead = playheadMs.toFloat() }
    val ppm by rememberUpdatedState(pxPerMs)
    val dur by rememberUpdatedState(durationMs)
    val scrub by rememberUpdatedState(onScrub)
    val zoom by rememberUpdatedState(onZoom)
    val tap by rememberUpdatedState(onTap)

    // Fit whole file on first layout.
    var fitted by remember { mutableStateOf(false) }

    Canvas(
        modifier.background(AppColors.Canvas).systemGestureExclusion().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var moved = 0f
                var pinch = false
                dragging = true
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size > 1) {
                        pinch = true
                        val z = ev.calculateZoom()
                        if (z != 1f) zoom(ppm * z)
                    } else if (!pinch) {
                        val dx = pressed[0].positionChange().x
                        moved += abs(dx)
                        localPlayhead = (localPlayhead - dx / ppm).coerceIn(0f, dur.toFloat())
                        scrub(localPlayhead.toLong())
                    }
                    ev.changes.forEach { it.consume() }
                }
                dragging = false
                if (!pinch && moved < 8f) {
                    val t = localPlayhead + (down.position.x - size.width / 2f) / ppm
                    tap(t.toLong())
                }
            }
        },
    ) {
        if (!fitted && durationMs > 0) {
            fitted = true
            onZoom(size.width * 0.9f / durationMs)
        }
        val center = size.width / 2f
        val ph = localPlayhead
        val xAt = { t: Long -> center + (t - ph) * pxPerMs }
        val rulerH = 26.dp.toPx()
        val visStart = max(0L, (ph - center / pxPerMs).toLong())
        val visEnd = (ph + center / pxPerMs).toLong()
        drawRuler(measurer, visStart, visEnd, pxPerMs, xAt, rulerH, durationMs)

        // Playhead time label
        val label = measurer.measure(Fmt.timeTenths(ph.toLong()), RulerTextStyle.copy(color = AppColors.TextPrimary))
        drawText(label, topLeft = Offset(center - label.size.width / 2f, rulerH + 8.dp.toPx()))

        val laneTop = size.height * 0.42f
        val laneH = 72.dp.toPx()
        val gap = 2.dp.toPx()
        segments.forEachIndexed { i, s ->
            val l = xAt(s.startMs) + if (i > 0) gap else 0f
            val r = xAt(s.endMs)
            if (r < 0 || l > size.width) return@forEachIndexed
            if (s.deleted) {
                drawRoundRect(AppColors.Divider, Offset(l, laneTop), Size(r - l, laneH), CornerRadius(8.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                return@forEachIndexed
            }
            drawRoundRect(AppColors.ClipFill, Offset(l, laneTop), Size(r - l, laneH), CornerRadius(8.dp.toPx()))
            clipRect(l, laneTop, r, laneTop + laneH) {
                if (waveform != null) {
                    drawEnvelope(waveform, s.startMs, s.endMs, l, pxPerMs, 1f, laneTop + 18.dp.toPx(), laneTop + laneH - 2.dp.toPx(), AppColors.Crimson.copy(alpha = 0.85f))
                }
                val tag = measurer.measure(if (segments.size > 1) "${i + 1}  $title" else title, RulerTextStyle.copy(color = AppColors.TextPrimary))
                drawRoundRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), Offset(l + 6.dp.toPx(), laneTop + 4.dp.toPx()),
                    Size(tag.size.width + 8.dp.toPx(), tag.size.height.toFloat()), CornerRadius(4.dp.toPx()))
                drawText(tag, topLeft = Offset(l + 10.dp.toPx(), laneTop + 4.dp.toPx()))
            }
            drawRoundRect(
                if (i == selected) AppColors.TextPrimary else AppColors.Crimson, Offset(l, laneTop), Size(r - l, laneH),
                CornerRadius(8.dp.toPx()), style = Stroke(if (i == selected) 2.5.dp.toPx() else 1.5.dp.toPx()),
            )
        }
        // Fixed centre playhead
        drawLine(AppColors.Playhead, Offset(center, rulerH + 28.dp.toPx()), Offset(center, size.height), 1.5.dp.toPx())
    }
}
