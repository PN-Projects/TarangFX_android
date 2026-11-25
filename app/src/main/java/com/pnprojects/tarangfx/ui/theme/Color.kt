package com.pnprojects.tarangfx.ui.theme

import androidx.compose.ui.graphics.Color

/** Design tokens from PRD §2.1 (dark-only theme). */
object AppColors {
    val Canvas = Color(0xFF0A0A0C)
    val Surface = Color(0xFF16161A)
    val SurfaceVariant = Color(0xFF222228)
    val Crimson = Color(0xFFE91E5A)
    val CrimsonDeep = Color(0xFFC81448)
    val CrimsonContainer = Color(0xFF3D1220)
    val ClipFill = Color(0xFF6B122E)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF9E9EA8)
    val TextDisabled = Color(0xFF52525B)
    val Divider = Color(0xFF2A2A32)
    val WaveInactive = Color(0xFF4A4A54)
    val WaveActive = Color(0xFFE91E5A)
    val Playhead = Color(0xFFFFFFFF)
    val Success = Color(0xFF2ECC71)

    // Tool icon gradients (ASSETS_AND_ICONS.md §2, Option A)
    val ToolMerge = listOf(Color(0xFF29B6F6), Color(0xFF0288D1))
    val ToolMix = listOf(Color(0xFFFFB300), Color(0xFFF57C00))
    val ToolRingtone = listOf(Color(0xFFAB47BC), Color(0xFF7B1FA2))
    val ToolSplit = listOf(Color(0xFF26A69A), Color(0xFF00897B))
    val ToolVideo = listOf(Color(0xFFEC407A), Color(0xFFAD1457))
    val ToolConvert = listOf(Color(0xFF26C6DA), Color(0xFF0097A7))
    val ToolRecord = listOf(Color(0xFFEF5350), Color(0xFFC62828))
    val ToolDenoise = listOf(Color(0xFF7E57C2), Color(0xFF4527A0))
    val ToolFx = listOf(Color(0xFF42A5F5), Color(0xFF1565C0))
    val ToolTags = listOf(Color(0xFFFFCA28), Color(0xFFFF8F00))
}
