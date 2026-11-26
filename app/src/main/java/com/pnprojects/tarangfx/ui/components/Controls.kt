package com.pnprojects.tarangfx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.AppShapes

@Composable
fun ScreenTopBar(
    title: String,
    onBack: (() -> Unit)?,
    helpTitle: String? = null,
    helpText: String? = null,
    actions: @Composable () -> Unit = {},
) {
    var showHelp by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = AppColors.TextPrimary)
            }
        } else Spacer(Modifier.width(12.dp))
        Text(
            title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (helpText != null) {
            IconButton(onClick = { showHelp = true }) {
                Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = "Help", tint = AppColors.TextPrimary)
            }
        }
        actions()
        Spacer(Modifier.width(8.dp))
    }
    if (showHelp && helpText != null) {
        InfoDialog(title = helpTitle ?: title, text = helpText, onDismiss = { showHelp = false })
    }
}

@Composable
fun SavePill(text: String = "Save", enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(AppShapes.PrimaryPill)
            .background(if (enabled) AppColors.Crimson else AppColors.SurfaceVariant)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else AppColors.TextDisabled)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = AppShapes.PrimaryPill,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.Crimson, contentColor = Color.White,
            disabledContainerColor = AppColors.SurfaceVariant, disabledContentColor = AppColors.TextDisabled,
        ),
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp)) }
}

@Composable
fun PlayButton(isPlaying: Boolean, size: Dp = 56.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(AppColors.Playhead)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = Color.Black, modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** Two-segment pill: (Undo | Redo) or (Zoom out | Zoom in). */
@Composable
fun DualPill(
    left: ImageVector, leftDesc: String, leftEnabled: Boolean, onLeft: () -> Unit,
    right: ImageVector, rightDesc: String, rightEnabled: Boolean, onRight: () -> Unit,
) {
    Row(
        Modifier.clip(AppShapes.Chip).background(AppColors.SurfaceVariant).height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onLeft, enabled = leftEnabled, modifier = Modifier.size(width = 48.dp, height = 36.dp)) {
            Icon(left, leftDesc, tint = if (leftEnabled) AppColors.TextPrimary else AppColors.TextDisabled, modifier = Modifier.size(20.dp))
        }
        Box(Modifier.width(1.dp).height(18.dp).background(AppColors.Divider))
        IconButton(onClick = onRight, enabled = rightEnabled, modifier = Modifier.size(width = 48.dp, height = 36.dp)) {
            Icon(right, rightDesc, tint = if (rightEnabled) AppColors.TextPrimary else AppColors.TextDisabled, modifier = Modifier.size(20.dp))
        }
    }
}

/** Large dark action card (Split / Delete / Add audio / Record). */
@Composable
fun ActionCard(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, highlighted: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier.clip(AppShapes.ToolCard)
            .background(if (highlighted) AppColors.CrimsonContainer else AppColors.Surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (enabled) AppColors.TextPrimary else AppColors.TextDisabled, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (enabled) AppColors.TextPrimary else AppColors.TextDisabled)
    }
}

/** Small icon + label tool for horizontally scrolling docks. */
@Composable
fun DockTool(icon: ImageVector, label: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val tint = when {
            !enabled -> AppColors.TextDisabled
            active -> AppColors.Crimson
            else -> AppColors.TextPrimary
        }
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

/** Selectable pill (mode toggles, filter chips, format chips). */
@Composable
fun SelectPill(text: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(AppShapes.Chip)
            .background(if (selected) AppColors.Crimson else AppColors.SurfaceVariant)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = when { !enabled -> AppColors.TextDisabled; selected -> Color.White; else -> AppColors.TextSecondary },
        )
    }
}

/** Outlined dark pill used by Trim mode toggles. */
@Composable
fun ModePill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(AppShapes.Chip)
            .background(if (selected) AppColors.SurfaceVariant else AppColors.Surface)
            .then(if (selected) Modifier.border(1.dp, AppColors.Crimson, AppShapes.Chip) else Modifier)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = if (selected) AppColors.TextPrimary else AppColors.TextDisabled)
    }
}

@Composable
fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it", color = AppColors.Crimson) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.Surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = AppColors.Crimson) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = AppColors.TextSecondary) } },
    )
}

@Composable
fun EmptyState(title: String, subtitle: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

val ScreenPadding = PaddingValues(horizontal = 20.dp)
