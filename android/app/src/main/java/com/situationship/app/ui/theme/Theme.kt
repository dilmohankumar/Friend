package com.situationship.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Brand = Color(0xFFFF4D6D)
val Brand2 = Color(0xFFFF8A5C)
val Online = Color(0xFF22C55E)
val Verified = Color(0xFF3B82F6)
val BrandGradient = Brush.linearGradient(listOf(Brand, Brand2))

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Brand2,
    background = Color(0xFFFFF7F5),
    onBackground = Color(0xFF1F1720),
    surface = Color.White,
    onSurface = Color(0xFF1F1720),
    surfaceVariant = Color(0xFFFFF0ED),
    onSurfaceVariant = Color(0xFF7A6A72),
    outline = Color(0xFFF1E1DE),
    error = Color(0xFFE11D48),
)

private val Dark = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Brand2,
    background = Color(0xFF140E12),
    onBackground = Color(0xFFF7EEF1),
    surface = Color(0xFF1E161B),
    onSurface = Color(0xFFF7EEF1),
    surfaceVariant = Color(0xFF2A1F25),
    onSurfaceVariant = Color(0xFFB19CA6),
    outline = Color(0xFF33262D),
    error = Color(0xFFF43F5E),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
)

@Composable
fun SituationshipTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}
