package com.aliflix.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.aliflix.app.ui.theme.*

/** Mobile-only visual roles. No layout, scroll state, blur or navigation ownership. */
enum class AliflixSurfaceLevel { Ambient, Content, Chrome, Elevated, Selected, Critical }

object AliflixAlpha {
    const val Content = 0.62f
    const val Chrome = 0.78f
    const val Elevated = 0.86f
    const val Selected = 0.16f
    const val Critical = 0.14f
    const val Highlight = 0.035f
    const val Focus = 0.20f
    const val Reviews = 0.65f
    const val Episodes = 0.64f
    const val Ratings = 0.50f
}

object AliflixSpacing {
    val Tiny = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Content = 16.dp
    val Panel = 20.dp
    val Large = 24.dp
    val TouchTarget = 48.dp
}

object AliflixCorners {
    val Small = RoundedCornerShape(12.dp)
    val Card = RoundedCornerShape(16.dp)
    val Panel = RoundedCornerShape(20.dp)
    val Chrome = RoundedCornerShape(24.dp)
}

object AliflixAtmosphere {
    val Start = Color(0xFF191629)
    val Middle = Color(0xFF0D111C)
    val End = Color(0xFF11131E)
    val Glow = AliflixAccentPrimary.copy(alpha = 0.133f)
    val Arc = Color(0xFFB1A2EE)
    const val ArcAlpha = 0.055f
    const val ArcStep = 0.01f
}

object AliflixElevation { val None = 0.dp }

object AliflixMotion {
    const val Press = 120
    const val Selection = 180
    const val Content = 240
    const val Navigation = 300
    const val Expressive = 360
    fun <T> press() = tween<T>(Press, easing = FastOutSlowInEasing)
    fun <T> selection() = tween<T>(Selection, easing = FastOutSlowInEasing)
    fun <T> content() = tween<T>(Content, easing = FastOutSlowInEasing)
}

object AliflixSurfaceDefaults {
    fun color(level: AliflixSurfaceLevel = AliflixSurfaceLevel.Content, alpha: Float? = null): Color = when (level) {
        AliflixSurfaceLevel.Ambient -> Color.Transparent
        AliflixSurfaceLevel.Content -> AliflixSurfaceSecondary.copy(alpha = alpha ?: AliflixAlpha.Content)
        AliflixSurfaceLevel.Chrome -> AliflixSurfacePrimary.copy(alpha = alpha ?: AliflixAlpha.Chrome)
        AliflixSurfaceLevel.Elevated -> AliflixSurfaceSecondary.copy(alpha = alpha ?: AliflixAlpha.Elevated)
        AliflixSurfaceLevel.Selected -> AliflixAccentPrimary.copy(alpha = AliflixAlpha.Selected)
            .compositeOver(color(AliflixSurfaceLevel.Content, alpha))
        AliflixSurfaceLevel.Critical -> AliflixError.copy(alpha = AliflixAlpha.Critical)
            .compositeOver(color(AliflixSurfaceLevel.Content, alpha))
    }
    val Focus = AliflixAccentSecondary.copy(alpha = AliflixAlpha.Focus)

    @Composable
    fun textFieldColors() = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = color(),
        unfocusedContainerColor = color(),
        disabledContainerColor = color(),
        focusedBorderColor = Focus,
        unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        errorBorderColor = AliflixError.copy(alpha = AliflixAlpha.Focus),
        errorContainerColor = color(AliflixSurfaceLevel.Critical),
        cursorColor = AliflixAccentSecondary,
    )
}

/** Draw inside the clipped surface, underneath content; never brighten poster pixels. */
private fun Modifier.ambientHighlight(enabled: Boolean = true): Modifier = if (!enabled) this else drawBehind {
    drawRect(Brush.verticalGradient(
        listOf(AliflixAccentSecondary.copy(alpha = AliflixAlpha.Highlight), Color.Transparent),
        endY = minOf(size.height, 48.dp.toPx()).coerceAtLeast(1f),
    ))
}

fun Modifier.aliflixSurface(
    level: AliflixSurfaceLevel = AliflixSurfaceLevel.Content,
    shape: Shape = AliflixCorners.Card,
    alpha: Float? = null,
): Modifier = clip(shape).background(AliflixSurfaceDefaults.color(level, alpha))
    .ambientHighlight(level != AliflixSurfaceLevel.Ambient)

@Composable
fun Modifier.aliflixSelection(selected: Boolean, shape: Shape = AliflixCorners.Card): Modifier {
    val color by animateColorAsState(
        if (selected) AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Selected) else Color.Transparent,
        AliflixMotion.selection(), label = "surface-selection",
    )
    return clip(shape).background(color).ambientHighlight(selected)
}

@Composable
fun AliflixSurface(
    modifier: Modifier = Modifier,
    shape: Shape = AliflixCorners.Card,
    level: AliflixSurfaceLevel = AliflixSurfaceLevel.Content,
    alpha: Float? = null,
    contentColor: Color = LocalContentColor.current,
    content: @Composable () -> Unit,
) {
    val color by animateColorAsState(AliflixSurfaceDefaults.color(level, alpha),
        AliflixMotion.selection(), label = "surface-color")
    Surface(modifier, shape, color,
        contentColor, tonalElevation = AliflixElevation.None, shadowElevation = AliflixElevation.None) {
        Box(Modifier.ambientHighlight(level != AliflixSurfaceLevel.Ambient), propagateMinConstraints = true) { content() }
    }
}

@Composable
fun AliflixSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = AliflixCorners.Card,
    level: AliflixSurfaceLevel = AliflixSurfaceLevel.Content,
    alpha: Float? = null,
    contentColor: Color = LocalContentColor.current,
    content: @Composable () -> Unit,
) {
    val color by animateColorAsState(AliflixSurfaceDefaults.color(level, alpha),
        AliflixMotion.selection(), label = "action-surface-color")
    Surface(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
        color = color, contentColor = contentColor,
        tonalElevation = AliflixElevation.None, shadowElevation = AliflixElevation.None) {
        Box(Modifier.ambientHighlight(level != AliflixSurfaceLevel.Ambient), propagateMinConstraints = true) { content() }
    }
}

@Composable
fun AliflixIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    level: AliflixSurfaceLevel = AliflixSurfaceLevel.Ambient,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, AliflixMotion.press(), label = "icon-press")
    IconButton(onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .aliflixSurface(level, AliflixCorners.Small), enabled, interactionSource = source, content = content)
}

/** Styling wrapper only: callers retain their selectable/toggleable semantics and padding. */
@Composable
fun AliflixChip(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val color by animateColorAsState(
        AliflixSurfaceDefaults.color(if (selected) AliflixSurfaceLevel.Selected else AliflixSurfaceLevel.Content),
        AliflixMotion.selection(), label = "chip-selection",
    )
    Box(modifier.clip(AliflixCorners.Card).background(color).ambientHighlight().padding(contentPadding),
        contentAlignment = contentAlignment, content = content)
}

@Composable
fun AliflixPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    AliflixSurface(modifier, shape = AliflixCorners.Card, content = content)

@Composable
fun AliflixSegmentedControl(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(AliflixSpacing.Tiny),
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    contentPadding: PaddingValues = PaddingValues(AliflixSpacing.Tiny),
    content: @Composable RowScope.() -> Unit,
) = Row(modifier.selectableGroup().aliflixSurface().padding(contentPadding), horizontalArrangement, verticalAlignment, content)

@Composable
fun AliflixMediaCard(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) = Box(modifier.clip(AliflixCorners.Card).background(AliflixSurfaceDefaults.color()),
    contentAlignment = contentAlignment, content = content)

/** Keeps the caller's Dialog/ModalBottomSheet dismissal, insets and sizing contracts. */
@Composable
fun AliflixSheet(
    modifier: Modifier = Modifier,
    contentColor: Color = LocalContentColor.current,
    content: @Composable () -> Unit,
) = AliflixSurface(modifier, AliflixCorners.Chrome, AliflixSurfaceLevel.Elevated,
    contentColor = contentColor, content = content)

@Composable
fun AliflixBottomChrome(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    AliflixSurface(modifier, AliflixCorners.Chrome, AliflixSurfaceLevel.Chrome, content = content)
