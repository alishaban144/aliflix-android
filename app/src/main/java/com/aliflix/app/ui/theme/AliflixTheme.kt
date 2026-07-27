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
 * remaining restrained against the deep blue-black surfaces used by the app.
 */
val AliflixBackgroundBase = Color(0xFF05070D)
val AliflixBackgroundImmersive = Color(0xFF070A12)
val AliflixSurfacePrimary = Color(0xFF0D111A)
val AliflixSurfaceSecondary = Color(0xFF131824)
val AliflixSurfaceElevated = Color(0xFF1A2030)
val AliflixSurfacePressed = Color(0xFF22293A)
val AliflixSurfaceDisabled = Color(0xFF181C25)

val AliflixContentPrimary = Color(0xFFEDECF2)
val AliflixContentSecondary = Color(0xFFA6AAB8)
val AliflixContentTertiary = Color(0xFF777D8D)
val AliflixContentInverse = Color(0xFF0D1017)

val AliflixBorderSubtle = Color(0xFF252B39)
val AliflixBorderStrong = Color(0xFF3B4355)
val AliflixAccentPrimary = Color(0xFF6650B8)
val AliflixAccentPrimaryContainer = Color(0xFF211A39)
val AliflixAccentSecondary = Color(0xFF9A8BB8)
val AliflixEditorialWarm = Color(0xFFC28B53)

val AliflixSuccess = Color(0xFF52AD79)
val AliflixWarning = Color(0xFFC89755)
val AliflixError = Color(0xFFD25E6C)
val AliflixInfo = Color(0xFF5B9FC5)
val AliflixFocus = Color(0xFFA394C8)
val AliflixScrimLight = Color(0x70020409)
val AliflixScrimStrong = Color(0xE0020409)

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
    onPrimaryContainer = Color(0xFFD5CCE8),
    inversePrimary = Color(0xFF5B45A0),
    secondary = AliflixAccentSecondary,
    onSecondary = Color(0xFF17121F),
    secondaryContainer = Color(0xFF252033),
    onSecondaryContainer = Color(0xFFD6CDE4),
    tertiary = AliflixEditorialWarm,
    onTertiary = Color(0xFF241505),
    tertiaryContainer = Color(0xFF352719),
    onTertiaryContainer = Color(0xFFE6C7A7),
    background = AliflixBackgroundBase,
    onBackground = AliflixContentPrimary,
    surface = AliflixSurfacePrimary,
    onSurface = AliflixContentPrimary,
    surfaceVariant = AliflixSurfaceSecondary,
    onSurfaceVariant = AliflixContentSecondary,
    surfaceTint = AliflixAccentPrimary,
    inverseSurface = Color(0xFFD8D5DC),
    inverseOnSurface = Color(0xFF26242B),
    error = AliflixError,
    onError = Color(0xFF250007),
    errorContainer = Color(0xFF442229),
    onErrorContainer = Color(0xFFEBC6CA),
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
