package com.fitcompanion.poc.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fitcompanion.poc.R

/** Sporty dark palette: near-black track, volt accent, one colour per activity type. */
object Palette {
    val Bg = Color(0xFF0B0E13)
    val Surface = Color(0xFF141922)
    val SurfaceHigh = Color(0xFF1C2330)
    val Outline = Color(0xFF2A3342)
    val Text = Color(0xFFF2F5F8)
    val TextDim = Color(0xFF8C97A8)
    val Volt = Color(0xFFD4FF3A)
    val Run = Color(0xFFFF6A3D)
    val Walk = Color(0xFF35D6C4)
    val Climb = Color(0xFF8B7BFF)
    val Sleep = Color(0xFF6EA8FF)
    val Heart = Color(0xFFFF4D6D)
    val Warn = Color(0xFFFFC23A)
    val Good = Color(0xFF4ADE80)

    val VoltGlow = Brush.linearGradient(listOf(Color(0xFFD4FF3A), Color(0xFF35D6C4)))
    val ClimbGlow = Brush.linearGradient(listOf(Color(0xFF8B7BFF), Color(0xFF3DB2FF)))
    val RunGlow = Brush.linearGradient(listOf(Color(0xFFFF6A3D), Color(0xFFFFC23A)))
}

val Condensed = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

@OptIn(ExperimentalTextApi::class)
val Inter = FontFamily(
    Font(R.font.inter, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

private val Base = Typography()

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 60.sp, letterSpacing = (-0.01).em),
    displayMedium = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 46.sp),
    displaySmall = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 36.sp),
    headlineLarge = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = 0.01.em),
    headlineMedium = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = Base.bodyLarge.copy(fontFamily = Inter),
    bodyMedium = Base.bodyMedium.copy(fontFamily = Inter),
    bodySmall = Base.bodySmall.copy(fontFamily = Inter),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.02.em),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.06.em),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.08.em),
)

@Composable
fun FitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Volt, onPrimary = Palette.Bg,
            secondary = Palette.Walk, onSecondary = Palette.Bg,
            tertiary = Palette.Climb,
            background = Palette.Bg, onBackground = Palette.Text,
            surface = Palette.Surface, onSurface = Palette.Text,
            surfaceVariant = Palette.SurfaceHigh, onSurfaceVariant = Palette.TextDim,
            surfaceContainer = Palette.Surface, surfaceContainerHigh = Palette.SurfaceHigh,
            surfaceContainerLow = Palette.Surface, surfaceContainerHighest = Palette.SurfaceHigh,
            outline = Palette.Outline, outlineVariant = Palette.Outline,
            error = Palette.Heart,
        ),
        typography = AppTypography,
        content = content,
    )
}
