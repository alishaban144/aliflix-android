@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aliflix.app.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max

object AliflixInsets {
    val Top: WindowInsets @Composable get() = WindowInsets.statusBarsIgnoringVisibility
    val Bottom: WindowInsets @Composable get() = WindowInsets.navigationBars
    val Safe: WindowInsets @Composable get() = WindowInsets.safeDrawing
    val TopPadding: Dp @Composable get() = max(
        Top.asPaddingValues().calculateTopPadding(),
        WindowInsets.displayCutout.asPaddingValues().calculateTopPadding(),
    )
}
