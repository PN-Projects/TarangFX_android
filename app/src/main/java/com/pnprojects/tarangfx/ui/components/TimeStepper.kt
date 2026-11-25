package com.pnprojects.tarangfx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.AppShapes
import com.pnprojects.tarangfx.ui.theme.TabularNums
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** `-  00:11.6  +` precision stepper. Tap = ±100 ms; long-press repeats with acceleration. */
@Composable
fun TimeStepper(valueMs: Long, onStep: (deltaMs: Long) -> Unit, onCommit: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(AppShapes.Chip).background(AppColors.Surface).height(40.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RepeatButton(Icons.Rounded.Remove, "Minus 0.1 seconds", { onStep(-100) }, onCommit)
        Text(Fmt.timeTenths(valueMs), style = MaterialTheme.typography.titleSmall.merge(TabularNums), modifier = Modifier.padding(horizontal = 8.dp))
        RepeatButton(Icons.Rounded.Add, "Plus 0.1 seconds", { onStep(100) }, onCommit)
    }
}

@Composable
private fun RepeatButton(icon: ImageVector, desc: String, onTick: () -> Unit, onRelease: () -> Unit) {
    val scope = rememberCoroutineScope()
    val tick by rememberUpdatedState(onTick)
    val release by rememberUpdatedState(onRelease)
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    tick()
                    var repeater: Job? = scope.launch {
                        delay(400)
                        var interval = 120L
                        while (true) {
                            tick()
                            delay(interval)
                            interval = (interval * 0.85).toLong().coerceAtLeast(25)
                        }
                    }
                    waitForUpOrCancellation()
                    repeater?.cancel()
                    repeater = null
                    release()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = AppColors.TextPrimary, modifier = Modifier.size(18.dp))
    }
}
