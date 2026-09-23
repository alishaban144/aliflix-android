package com.aliflix.app.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

@Composable
internal fun rememberLibraryFlingBehavior(state: LazyGridState): FlingBehavior {
    val density = LocalDensity.current
    return remember(state, density) {
        LibraryFlingBehavior(with(density) { 2000.dp.toPx() }, with(density) { 120.dp.toPx() }) { forward ->
            val layout = state.layoutInfo
            val visible = layout.visibleItemsInfo
            when {
                forward && !state.canScrollForward -> 0f
                !forward && !state.canScrollBackward -> 0f
                forward && visible.any { it.index == layout.totalItemsCount - 1 } ->
                    (visible.maxOf { it.offset.y + it.size.height } + layout.afterContentPadding - layout.viewportEndOffset).toFloat().coerceAtLeast(0f)
                !forward && visible.any { it.index == 0 } ->
                    (-visible.first { it.index == 0 }.offset.y).toFloat().coerceAtLeast(0f)
                else -> Float.POSITIVE_INFINITY
            }
        }
    }
}

@Composable
internal fun rememberLibraryFlingBehavior(state: LazyListState): FlingBehavior {
    val density = LocalDensity.current
    return remember(state, density) {
        LibraryFlingBehavior(with(density) { 2000.dp.toPx() }, with(density) { 120.dp.toPx() }) { forward ->
            val layout = state.layoutInfo
            val visible = layout.visibleItemsInfo
            when {
                forward && !state.canScrollForward -> 0f
                !forward && !state.canScrollBackward -> 0f
                forward && visible.any { it.index == layout.totalItemsCount - 1 } ->
                    (visible.maxOf { it.offset + it.size } + layout.afterContentPadding - layout.viewportEndOffset).toFloat().coerceAtLeast(0f)
                !forward && visible.any { it.index == 0 } ->
                    (-visible.first { it.index == 0 }.offset).toFloat().coerceAtLeast(0f)
                else -> Float.POSITIVE_INFINITY
            }
        }
    }
}

/** Touch dragging stays one-to-one. Only released momentum is limited and braked. */
private class LibraryFlingBehavior(
    private val maximumVelocity: Float,
    private val brakingDistance: Float,
    private val distanceToEdge: (forward: Boolean) -> Float,
) : FlingBehavior {
    private val decay = exponentialDecay<Float>(frictionMultiplier = 2f)

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (!initialVelocity.isFinite() || abs(initialVelocity) < 1f) return 0f
        val forward = initialVelocity > 0f
        var previousValue = 0f
        var edgeFactor = 1f
        AnimationState(initialValue = 0f, initialVelocity = initialVelocity.coerceIn(-maximumVelocity, maximumVelocity))
            .animateDecay(decay) {
                val remaining = distanceToEdge(forward)
                // Read actual layout each frame: downloads can resize and items can be removed.
                // Never increase momentum after braking has started.
                edgeFactor = min(edgeFactor, (remaining / brakingDistance).coerceIn(0f, 1f))
                val delta = (value - previousValue) * edgeFactor
                previousValue = value
                val consumed = scrollBy(delta)
                if (remaining <= 0.5f || abs(delta - consumed) > 0.5f) cancelAnimation()
            }
        // Do not hand a fast residual fling to the pager after reaching a vertical edge.
        return 0f
    }
}
