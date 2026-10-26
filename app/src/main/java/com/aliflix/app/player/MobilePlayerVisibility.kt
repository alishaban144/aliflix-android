package com.aliflix.app.player

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import com.aliflix.app.ui.common.rememberMobileMotionEnabled

internal const val PLAYER_REVEAL_MILLIS = 180
internal const val PLAYER_DISMISS_MILLIS = 120
private val PlayerRevealEase = CubicBezierEasing(.16f,1f,.3f,1f)
private val LocalPlayerReveal = staticCompositionLocalOf<State<Float>?> { null }

/** One clock and no child delays: identity, actions, transport and timeline appear together. */
@Composable
internal fun MobilePlayerControlsVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val motion = rememberMobileMotionEnabled()
    val appear = if (motion) PLAYER_REVEAL_MILLIS else 0
    val disappear = if (motion) PLAYER_DISMISS_MILLIS else 0
    AnimatedVisibility(visible, modifier.testTag("player-controls"),
        enter = fadeIn(tween(appear,easing = PlayerRevealEase)),
        exit = fadeOut(tween(disappear))) {
        val phase = transition.animateFloat(transitionSpec = {
            tween(if (targetState == EnterExitState.Visible) appear else disappear,easing = PlayerRevealEase)
        },label = "player-appear") { if (it == EnterExitState.Visible) 1f else 0f }
        CompositionLocalProvider(LocalPlayerReveal provides phase) { content() }
    }
}

/** Serafim's source uses scale .9 -> 1, rotation 45 -> 0 and blur 2px -> 0.
 * Its 400ms reveal is shortened to the shared 180ms player visibility window. */
@Composable
internal fun Modifier.playerActionReveal(rotate: Boolean = true): Modifier {
    val phase = LocalPlayerReveal.current ?: return this
    if (com.aliflix.app.BuildConfig.IS_TV) return this
    return graphicsLayer {
        val p = phase.value
        scaleX = .9f + .1f * p; scaleY = scaleX
        rotationZ = if (rotate) 45f * (1f-p) else 0f
        val radius = 2f * (1f-p)
        renderEffect = if (Build.VERSION.SDK_INT >= 31 && radius > .01f) BlurEffect(radius,radius) else null
    }
}
