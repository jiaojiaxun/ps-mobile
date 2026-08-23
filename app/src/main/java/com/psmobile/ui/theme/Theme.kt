package com.psmobile.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Photoshop's own palette: near-neutral greys so that artwork colour is judged against nothing,
 * with the familiar blue reserved for selection and focus.
 */
object PsColors {
    val Accent = Color(0xFF31A8FF)
    val AccentDim = Color(0xFF1E7FCB)
    val AccentGlow = Color(0xFF8FD3FF)

    val Ink900 = Color(0xFF0E0E11)
    val Ink800 = Color(0xFF16161A)
    val Ink700 = Color(0xFF1B1B1F)
    val Ink600 = Color(0xFF232329)
    val Ink500 = Color(0xFF2C2C33)
    val Ink400 = Color(0xFF3A3A43)
    val Ink300 = Color(0xFF53535E)

    val TextPrimary = Color(0xFFECECEF)
    val TextSecondary = Color(0xFFA0A0AC)
    val TextTertiary = Color(0xFF6E6E7A)

    val Danger = Color(0xFFFF6B6B)
    val Success = Color(0xFF5FD68A)

    /** The two greys of the transparency checkerboard. */
    val CheckerLight = Color(0xFF3C3C42)
    val CheckerDark = Color(0xFF2E2E34)

    /** Colour labels Photoshop can attach to a layer. */
    val LayerLabels = listOf(
        Color.Transparent,
        Color(0xFFD0483C), // red
        Color(0xFFE08A2E), // orange
        Color(0xFFD8C33A), // yellow
        Color(0xFF56A14B), // green
        Color(0xFF3B7BD4), // blue
        Color(0xFF8E5CC4), // violet
        Color(0xFF7A7A85), // gray
    )
}

private val DarkScheme = darkColorScheme(
    primary = PsColors.Accent,
    onPrimary = Color(0xFF04121F),
    primaryContainer = PsColors.AccentDim,
    onPrimaryContainer = Color(0xFFE7F3FF),
    secondary = PsColors.AccentGlow,
    background = PsColors.Ink800,
    onBackground = PsColors.TextPrimary,
    surface = PsColors.Ink700,
    onSurface = PsColors.TextPrimary,
    surfaceVariant = PsColors.Ink600,
    onSurfaceVariant = PsColors.TextSecondary,
    surfaceContainer = PsColors.Ink600,
    surfaceContainerHigh = PsColors.Ink500,
    surfaceContainerHighest = PsColors.Ink400,
    outline = PsColors.Ink400,
    outlineVariant = PsColors.Ink500,
    error = PsColors.Danger,
    onError = Color(0xFF2A0A0A),
)

private val PsTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun PsMobileTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    // Editing surfaces stay dark: judging artwork colour against light chrome is misleading.
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = PsTypography,
        content = content,
    )
}
