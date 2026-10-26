package com.aliflix.app.ui.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.aliflix.app.ui.theme.AliflixAccentSecondary

/** MIT aryamitra06/silent-lion-21: three staggered bars, 1 s cycle, taller center. */
@Composable
fun AliflixSyncBars(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "audio-sync-bars")
    val phase by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "audio-sync-phase")
    Canvas(modifier) {
        repeat(3) { i ->
            val t = (phase - i * .25f + 1f) % 1f
            val pulse = when { t < .2f -> t / .2f; t < .4f -> 1f - (t - .2f) / .2f; else -> 0f }
            val height = size.height * (if (i == 1) .64f else .37f) * (1f + .5f * pulse)
            val width = size.width * .12f
            drawRoundRect(AliflixAccentSecondary.copy(alpha = .5f + .5f * pulse),
                Offset(size.width * (.15f + i * .29f), (size.height - height) / 2),
                Size(width, height), CornerRadius(width))
        }
    }
}
