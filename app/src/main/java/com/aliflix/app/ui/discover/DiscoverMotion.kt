package com.aliflix.app.ui.discover

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState

object DiscoverMotion {
    const val TouchFeedback = 120
    const val ChipSelection = 180
    const val ContentChange = 240
    const val ScreenNavigation = 300
    const val ListReorder = 260

    fun <T> fast() = tween<T>(durationMillis = TouchFeedback, easing = FastOutSlowInEasing)
    fun <T> standard() = tween<T>(durationMillis = ContentChange, easing = FastOutSlowInEasing)
    fun <T> navigation() = tween<T>(durationMillis = ScreenNavigation, easing = FastOutSlowInEasing)
}

@Composable
fun Modifier.aliflixPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.975f
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = DiscoverMotion.fast(),
        label = "aliflixPressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
