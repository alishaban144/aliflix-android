package com.aliflix.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Aliflix "Cinematic Intelligence" palette.
 *
 * The primary accent deliberately supports white text at WCAG AA contrast while
 * staying vivid on the deep blue-black surfaces used throughout the app.
 */
val AliflixBackgroundBase = Color(0xFF070912)
val AliflixBackgroundImmersive = Color(0xFF090C17)
val AliflixSurfacePrimary = Color(0xFF111521)
val AliflixSurfaceSecondary = Color(0xFF181D2B)
val AliflixSurfaceElevated = Color(0xFF22283A)
val AliflixSurfacePressed = Color(0xFF2A3147)
val AliflixSurfaceDisabled = Color(0xFF202432)

val AliflixContentPrimary = Color(0xFFF6F4FA)
val AliflixContentSecondary = Color(0xFFBEC2D0)
val AliflixContentTertiary = Color(0xFF8D93A6)
val AliflixContentInverse = Color(0xFF11131B)

val AliflixBorderSubtle = Color(0xFF303649)
val AliflixBorderStrong = Color(0xFF505974)
val AliflixAccentPrimary = Color(0xFF7C5CE5)
val AliflixAccentPrimaryContainer = Color(0xFF2C2350)
val AliflixAccentSecondary = Color(0xFFB8A1FF)
val AliflixEditorialWarm = Color(0xFFFFB86B)

val AliflixSuccess = Color(0xFF69D69A)
val AliflixWarning = Color(0xFFFFBF69)
val AliflixError = Color(0xFFFF7183)
val AliflixInfo = Color(0xFF75C7FF)
val AliflixFocus = Color(0xFFD0C1FF)
val AliflixScrimLight = Color(0x6604070E)
val AliflixScrimStrong = Color(0xD904070E)

// Short semantic aliases for call sites where the role is already clear.
val AliflixAccent = AliflixAccentPrimary
val AliflixLilac = AliflixAccentSecondary
val AliflixWarm = AliflixEditorialWarm

/*
 * Legacy aliases remain unchanged for the TV UI and protected player surfaces.
 * Mobile UI call sites opt into the semantic tokens above.
 */
val AliflixRed = Color(0xFFFF4057)
val AliflixBlack = Color(0xFF06070A)
val AliflixSurface = Color(0xFF111319)
val AliflixSurfaceRaised = Color(0xFF191C24)
val AliflixMuted = Color(0xFFA8ADB9)
val AliflixGreen = Color(0xFF55D98B)
val AliflixIce = Color(0xFFB9E6FF)

object AliflixSpacing {
    val none = 0.dp
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 40.dp
    val section = 48.dp
}

object AliflixRadii {
    val none = 0.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 18.dp
    val extraLarge = 28.dp
    val full = 999.dp
}

object AliflixElevation {
    val none = 0.dp
    val low = 2.dp
    val medium = 6.dp
    val high = 12.dp
}

val AliflixShapes = Shapes(
    extraSmall = RoundedCornerShape(AliflixRadii.small),
    small = RoundedCornerShape(AliflixRadii.medium),
    medium = RoundedCornerShape(AliflixRadii.large),
    large = RoundedCornerShape(AliflixRadii.extraLarge),
    extraLarge = RoundedCornerShape(AliflixRadii.extraLarge),
)

private val AliflixMobileColors = darkColorScheme(
    primary = AliflixAccentPrimary,
    onPrimary = Color.White,
    primaryContainer = AliflixAccentPrimaryContainer,
    onPrimaryContainer = Color(0xFFE8DFFF),
    inversePrimary = Color(0xFF5E40C0),
    secondary = AliflixAccentSecondary,
    onSecondary = Color(0xFF1B1233),
    secondaryContainer = Color(0xFF30284A),
    onSecondaryContainer = Color(0xFFE8DFFF),
    tertiary = AliflixEditorialWarm,
    onTertiary = Color(0xFF2C1700),
    tertiaryContainer = Color(0xFF4A301B),
    onTertiaryContainer = Color(0xFFFFDDBB),
    background = AliflixBackgroundBase,
    onBackground = AliflixContentPrimary,
    surface = AliflixSurfacePrimary,
    onSurface = AliflixContentPrimary,
    surfaceVariant = AliflixSurfaceSecondary,
    onSurfaceVariant = AliflixContentSecondary,
    surfaceTint = AliflixAccentPrimary,
    inverseSurface = Color(0xFFE7E2EC),
    inverseOnSurface = Color(0xFF292630),
    error = AliflixError,
    onError = Color(0xFF3A000D),
    errorContainer = Color(0xFF5A202B),
    onErrorContainer = Color(0xFFFFD9DE),
    outline = AliflixBorderStrong,
    outlineVariant = AliflixBorderSubtle,
    scrim = Color.Black,
)

private val AliflixMobileTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1.1).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.8).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 37.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.45).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.05.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.25.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.35.sp,
    ),
)

private val AliflixLegacyColors = darkColorScheme(
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

private val AliflixLegacyTypography = Typography(
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
        colorScheme = AliflixLegacyColors,
        typography = AliflixLegacyTypography,
        content = content,
    )
}

@Composable
fun AliflixMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AliflixMobileColors,
        typography = AliflixMobileTypography,
        shapes = AliflixShapes,
        content = content,
    )
}
