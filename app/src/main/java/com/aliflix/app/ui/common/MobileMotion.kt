package com.aliflix.app.ui.common

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Decorative mobile motion stops offscreen and reacts to Android's animation preference. */
@Composable
internal fun rememberMobileMotionEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember(context) { mutableStateOf(android.animation.ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = android.animation.ValueAnimator.areAnimatorsEnabled() }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return enabled
}

@Composable
internal fun rememberMobileResumed(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumed
}

/** Accumulates active time only; resuming never advances a suspended animation by a large step. */
@Composable
internal fun rememberMobileAnimationTime(active: Boolean, infinite: Boolean = true): Float {
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active, infinite) {
        if (active) {
            var previous = 0L
            while (true) {
                val now = if (infinite) withInfiniteAnimationFrameNanos { it } else withFrameNanos { it }
                if (previous != 0L) {
                    val delta = (now-previous)/1_000_000_000f
                    seconds += if (infinite) delta.coerceAtMost(.05f) else delta
                }
                previous = now
            }
        }
    }
    return seconds
}
