package com.pnprojects.tarangfx.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pnprojects.tarangfx.R

/** Plus Jakarta Sans (SIL OFL 1.1), bundled locally — no downloadable fonts (offline mandate). */
val JakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold),
)

/** Tabular numerals so timestamps never jitter during playback (UI_DESIGN_SYSTEM.md §2). */
val TabularNums = TextStyle(fontFeatureSettings = "tnum")

private val base = TextStyle(fontFamily = JakartaSans, color = AppColors.TextPrimary)

val AppTypography = Typography(
    displaySmall = base.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold),
    headlineMedium = base.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    headlineSmall = base.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = base.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = base.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = base.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.copy(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = base.copy(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    bodySmall = base.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = AppColors.TextSecondary),
    labelLarge = base.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
    labelMedium = base.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = base.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)
