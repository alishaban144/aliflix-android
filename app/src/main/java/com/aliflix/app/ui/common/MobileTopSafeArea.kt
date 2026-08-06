package com.aliflix.app.ui.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Provides the safe top spacing for mobile screens.
 * Includes the Android status bar inset + 16dp of visual breathing space.
 * Prevents titles and content from drawing under the status bar.
 */
@Composable
fun MobileTopSafeArea(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(16.dp)
    )
}
