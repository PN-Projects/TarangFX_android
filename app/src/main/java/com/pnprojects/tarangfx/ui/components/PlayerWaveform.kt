package com.pnprojects.tarangfx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pnprojects.tarangfx.domain.model.Waveform
import com.pnprojects.tarangfx.ui.theme.AppColors

/** Whole-file bar waveform with played portion highlighted; tap or drag to seek. */
@Composable
fun PlayerWaveform(
    waveform: Waveform?,
    durationMs: Long,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    activeColor: Color = AppColors.WaveActive,
) {
    val dur by rememberUpdatedState(durationMs.coerceAtLeast(1))
    val seek by rememberUpdatedState(onSeek)
    Canvas(
        modifier.fillMaxWidth().height(height).background(AppColors.Surface, RoundedCornerShape(16.dp))
            .pointerInput(Unit) { detectTapGestures { p -> seek((p.x / size.width * dur).toLong().coerceIn(0, dur)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { ch, _ -> seek((ch.position.x / size.width * dur).toLong().coerceIn(0, dur)) }
            },
    ) {
        val pxPerMs = size.width / dur
        val cy = size.height / 2
        val half = size.height / 2 - 10.dp.toPx()
        if (waveform != null) {
            drawBarWaveform(waveform, 0L, pxPerMs, 0f, 0f, size.width, cy, half, 2.5.dp.toPx(), 1.5.dp.toPx()) { t ->
                if (t <= positionMs) activeColor else AppColors.WaveInactive
            }
        }
        val x = positionMs * pxPerMs
        drawLine(AppColors.Playhead, Offset(x, 6.dp.toPx()), Offset(x, size.height - 6.dp.toPx()), 1.5.dp.toPx())
    }
}
