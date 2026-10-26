@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aliflix.app.ui.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * Provides the safe top spacing for mobile screens.
 * Explicitly resolves Android status bar and display cutout insets plus visual breathing space.
 * Prevents titles, headers, and controls from overlapping the notification bar,
 * maintaining consistent spacing across all devices.
 */
@Composable
fun MobileTopSafeArea(
    modifier: Modifier = Modifier,
    extraPadding: Dp = AliflixSpacing.Content,
) {
    val topInset = AliflixInsets.TopPadding
    Spacer(
        modifier = modifier.height(topInset + extraPadding)
    )
}
