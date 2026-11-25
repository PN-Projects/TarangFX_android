package com.pnprojects.tarangfx.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Corner radii tokens (PRD §2.2 / UI_DESIGN_SYSTEM.md §2). */
object AppShapes {
    val ToolCard = RoundedCornerShape(16.dp)
    val Hero = RoundedCornerShape(20.dp)
    val PrimaryPill = RoundedCornerShape(28.dp)
    val SecondaryButton = RoundedCornerShape(12.dp)
    val Chip = RoundedCornerShape(20.dp)
    val Sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
}

private val scheme = darkColorScheme(
    primary = AppColors.Crimson,
    onPrimary = AppColors.TextPrimary,
    primaryContainer = AppColors.CrimsonContainer,
    onPrimaryContainer = AppColors.TextPrimary,
    secondary = AppColors.Crimson,
    onSecondary = AppColors.TextPrimary,
    background = AppColors.Canvas,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Canvas,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceVariant,
    onSurfaceVariant = AppColors.TextSecondary,
    surfaceContainer = AppColors.Surface,
    surfaceContainerHigh = AppColors.Surface,
    surfaceContainerHighest = AppColors.SurfaceVariant,
    surfaceContainerLow = AppColors.Surface,
    surfaceContainerLowest = AppColors.Canvas,
    outline = AppColors.Divider,
    outlineVariant = AppColors.Divider,
    error = androidx.compose.ui.graphics.Color(0xFFFF5370),
)

@Composable
fun TarangFXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
