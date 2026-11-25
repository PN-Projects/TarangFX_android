package com.pnprojects.tarangfx.ui.mix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.pnprojects.tarangfx.domain.model.Clip
import com.pnprojects.tarangfx.domain.model.Project
import com.pnprojects.tarangfx.domain.model.Waveform
import com.pnprojects.tarangfx.ui.components.RulerTextStyle
import com.pnprojects.tarangfx.ui.components.drawEnvelope
import com.pnprojects.tarangfx.ui.components.drawRuler
import com.pnprojects.tarangfx.ui.theme.AppColors
import kotlin.math.abs
import kotlin.math.max

const val MIN_LANES = 5

/**
 * Multitrack timeline: lane numbers on the left, clips drawn per lane, fixed white playhead in
 * the centre of the track area. Drag empty space to scrub, drag the selected clip to move it
 * (horizontally and across lanes) with magnetic snapping to the playhead and other clip edges.
 */
@Composable
fun MixTimeline(
    project: Project,
    waveforms: Map<String, Waveform>,
    selectedClipId: Long?,
    playheadMs: Long,
    pxPerMs: Float,
    onScrub: (Long) -> Unit,
    onZoom: (Float) -> Unit,
    onSelect: (Long?) -> Unit,
    onLaneTap: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDragClip: (clipId: Long, startMs: Long, lane: Int) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var localPlayhead by remember { mutableFloatStateOf(playheadMs.toFloat()) }
    var scrubbing by remember { mutableStateOf(false) }
    LaunchedEffect(playheadMs) { if (!scrubbing) localPlayhead = playheadMs.toFloat() }

    val proj by rememberUpdatedState(project)
    val ppm by rememberUpdatedState(pxPerMs)
    val sel by rememberUpdatedState(selectedClipId)
    val cbScrub by rememberUpdatedState(onScrub)
    val cbZoom by rememberUpdatedState(onZoom)
    val cbSelect by rememberUpdatedState(onSelect)
    val cbLane by rememberUpdatedState(onLaneTap)
    val cbDragStart by rememberUpdatedState(onDragStart)
    val cbDrag by rememberUpdatedState(onDragClip)
    val cbDragEnd by rememberUpdatedState(onDragEnd)

    Canvas(
        modifier.background(AppColors.Canvas).systemGestureExclusion().pointerInput(Unit) {
            val labelW = 40.dp.toPx()
            val rulerH = 28.dp.toPx()
            val laneH = 60.dp.toPx()
            val snapPx = 10.dp.toPx()
            fun trackCenter() = labelW + (size.width - labelW) / 2f
            fun msAt(x: Float) = localPlayhead + (x - trackCenter()) / ppm
            fun laneAt(y: Float) = ((y - rulerH) / laneH).toInt()
            fun clipAt(x: Float, y: Float): Pair<Clip, Int>? {
                val lane = laneAt(y)
                val t = msAt(x).toLong()
                val clip = proj.tracks.getOrNull(lane)?.clips?.firstOrNull { t >= it.timelineStartMs && t < it.timelineEndMs }
                return clip?.let { it to lane }
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                val hit = if (down.position.x > labelW) clipAt(down.position.x, down.position.y) else null
                val draggingClip = hit != null && hit.first.id == sel
                var clipStart = hit?.first?.timelineStartMs?.toFloat() ?: 0f
                var moved = 0f
                var pinch = false
                var accX = 0f
                var accY = 0f
                var decided = draggingClip
                if (draggingClip) cbDragStart() else scrubbing = true
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size > 1) {
                        pinch = true
                        val z = ev.calculateZoom()
                        if (z != 1f) cbZoom(ppm * z)
                        ev.changes.forEach { it.consume() }
                        continue
                    }
                    if (pinch) continue
                    val ch = pressed[0]
                    val d = ch.positionChange()
                    moved += abs(d.x) + abs(d.y)
                    if (!decided) {
                        accX += d.x
                        accY += d.y
                        if (abs(accX) + abs(accY) < viewConfiguration.touchSlop) continue
                        decided = true
                        if (abs(accY) > abs(accX)) {
                            // Vertical gesture: leave it unconsumed so the lane list scrolls.
                            scrubbing = false
                            return@awaitEachGesture
                        }
                    }
                    if (draggingClip && hit != null) {
                        clipStart += d.x / ppm
                        var start = clipStart.toLong().coerceAtLeast(0)
                        // Magnetic snapping: playhead and edges of other clips.
                        val len = hit.first.timelineLengthMs
                        val targets = buildList {
                            add(localPlayhead.toLong())
                            proj.allClips.filter { it.id != hit.first.id }.forEach { add(it.timelineStartMs); add(it.timelineEndMs) }
                            add(0L)
                        }
                        val snapMs = snapPx / ppm
                        targets.minByOrNull { abs(it - start) }?.let { if (abs(it - start) < snapMs) start = it }
                        targets.minByOrNull { abs(it - (start + len)) }?.let { if (abs(it - (start + len)) < snapMs) start = it - len }
                        val lane = laneAt(ch.position.y).coerceIn(0, max(MIN_LANES, proj.tracks.size))
                        cbDrag(hit.first.id, start.coerceAtLeast(0), lane)
                    } else if (abs(d.x) > 0f && down.position.x > labelW) {
                        localPlayhead = (localPlayhead - d.x / ppm).coerceAtLeast(0f)
                        cbScrub(localPlayhead.toLong())
                    }
                    ch.consume()
                }
                if (draggingClip) cbDragEnd()
                scrubbing = false
                if (!pinch && moved < 10f) {
                    if (down.position.x <= labelW) cbLane(laneAt(down.position.y))
                    else {
                        val h = clipAt(down.position.x, down.position.y)
                        if (h == null && laneAt(down.position.y) >= proj.tracks.count { it.clips.isNotEmpty() } &&
                            proj.tracks.getOrNull(laneAt(down.position.y))?.clips.isNullOrEmpty()) {
                            cbLane(laneAt(down.position.y))
                        } else cbSelect(h?.first?.id)
                    }
                }
            }
        },
    ) {
        val labelW = 40.dp.toPx()
        val rulerH = 28.dp.toPx()
        val laneH = 60.dp.toPx()
        val clipPad = 5.dp.toPx()
        val center = labelW + (size.width - labelW) / 2f
        val ph = localPlayhead
        val xAt = { t: Long -> center + (t - ph) * pxPerMs }
        val lanes = max(MIN_LANES, project.tracks.size + 1)
        val duration = max(project.durationMs, ph.toLong() + 10_000)

        clipRect(left = labelW) {
            val visStart = max(0L, (ph - (center - labelW) / pxPerMs).toLong())
            val visEnd = (ph + (size.width - center) / pxPerMs).toLong()
            drawRuler(measurer, visStart, visEnd, pxPerMs, xAt, rulerH, duration)
            // Lanes + clips
            for (lane in 0 until lanes) {
                val top = rulerH + lane * laneH
                drawLine(AppColors.Divider, Offset(labelW, top + laneH), Offset(size.width, top + laneH), 1f)
                val track = project.tracks.getOrNull(lane) ?: continue
                val dim = track.muted || (project.tracks.any { it.solo } && !track.solo)
                track.clips.forEach { c ->
                    val l = xAt(c.timelineStartMs)
                    val r = xAt(c.timelineEndMs)
                    if (r < labelW || l > size.width) return@forEach
                    val selected = c.id == selectedClipId
                    val fill = if (dim) AppColors.SurfaceVariant else AppColors.ClipFill
                    drawRoundRect(fill, Offset(l, top + clipPad), Size(r - l, laneH - 2 * clipPad), CornerRadius(6.dp.toPx()))
                    clipRect(max(l, labelW), top + clipPad, r, top + laneH - clipPad) {
                        waveforms[c.uri]?.let { wf ->
                            drawEnvelope(wf, c.sourceStartMs, c.sourceEndMs, l, pxPerMs, c.dsp.speed,
                                top + clipPad + 14.dp.toPx(), top + laneH - clipPad - 2f,
                                if (dim) AppColors.TextDisabled else AppColors.Crimson.copy(alpha = 0.85f))
                        }
                        // Fade ramps
                        if (c.fadeInMs > 0) {
                            val fx = c.fadeInMs * pxPerMs
                            drawPath(Path().apply { moveTo(l, top + laneH - clipPad); lineTo(l + fx, top + clipPad); lineTo(l, top + clipPad); close() }, Color.Black.copy(alpha = 0.35f))
                        }
                        if (c.fadeOutMs > 0) {
                            val fx = c.fadeOutMs * pxPerMs
                            drawPath(Path().apply { moveTo(r, top + laneH - clipPad); lineTo(r - fx, top + clipPad); lineTo(r, top + clipPad); close() }, Color.Black.copy(alpha = 0.35f))
                        }
                        val tag = measurer.measure(c.title, RulerTextStyle.copy(color = AppColors.TextPrimary))
                        drawText(tag, topLeft = Offset(max(l, labelW) + 6.dp.toPx(), top + clipPad + 1.dp.toPx()))
                    }
                    if (selected) {
                        drawRoundRect(AppColors.Crimson, Offset(l, top + clipPad), Size(r - l, laneH - 2 * clipPad), CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
                        // Crimson grab handle on the left edge (ref_ui 07)
                        drawRoundRect(AppColors.Crimson, Offset(l - 2.dp.toPx(), top + clipPad), Size(14.dp.toPx(), laneH - 2 * clipPad), CornerRadius(4.dp.toPx()))
                        val cy = top + laneH / 2
                        drawPath(Path().apply {
                            moveTo(l + 3.dp.toPx(), cy - 5.dp.toPx()); lineTo(l + 7.dp.toPx(), cy); lineTo(l + 3.dp.toPx(), cy + 5.dp.toPx())
                        }, Color.White, style = Stroke(2.dp.toPx()))
                    }
                }
            }
            drawLine(AppColors.Playhead, Offset(center, rulerH - 6.dp.toPx()), Offset(center, size.height), 1.5.dp.toPx())
        }
        // Lane labels
        for (lane in 0 until lanes) {
            val top = rulerH + lane * laneH
            val track = project.tracks.getOrNull(lane)
            val hasClips = track?.clips?.isNotEmpty() == true
            val label = if (hasClips) "${lane + 1}" else "+"
            val flags = buildString { if (track?.muted == true) append("M"); if (track?.solo == true) append("S") }
            val layout = measurer.measure(label, RulerTextStyle.copy(color = AppColors.TextPrimary, fontSize = RulerTextStyle.fontSize * 1.6f))
            drawText(layout, topLeft = Offset((labelW - layout.size.width) / 2f, top + (laneH - layout.size.height) / 2f))
            if (flags.isNotEmpty()) {
                val f = measurer.measure(flags, RulerTextStyle.copy(color = AppColors.Crimson))
                drawText(f, topLeft = Offset((labelW - f.size.width) / 2f, top + laneH - f.size.height - 2f))
            }
        }
        drawLine(AppColors.Divider, Offset(labelW, rulerH), Offset(labelW, size.height), 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
    }
}
