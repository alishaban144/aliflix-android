package com.aliflix.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixBackgroundBase
import com.aliflix.app.ui.theme.AliflixBackgroundImmersive

fun Modifier.aliflixScreenBackground(): Modifier = background(
    Brush.verticalGradient(
        0f to AliflixBackgroundBase,
        0.42f to AliflixBackgroundImmersive,
        0.76f to AliflixBackgroundBase,
        1f to AliflixAccentPrimary.copy(alpha = 0.055f),
    ),
)
