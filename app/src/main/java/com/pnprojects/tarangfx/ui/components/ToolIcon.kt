package com.pnprojects.tarangfx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glossy "3D-style" tool icon built from layered Compose primitives (ASSETS_AND_ICONS.md §2,
 * Option A): diagonal gradient tile, soft top highlight, drop shadow, white glyph.
 */
@Composable
fun GradientToolIcon(icon: ImageVector, colors: List<Color>, iconSize: Dp = 52.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(iconSize * 0.3f)
    Box(
        modifier.size(iconSize)
            .shadow(10.dp, shape, ambientColor = colors.last(), spotColor = colors.last())
            .clip(shape)
            .background(Brush.linearGradient(colors, start = Offset.Zero, end = Offset.Infinite)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Glossy highlight on the upper half
            drawRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), endY = size.height * 0.55f),
            )
        }
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(iconSize * 0.56f))
    }
}
