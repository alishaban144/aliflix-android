package com.aliflix.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Material's enter-always behavior owns drag consumption, fling decay and settling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberLibraryChrome(
    page: Int,
    onCollapseFractionChange: (Float) -> Unit,
): TopAppBarScrollBehavior {
    val state = rememberTopAppBarState(initialHeightOffsetLimit = 0f)
    val behavior = TopAppBarDefaults.enterAlwaysScrollBehavior(state)
    val latestCallback by rememberUpdatedState(onCollapseFractionChange)
    val scope = rememberCoroutineScope()
    val collapsed by remember { derivedStateOf { state.heightOffset < -0.5f } }
    suspend fun expand() {
        if (state.heightOffset == 0f) return
        animate(state.heightOffset, 0f, animationSpec = tween(220)) { value, _ ->
            state.heightOffset = value
        }
        state.contentOffset = 0f
    }
    LaunchedEffect(page) { expand() }
    LaunchedEffect(state) {
        snapshotFlow { state.collapsedFraction }.distinctUntilChanged().collect { latestCallback(it) }
    }
    DisposableEffect(Unit) { onDispose { latestCallback(0f) } }
    BackHandler(collapsed) { scope.launch { expand() } }
    return behavior
}

/** Measure full-height chrome once per layout, then reveal only its visible portion.
 * Reading the offset in layout avoids recomposing the item grids on every scroll pixel.
 */
@Composable
internal fun LibraryChrome(
    fraction: () -> Float,
    slideUp: Boolean = true,
    onHeightChanged: (Int) -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box(Modifier.clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        onHeightChanged(placeable.height)
        val hidden = (placeable.height * fraction().coerceIn(0f, 1f)).roundToInt()
        layout(placeable.width, placeable.height - hidden) {
            placeable.placeRelative(0, if (slideUp) -hidden else 0)
        }
    }) { content() }
}
