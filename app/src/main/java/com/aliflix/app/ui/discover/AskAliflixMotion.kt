package com.aliflix.app.ui.discover

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

object AskAliflixMotion {
    const val DURATION_PRESS = 110
    const val DURATION_CHIP = 160
    const val DURATION_CONTENT_SMALL = 200
    const val DURATION_MODE_TRANSITION = 280
    const val DURATION_STATE_TRANSITION = 360

    fun <T> pressSpec() = tween<T>(DURATION_PRESS, easing = FastOutSlowInEasing)
    fun <T> chipSpec() = tween<T>(DURATION_CHIP, easing = FastOutSlowInEasing)
    fun <T> smallContentSpec() = tween<T>(DURATION_CONTENT_SMALL, easing = LinearOutSlowInEasing)
    fun <T> modeTransitionSpec() = tween<T>(DURATION_MODE_TRANSITION, easing = FastOutSlowInEasing)
    fun <T> stateTransitionSpec() = tween<T>(DURATION_STATE_TRANSITION, easing = FastOutSlowInEasing)

    fun horizontalModeTransition(isForward: Boolean) = if (isForward) {
        (slideInHorizontally(
            initialOffsetX = { (it * 0.25f).toInt() },
            animationSpec = modeTransitionSpec()
        ) + fadeIn(modeTransitionSpec())) togetherWith (
            slideOutHorizontally(
                targetOffsetX = { (-it * 0.2f).toInt() },
                animationSpec = modeTransitionSpec()
            ) + fadeOut(modeTransitionSpec())
        )
    } else {
        (slideInHorizontally(
            initialOffsetX = { (-it * 0.25f).toInt() },
            animationSpec = modeTransitionSpec()
        ) + fadeIn(modeTransitionSpec())) togetherWith (
            slideOutHorizontally(
                targetOffsetX = { (it * 0.2f).toInt() },
                animationSpec = modeTransitionSpec()
            ) + fadeOut(modeTransitionSpec())
        )
    }

    fun editorResultTransition(showingEditor: Boolean) = if (showingEditor) {
        (slideInHorizontally(
            initialOffsetX = { -it / 5 },
            animationSpec = stateTransitionSpec()
        ) + fadeIn(stateTransitionSpec()) + scaleIn(stateTransitionSpec(), initialScale = 0.985f)) togetherWith (
            slideOutHorizontally(
                targetOffsetX = { it / 7 },
                animationSpec = stateTransitionSpec()
            ) + fadeOut(stateTransitionSpec()) + scaleOut(stateTransitionSpec(), targetScale = 0.99f)
        )
    } else {
        (slideInVertically(
            initialOffsetY = { it / 12 },
            animationSpec = stateTransitionSpec()
        ) + fadeIn(stateTransitionSpec()) + scaleIn(stateTransitionSpec(), initialScale = 0.985f)) togetherWith (
            slideOutVertically(
                targetOffsetY = { -it / 16 },
                animationSpec = stateTransitionSpec()
            ) + fadeOut(stateTransitionSpec()) + scaleOut(stateTransitionSpec(), targetScale = 0.99f)
        )
    }
}
