package com.aliflix.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aliflix.app.ui.theme.AliflixBackgroundBase

/**
 * A single continuous artwork plane behind hero, actions, and the page below.
 *
 * The artwork is drawn in its original colours: nothing is tinted, dimmed or vignetted over the
 * poster itself. The defocused copy behind it only softens the plane, and the darker treatment is
 * confined to the content that sits below the poster, where it is needed for white copy to read.
 * That keeps the scrim start aligned with the bottom of the artwork instead of washing over it.
 *
 * @param posterHeight how much of the plane the poster occupies. The scrim begins below it.
 */
@Composable
internal fun CinematicBackdrop(
    artwork: String?,
    modifier: Modifier = Modifier,
    posterHeight: Dp = 0.dp,
) {
    Box(modifier.background(AliflixBackgroundBase)) {
        if (artwork.isNullOrBlank()) return@Box
        AmbientArtworkLayer(artwork, Modifier.matchParentSize())
        BoxWithConstraints(Modifier.matchParentSize()) {
            val planeHeight = maxHeight
            val contentTop = if (planeHeight > 0.dp) {
                (posterHeight.coerceIn(0.dp, planeHeight) / planeHeight).coerceIn(0f, 1f)
            } else {
                1f
            }
            AsyncImage(
                artwork,
                null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .drawWithContent {
                        drawContent()
                        if (contentTop >= 1f) return@drawWithContent
                        val top = size.height * contentTop
                        // A long, evenly spaced ramp dissolves the artwork into the page background
                        // instead of ending on a visible line.
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.45f to AliflixBackgroundBase.copy(alpha = 0.55f),
                                0.75f to AliflixBackgroundBase.copy(alpha = 0.88f),
                                1f to AliflixBackgroundBase,
                                startY = top,
                                endY = size.height,
                            ),
                            topLeft = Offset(0f, top),
                            size = Size(size.width, size.height - top),
                        )
                    },
            )
        }
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
