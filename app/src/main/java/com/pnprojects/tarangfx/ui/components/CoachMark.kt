package com.pnprojects.tarangfx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pnprojects.tarangfx.ui.theme.AppColors

data class CoachStep(val text: String, val alignment: Alignment)

/** Dimmed overlay with a crimson speech-bubble tooltip (ref_ui 09–11). Tap anywhere to advance. */
@Composable
fun CoachMarks(steps: List<CoachStep>, index: Int, onNext: () -> Unit, onSkip: () -> Unit) {
    val step = steps.getOrNull(index) ?: return
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onNext),
    ) {
        Column(
            Modifier.align(step.alignment).padding(24.dp).widthIn(max = 300.dp)
                .background(AppColors.Crimson, RoundedCornerShape(16.dp)).padding(16.dp),
        ) {
            Text(step.text, style = MaterialTheme.typography.titleSmall, color = Color.White)
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}/${steps.size}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.weight(1f))
                Text("Skip", style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.clickable(onClick = onSkip).padding(6.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (index == steps.lastIndex) "Done" else "Next", style = MaterialTheme.typography.labelLarge, color = Color.White,
                    modifier = Modifier.clickable(onClick = onNext).padding(6.dp))
            }
        }
    }
}
