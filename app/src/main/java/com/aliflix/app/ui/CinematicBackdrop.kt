package com.aliflix.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aliflix.app.ui.theme.AliflixBackgroundBase

/**
 * A single continuous artwork plane behind hero, actions, and the page below.
 *
 * The treatment is built from stacked layers so the artwork never ends on a visible line: an
 * oversized, defocused copy of the same artwork glows behind the sharp crop, a long bottom-up
 * scrim dissolves both into the page background, and soft edge falloffs keep the sides and the
 * status bar area calm. Every layer is drawn slightly darker than the reference treatment so
 * white titles and body copy keep their contrast on top of it.
 */
@Composable internal fun CinematicBackdrop(artwork: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(AliflixBackgroundBase)) {
        if (artwork.isNullOrBlank()) return@Box
        AmbientArtworkLayer(artwork, Modifier.matchParentSize())
        AsyncImage(
            artwork,
            null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = 0.97f }
                .drawWithContent {
                    drawContent()
                    // Long, evenly spaced stops are what remove the hard edge. A short ramp
                    // would read as a line where the artwork stops.
                    drawRect(
                        Brush.verticalGradient(
                            0f to AliflixBackgroundBase.copy(alpha = 0.34f),
                            0.16f to AliflixBackgroundBase.copy(alpha = 0.38f),
                            0.34f to AliflixBackgroundBase.copy(alpha = 0.52f),
                            0.52f to AliflixBackgroundBase.copy(alpha = 0.70f),
                            0.70f to AliflixBackgroundBase.copy(alpha = 0.86f),
                            0.86f to AliflixBackgroundBase.copy(alpha = 0.96f),
                            1f to AliflixBackgroundBase,
                        ),
                    )
                    // Status bar and gesture area stay legible without a hard top band.
                    drawRect(
                        Brush.verticalGradient(
                            0f to AliflixBackgroundBase.copy(alpha = 0.58f),
                            0.09f to AliflixBackgroundBase.copy(alpha = 0.22f),
                            0.2f to Color.Transparent,
                        ),
                    )
                    drawRect(
                        Brush.horizontalGradient(
                            0f to AliflixBackgroundBase.copy(alpha = 0.40f),
                            0.18f to Color.Transparent,
                            0.82f to Color.Transparent,
                            1f to AliflixBackgroundBase.copy(alpha = 0.40f),
                        ),
                    )
                    drawRect(
                        Brush.radialGradient(
                            0.45f to Color.Transparent,
                            0.78f to AliflixBackgroundBase.copy(alpha = 0.16f),
                            1f to AliflixBackgroundBase.copy(alpha = 0.42f),
                            center = Offset(0.5f, 0.34f),
                            radius = 1.28f,
                        ),
                    )
                },
        )
    }
}

/**
 * The defocused copy behind the sharp crop. `Modifier.blur` is a no-op before API 31, so those
 * devices keep the oversized, low-opacity copy, which still softens the plane without the blur.
 */
@Composable
private fun AmbientArtworkLayer(artwork: String, modifier: Modifier = Modifier) {
    val scaled = modifier.graphicsLayer {
        scaleX = 1.34f
        scaleY = 1.34f
        alpha = 0.40f
    }
    AsyncImage(
        artwork,
        null,
        contentScale = ContentScale.Crop,
        modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scaled.blur(56.dp, BlurredEdgeTreatment.Unbounded)
        } else {
            scaled
        },
    )
}
