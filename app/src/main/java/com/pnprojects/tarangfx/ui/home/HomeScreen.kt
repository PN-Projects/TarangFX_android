package com.pnprojects.tarangfx.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pnprojects.tarangfx.ui.components.GradientToolIcon
import com.pnprojects.tarangfx.ui.components.InfoDialog
import com.pnprojects.tarangfx.ui.navigation.Tool
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.AppShapes
import kotlin.math.sin

private data class GridTool(val tool: Tool, val label: String, val icon: ImageVector, val colors: List<Color>, val ai: Boolean = false)

private val GRID = listOf(
    GridTool(Tool.MERGE, "Merge", Icons.Rounded.CallMerge, AppColors.ToolMerge),
    GridTool(Tool.MIX, "Mix", Icons.Rounded.LibraryMusic, AppColors.ToolMix),
    GridTool(Tool.RINGTONE, "Ringtone", Icons.Rounded.NotificationsActive, AppColors.ToolRingtone),
    GridTool(Tool.SPLIT, "Split", Icons.Rounded.VerticalSplit, AppColors.ToolSplit),
    GridTool(Tool.VIDEO, "Video to audio", Icons.Rounded.VideoLibrary, AppColors.ToolVideo),
    GridTool(Tool.CONVERT, "Convert format", Icons.Rounded.SwapHoriz, AppColors.ToolConvert),
    GridTool(Tool.RECORD, "Record", Icons.Rounded.Mic, AppColors.ToolRecord),
    GridTool(Tool.DENOISE, "AI Denoise", Icons.Rounded.GraphicEq, AppColors.ToolDenoise, ai = true),
    GridTool(Tool.FX, "FX & EQ", Icons.Rounded.Tune, AppColors.ToolFx),
    GridTool(Tool.TAGS, "Tag Editor", Icons.Rounded.Sell, AppColors.ToolTags),
)

@Composable
fun HomeScreen(onTool: (Tool) -> Unit, onSettings: () -> Unit) {
    var help by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("TarangFX", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.weight(1f))
            IconButton(onClick = { help = true }) { Icon(Icons.Rounded.HelpOutline, "Help", tint = AppColors.TextPrimary) }
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = AppColors.TextPrimary) }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(3) }) { HeroCard { onTool(Tool.TRIM) } }
            items(GRID, key = { it.tool.name }) { t -> ToolCard(t) { onTool(t.tool) } }
            item(span = { GridItemSpan(3) }) {
                Text(
                    "100% offline · no ads · no accounts · every feature free",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }
        }
    }
    if (help) {
        InfoDialog(
            "About TarangFX",
            "A studio-grade audio editor that runs entirely on your device. Pick a tool, choose files, " +
                "and export to MP3, AAC, M4A, FLAC, WAV, OPUS, OGG or ALAC. Exports are saved to Music/TarangFX " +
                "and listed in the Outputs tab. Your original files are never modified.",
        ) { help = false }
    }
}

@Composable
private fun HeroCard(onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(140.dp).clip(AppShapes.Hero)
            .background(Brush.linearGradient(listOf(AppColors.Crimson, AppColors.CrimsonDeep)))
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        // Stylised waveform + scissors illustration on the right
        Canvas(Modifier.fillMaxHeight().width(180.dp).align(Alignment.CenterEnd)) {
            val bars = 16
            val bw = size.width / (bars * 1.6f)
            for (i in 0 until bars) {
                val h = size.height * (0.18f + 0.5f * kotlin.math.abs(sin(i * 0.9f)))
                drawRoundRect(
                    Color.White.copy(alpha = 0.22f),
                    topLeft = Offset(i * bw * 1.6f, (size.height - h) / 2),
                    size = Size(bw, h), cornerRadius = CornerRadius(bw / 2),
                )
            }
        }
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 28.dp).size(64.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.ContentCut, null, tint = AppColors.Crimson, modifier = Modifier.size(34.dp)) }
        Column(Modifier.align(Alignment.CenterStart).padding(start = 24.dp)) {
            Text("Trim audio", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Start trimming", style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.9f))
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, tint = Color.White, modifier = Modifier.size(10.dp)) }
            }
        }
    }
}

@Composable
private fun ToolCard(t: GridTool, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.92f).clip(AppShapes.ToolCard).background(AppColors.Surface)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            GradientToolIcon(t.icon, t.colors)
            Spacer(Modifier.height(14.dp))
            Text(t.label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp), textAlign = TextAlign.Center, maxLines = 2)
        }
        if (t.ai) {
            Box(
                Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(topStart = 16.dp, bottomEnd = 12.dp))
                    .background(Brush.linearGradient(AppColors.ToolDenoise)).padding(horizontal = 8.dp, vertical = 3.dp),
            ) { Text("AI", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Color.White) }
        }
    }
}
