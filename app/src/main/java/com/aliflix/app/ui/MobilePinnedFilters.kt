package com.aliflix.app.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.roundToInt
import com.aliflix.app.ui.theme.AliflixBackgroundBase

internal fun homeFilterY(naturalTop: Float, headerBottom: Float): Float = max(naturalTop, headerBottom)
internal fun homeFilterOpacity(naturalTop: Float, headerBottom: Float, barHeight: Float): Float {
    val p = (1f - (naturalTop-headerBottom) / barHeight.coerceAtLeast(1f)).coerceIn(0f,1f)
    return p*p*(3f-2f*p)
}

/** The search header's measured height is available before the feed's first placement. */
@Composable
internal fun MobileHomeSearchFrame(
    showHeader: Boolean,
    modifier: Modifier,
    header: @Composable () -> Unit,
    content: @Composable (headerBottomPx: Float) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val headerPlaceable = if (showHeader) subcompose("search", header).single()
            .measure(constraints.copy(minHeight = 0)) else null
        val contentPlaceable = subcompose("content") { content(headerPlaceable?.height?.toFloat() ?: 0f) }
            .single().measure(constraints)
        layout(constraints.maxWidth,constraints.maxHeight) {
            contentPlaceable.placeRelative(0,0)
            headerPlaceable?.placeRelative(0,0,zIndex = 2f)
        }
    }
}

/** Measures one bar once, reserves that exact height, and places the same bar from scroll geometry. */
@Composable
internal fun MobilePinnedFilterLayout(
    state: LazyListState,
    headerBottom: () -> Float,
    bar: @Composable (opacity: () -> Float) -> Unit,
    feed: @Composable (barHeight: Dp) -> Unit,
    modifier: Modifier = Modifier,
    showChrome: Boolean = true,
) {
    SubcomposeLayout(modifier) { constraints ->
        fun naturalY(): Float {
            val info = state.layoutInfo
            return info.visibleItemsInfo.firstOrNull { it.index == 1 }?.offset?.toFloat()
                ?: info.visibleItemsInfo.firstOrNull { it.index == 0 }?.let { (it.offset+it.size).toFloat() }
                ?: if (state.firstVisibleItemIndex > 0) Float.NEGATIVE_INFINITY else constraints.maxHeight.toFloat()
        }
        var barHeightPx = 1f
        val barPlaceable = subcompose("filters") {
            Box(Modifier.scrollable(state, Orientation.Vertical, reverseDirection = true)) {
                bar { homeFilterOpacity(naturalY(), headerBottom(), barHeightPx) }
            }
        }.single().measure(constraints.copy(minHeight = 0))
        barHeightPx = barPlaceable.height.toFloat()
        val listPlaceable = subcompose("feed") { feed(barPlaceable.height.toDp()) }.single().measure(constraints)
        val headerHeight = headerBottom().roundToInt().coerceIn(0, constraints.maxHeight)
        val headerScrim = subcompose("header-scrim") {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(AliflixBackgroundBase.copy(alpha = if (showChrome)
                    homeFilterOpacity(naturalY(), headerBottom(), barHeightPx) else 0f))
            }
        }.single().measure(constraints.copy(minHeight = headerHeight, maxHeight = headerHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            listPlaceable.placeRelative(0,0)
            headerScrim.placeRelative(0,0,zIndex = .5f)
            barPlaceable.placeRelative(0, homeFilterY(naturalY(),headerBottom()).roundToInt(), zIndex = 1f)
        }
    }
}
