package com.pnprojects.tarangfx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pnprojects.tarangfx.domain.model.Waveform
import com.pnprojects.tarangfx.ui.theme.AppColors
import kotlin.math.abs
import kotlin.math.max

/**
 * Trim waveform (PRD Screen 4): time ruler, bar waveform, selection with draggable `<` `>` pink
 * handles, white playhead with timestamp, pinch/zoom and horizontal scroll. Uses viewport culling
 * so multi-hour files scroll at display refresh rate.
 *
 * [zoom] = 1 shows the whole file; larger values zoom in.
 */
@Composable
fun TrimWaveform(
    waveform: Waveform?,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    playheadMs: Long,
    keepMode: Boolean,
    zoom: Float,
    isPlaying: Boolean,
    onZoomChange: (Float) -> Unit,
    onSelectionChange: (startMs: Long, endMs: Long) -> Unit,
    onSelectionCommit: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 320.dp,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(1f) }
    var scrollPx by remember { mutableFloatStateOf(0f) }
    var userScrolling by remember { mutableStateOf(false) }

    val dur = max(1L, durationMs)
    val contentWidth = widthPx * zoom
    val pxPerMs = contentWidth / dur

    fun clampScroll(v: Float) = v.coerceIn(0f, max(0f, contentWidth - widthPx))

    // Keep the playhead visible while playing.
    LaunchedEffect(playheadMs, isPlaying, zoom, widthPx) {
        if (isPlaying && !userScrolling) {
            val x = playheadMs * pxPerMs - scrollPx
            if (x < widthPx * 0.1f || x > widthPx * 0.9f) scrollPx = clampScroll(playheadMs * pxPerMs - widthPx * 0.3f)
        }
        scrollPx = clampScroll(scrollPx)
    }

    val start by rememberUpdatedState(startMs)
    val end by rememberUpdatedState(endMs)
    val zoomState by rememberUpdatedState(zoom)
    val ppm by rememberUpdatedState(pxPerMs)
    val durState by rememberUpdatedState(dur)
    val onSel by rememberUpdatedState(onSelectionChange)
    val onCommit by rememberUpdatedState(onSelectionCommit)
    val onSeekState by rememberUpdatedState(onSeek)
    val onZoomState by rememberUpdatedState(onZoomChange)

    Box(modifier.fillMaxWidth().height(height).background(AppColors.Canvas)) {
        Canvas(
            Modifier.fillMaxWidth().height(height)
                // Handles can sit at the screen edges: keep the system back gesture from stealing drags.
                // Android allows at most 200 dp of exclusion per edge, so exclude exactly where the
                // handles are drawn: the left "<" handle near the top, the right ">" one near the bottom.
                .systemGestureExclusion { c ->
                    val top = with(density) { 32.dp.toPx() }
                    Rect(0f, top, c.size.width / 2f, top + with(density) { 200.dp.toPx() })
                }
                .systemGestureExclusion { c ->
                    val bottom = c.size.height - with(density) { 34.dp.toPx() }
                    Rect(c.size.width / 2f, bottom - with(density) { 200.dp.toPx() }, c.size.width.toFloat(), bottom)
                }
                .pointerInput(Unit) {
                    val handleSlop = 28.dp.toPx()
                    val minSelMs = 100L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = start * ppm - scrollPx
                        val endX = end * ppm - scrollPx
                        val dS = abs(down.position.x - startX)
                        val dE = abs(down.position.x - endX)
                        // 0 = none, 1 = start handle, 2 = end handle
                        var target = when {
                            dS <= handleSlop && dS <= dE -> 1
                            dE <= handleSlop -> 2
                            else -> 0
                        }
                        var moved = 0f
                        var pinching = false
                        userScrolling = true
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                pinching = true
                                target = 0
                                val z = event.calculateZoom()
                                val centroid = event.calculateCentroid()
                                if (z != 1f) {
                                    val anchorMs = (scrollPx + centroid.x) / ppm
                                    val newZoom = (zoomState * z).coerceIn(1f, maxZoom(durState))
                                    onZoomState(newZoom)
                                    val newPpm = widthPx * newZoom / durState
                                    scrollPx = (anchorMs * newPpm - centroid.x).coerceIn(0f, max(0f, widthPx * newZoom - widthPx))
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val ch = pressed.first()
                            val dx = ch.positionChange().x
                            moved += abs(dx)
                            when (target) {
                                1 -> {
                                    val t = ((scrollPx + ch.position.x) / ppm).toLong().coerceIn(0L, end - minSelMs)
                                    onSel(t, end)
                                }
                                2 -> {
                                    val t = ((scrollPx + ch.position.x) / ppm).toLong().coerceIn(start + minSelMs, durState)
                                    onSel(start, t)
                                }
                                else -> if (!pinching) {
                                    scrollPx = (scrollPx - dx).coerceIn(0f, max(0f, widthPx * zoomState - widthPx))
                                }
                            }
                            ch.consume()
                        }
                        if (target != 0) onCommit()
                        else if (!pinching && moved < 8f) {
                            onSeekState(((scrollPx + down.position.x) / ppm).toLong().coerceIn(0L, durState))
                        }
                        userScrolling = false
                    }
                },
        ) {
            widthPx = size.width
            val rulerH = 26.dp.toPx()
            val bottomLabelH = 34.dp.toPx()
            val waveTop = rulerH + 6.dp.toPx()
            val waveBottom = size.height - bottomLabelH
            val centerY = (waveTop + waveBottom) / 2f
            val half = (waveBottom - waveTop) / 2f - 4.dp.toPx()
            val visibleStart = (scrollPx / pxPerMs).toLong()
            val visibleEnd = ((scrollPx + size.width) / pxPerMs).toLong()
            val xAt = { t: Long -> t * pxPerMs - scrollPx }

            drawRuler(textMeasurer, visibleStart, visibleEnd, pxPerMs, xAt, rulerH, dur)

            val sx = xAt(startMs)
            val ex = xAt(endMs)
            // Selection backdrop
            drawRect(
                if (keepMode) AppColors.CrimsonContainer else AppColors.Surface,
                topLeft = Offset(sx, waveTop), size = Size(max(0f, ex - sx), waveBottom - waveTop),
            )
            if (!keepMode) {
                drawRect(AppColors.CrimsonContainer.copy(alpha = 0.5f), Offset(0f, waveTop), Size(max(0f, sx), waveBottom - waveTop))
                drawRect(AppColors.CrimsonContainer.copy(alpha = 0.5f), Offset(ex, waveTop), Size(max(0f, size.width - ex), waveBottom - waveTop))
            }

            if (waveform != null) {
                val barW = 3.dp.toPx()
                val gap = 2.dp.toPx()
                drawBarWaveform(
                    waveform, 0L, pxPerMs, -scrollPx, 0f, size.width, centerY, half, barW, gap,
                ) { t ->
                    val inside = t in startMs until endMs
                    if (inside == keepMode) AppColors.WaveActive else AppColors.WaveInactive
                }
            }

            // Selection edges + handles
            val handleW = 18.dp.toPx()
            val handleH = 30.dp.toPx()
            drawLine(AppColors.Crimson, Offset(sx, waveTop), Offset(sx, waveBottom), 2.dp.toPx())
            drawLine(AppColors.Crimson, Offset(ex, waveTop), Offset(ex, waveBottom), 2.dp.toPx())
            // Handles sit outside the selection but are kept on-screen when it touches the edges.
            drawHandle(Offset((sx - handleW).coerceIn(0f, size.width - handleW), waveTop + 6.dp.toPx()), handleW, handleH, left = true)
            drawHandle(Offset(ex.coerceIn(0f, size.width - handleW), waveBottom - handleH - 6.dp.toPx()), handleW, handleH, left = false)

            // Playhead
            val px = xAt(playheadMs)
            if (px in -2f..size.width + 2f) {
                drawLine(AppColors.Playhead, Offset(px, waveTop), Offset(px, waveBottom), 1.5.dp.toPx())
                val tri = Path().apply {
                    moveTo(px, waveBottom + 4.dp.toPx())
                    lineTo(px - 5.dp.toPx(), waveBottom + 11.dp.toPx())
                    lineTo(px + 5.dp.toPx(), waveBottom + 11.dp.toPx())
                    close()
                }
                drawPath(tri, AppColors.Playhead)
                val label = textMeasurer.measure(Fmt.timeTenths(playheadMs), RulerTextStyle)
                val lx = (px - label.size.width / 2f).coerceIn(0f, size.width - label.size.width)
                drawText(label, topLeft = Offset(lx, waveBottom + 13.dp.toPx()))
            }
            // Top/bottom frame lines
            drawLine(AppColors.Divider, Offset(0f, waveTop), Offset(size.width, waveTop), 1f)
            drawLine(AppColors.Divider, Offset(0f, waveBottom), Offset(size.width, waveBottom), 1f)
        }
    }
}

private fun maxZoom(durationMs: Long): Float = max(1f, durationMs / 1000f * 0.5f).coerceAtMost(400f)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandle(topLeft: Offset, w: Float, h: Float, left: Boolean) {
    drawRoundRect(AppColors.Crimson, topLeft, Size(w, h), CornerRadius(w / 2, w / 2))
    val cx = topLeft.x + w / 2
    val cy = topLeft.y + h / 2
    val a = w * 0.18f
    val path = Path().apply {
        if (left) {
            moveTo(cx + a, cy - a * 1.6f); lineTo(cx - a, cy); lineTo(cx + a, cy + a * 1.6f)
        } else {
            moveTo(cx - a, cy - a * 1.6f); lineTo(cx + a, cy); lineTo(cx - a, cy + a * 1.6f)
        }
    }
    drawPath(path, Color.White, style = Stroke(width = 2.dp.toPx()))
}
