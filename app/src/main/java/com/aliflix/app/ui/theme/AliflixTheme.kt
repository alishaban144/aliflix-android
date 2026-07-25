package com.aliflix.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AliflixRed = Color(0xFFFF4057)
val AliflixBlack = Color(0xFF06070A)
val AliflixSurface = Color(0xFF111319)
val AliflixSurfaceRaised = Color(0xFF191C24)
val AliflixMuted = Color(0xFFA8ADB9)
val AliflixGreen = Color(0xFF55D98B)
val AliflixIce = Color(0xFFB9E6FF)

private val AliflixColors = darkColorScheme(
    primary = AliflixRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF55121E),
    onPrimaryContainer = Color(0xFFFFD9DE),
    background = AliflixBlack,
    onBackground = Color.White,
    surface = AliflixSurface,
    onSurface = Color.White,
    surfaceVariant = AliflixSurfaceRaised,
    onSurfaceVariant = AliflixMuted,
    secondary = AliflixIce,
    onSecondary = Color(0xFF07151D),
    outline = Color(0xFF363A45),
)

private val AliflixTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 44.sp,
        lineHeight = 45.sp,
        letterSpacing = (-1.4).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        lineHeight = 37.sp,
        letterSpacing = (-0.9).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 27.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
)

@Composable
fun AliflixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AliflixColors,
        typography = AliflixTypography,
        content = content,
    )
}
