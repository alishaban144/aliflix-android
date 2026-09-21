package com.aliflix.app.ui.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

/**
 * Provides the safe top spacing for mobile screens.
 * Explicitly resolves Android status bar and display cutout insets plus visual breathing space.
 * Prevents titles, headers, and controls from overlapping the notification bar,
 * maintaining consistent spacing across all devices.
 */
@Composable
fun MobileTopSafeArea(
    modifier: Modifier = Modifier,
    extraPadding: Dp = 16.dp,
) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val cutoutTop = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val topInset = max(statusBarTop, cutoutTop)
    Spacer(
        modifier = modifier.height(topInset + extraPadding)
    )
}
