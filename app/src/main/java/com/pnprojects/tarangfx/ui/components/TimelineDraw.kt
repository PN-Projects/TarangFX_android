package com.pnprojects.tarangfx.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import com.pnprojects.tarangfx.domain.model.Waveform
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.JakartaSans
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val TICK_STEPS_MS = longArrayOf(100, 250, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000)

/** Picks a ruler label interval so labels are at least [minLabelPx] apart. */
fun rulerStep(pxPerMs: Float, minLabelPx: Float): Long =
    TICK_STEPS_MS.firstOrNull { it * pxPerMs >= minLabelPx } ?: TICK_STEPS_MS.last()

val RulerTextStyle = TextStyle(fontFamily = JakartaSans, fontSize = 10.sp, color = AppColors.TextSecondary, fontFeatureSettings = "tnum")

/**
 * Draws a time ruler. [msAtX] maps screen x to time; [xAtMs] maps time to screen x.
 * Only ticks inside the visible window are iterated (viewport culling).
 */
fun DrawScope.drawRuler(
    textMeasurer: TextMeasurer,
    visibleStartMs: Long,
    visibleEndMs: Long,
    pxPerMs: Float,
    xAtMs: (Long) -> Float,
    height: Float,
    durationMs: Long,
) {
    val step = rulerStep(pxPerMs, 64.dp2px(density))
    val minor = max(1L, step / 5)
    val first = (floor(visibleStartMs.toDouble() / minor).toLong() * minor).coerceAtLeast(0)
    var t = first
    while (t <= min(visibleEndMs, durationMs + step)) {
        val x = xAtMs(t)
        val major = t % step == 0L
        val tickH = if (major) height * 0.35f else height * 0.18f
        drawLine(AppColors.TextDisabled, Offset(x, height - tickH), Offset(x, height), strokeWidth = 1f)
        if (major) {
            val label = if (step < 1000) Fmt.timeTenths(t) else Fmt.time(t)
            val layout = textMeasurer.measure(label, RulerTextStyle)
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, 2f))
        }
        t += minor
    }
}

private fun Int.dp2px(density: Float) = this * density

/**
 * Draws a mirrored "bar" waveform (vertical rounded bars, ref_ui style) for the time window that
 * is visible. Each bar aggregates the max peak across its time span.
 */
fun DrawScope.drawBarWaveform(
    waveform: Waveform,
    sourceStartMs: Long,
    pxPerMs: Float,
    contentStartX: Float,
    clipLeft: Float,
    clipRight: Float,
    centerY: Float,
    maxHalfHeight: Float,
    barWidth: Float,
    gap: Float,
    colorAt: (timeMs: Long) -> androidx.compose.ui.graphics.Color,
) {
    if (waveform.isEmpty) return
    val pitch = barWidth + gap
    val left = max(clipLeft, 0f)
    val right = min(clipRight, size.width)
    if (right <= left) return
    val firstBar = floor((left - contentStartX) / pitch).toInt().coerceAtLeast(0)
    val lastBar = ceil((right - contentStartX) / pitch).toInt()
    val pps = waveform.peaksPerSecond
    val peaks = waveform.peaks
    for (b in firstBar..lastBar) {
        val x = contentStartX + b * pitch
        if (x + barWidth < left || x > right) continue
        val t0 = sourceStartMs + (b * pitch / pxPerMs).toLong()
        val t1 = sourceStartMs + ((b + 1) * pitch / pxPerMs).toLong()
        val i0 = (t0 * pps / 1000).toInt().coerceIn(0, peaks.size - 1)
        val i1 = (t1 * pps / 1000).toInt().coerceIn(i0 + 1, peaks.size)
        val p = waveform.maxPeak(i0, i1)  // O(log n), not O(peaks under the bar)
        // Slight perceptual lift so quiet passages remain visible.
        val h = max(2f, (Math.sqrt(p.toDouble()).toFloat()) * maxHalfHeight)
        drawRoundRect(
            color = colorAt(t0),
            topLeft = Offset(x, centerY - h),
            size = androidx.compose.ui.geometry.Size(barWidth, h * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2, barWidth / 2),
        )
    }
}

/** Filled envelope waveform used inside clip boxes (Split / Mix timelines). */
fun DrawScope.drawEnvelope(
    waveform: Waveform,
    sourceStartMs: Long,
    sourceEndMs: Long,
    clipLeftX: Float,
    pxPerMs: Float,
    speed: Float,
    top: Float,
    bottom: Float,
    color: androidx.compose.ui.graphics.Color,
) {
    if (waveform.isEmpty) return
    val step = 2f
    val visL = max(0f, clipLeftX)
    val clipW = (sourceEndMs - sourceStartMs) / speed * pxPerMs
    val visR = min(size.width, clipLeftX + clipW)
    if (visR <= visL) return
    val pps = waveform.peaksPerSecond
    val peaks = waveform.peaks
    val path = androidx.compose.ui.graphics.Path()
    path.moveTo(visL, bottom)
    var x = visL
    while (x <= visR) {
        val tSrc = sourceStartMs + ((x - clipLeftX) / pxPerMs * speed).toLong()
        val tSrc2 = sourceStartMs + ((x + step - clipLeftX) / pxPerMs * speed).toLong()
        val i0 = (tSrc * pps / 1000).toInt().coerceIn(0, peaks.size - 1)
        val i1 = (tSrc2 * pps / 1000).toInt().coerceIn(i0 + 1, peaks.size)
        val p = waveform.maxPeak(i0, i1)
        val y = bottom - (bottom - top) * Math.sqrt(p.toDouble()).toFloat()
        path.lineTo(x, y)
        x += step
    }
    path.lineTo(visR, bottom)
    path.close()
    drawPath(path, color)
}
