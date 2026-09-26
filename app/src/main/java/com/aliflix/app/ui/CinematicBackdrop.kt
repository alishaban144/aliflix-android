package com.aliflix.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aliflix.app.ui.theme.AliflixBackgroundBase

/**
 * The slice of the backdrop plane that the poster occupies, measured from the top of that plane.
 *
 * @param top where the band starts.
 * @param bottom the poster's lower edge. The dark content ramp starts here, never above it.
 */
internal data class PosterBand(val top: Dp, val bottom: Dp) {
    val height: Dp get() = bottom - top
}

/**
 * Resolves where the poster sits inside a plane [planeHeight] tall.
 *
 * Both edges are clamped to the plane so a caller passing a hero measured inside an inset, or a
 * hero taller than the plane, still yields a band that is inside the plane and never inverted.
 * This is what keeps the artwork from being cropped to the whole screen, and what keeps the
 * content ramp from starting above the poster.
 */
internal fun posterBand(planeHeight: Dp, posterTopInset: Dp, posterHeight: Dp): PosterBand {
    if (planeHeight <= 0.dp || planeHeight == Dp.Infinity) return PosterBand(0.dp, 0.dp)
    val top = posterTopInset.coerceIn(0.dp, planeHeight)
    val bottom = (top + posterHeight).coerceIn(top, planeHeight)
    return PosterBand(top = top, bottom = bottom)
}

/**
 * A single continuous artwork plane behind hero, actions, and the page below.
 *
 * The poster is framed by its own band rather than by the whole screen. A 16:9 backdrop cropped to
 * a full-height phone is reduced to a narrow vertical slice, which is what made the poster look
 * broken; cropping it to the hero instead keeps the composition the artwork was framed for.
 *
 * Nothing is drawn over the band itself, so the poster keeps its original colours. The dark
 * translucent ramp begins at the band's lower edge and covers only the content below it, which is
 * the only place white copy has to be read against the artwork. The defocused copy behind softens
 * the plane and shows through that ramp as a glow rather than as a second, competing picture.
 *
 * @param posterTopInset where the band starts, measured from the top of the plane. A screen whose
 *   hero is pushed down by the status bar passes that inset so the band still lines up with the
 *   hero it belongs to.
 * @param posterHeight how tall the band is, which is also where the content ramp begins.
 */
@Composable
internal fun CinematicBackdrop(
    artwork: String?,
    modifier: Modifier = Modifier,
    posterTopInset: Dp = 0.dp,
    posterHeight: Dp = 0.dp,
) {
    Box(modifier.background(AliflixBackgroundBase)) {
        if (artwork.isNullOrBlank()) return@Box
        BoxWithConstraints(Modifier.matchParentSize()) {
            val planeHeight = maxHeight
            val band = posterBand(planeHeight, posterTopInset, posterHeight)

            AmbientArtworkLayer(artwork, Modifier.matchParentSize())

            if (band.height > 0.dp) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = band.top)
                        .fillMaxWidth()
                        .height(band.height)
                        .clipToBounds(),
                ) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }

            val rampHeight = planeHeight - band.bottom
            if (rampHeight > 0.dp) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = band.bottom)
                        .fillMaxWidth()
                        .height(rampHeight)
                        .background(
                            // Starts clear so the poster above keeps its colours, and runs long
                            // enough that the content below fades into the page instead of ending
                            // on a visible line.
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.34f to AliflixBackgroundBase.copy(alpha = 0.58f),
                                0.68f to AliflixBackgroundBase.copy(alpha = 0.88f),
                                1f to AliflixBackgroundBase,
                            ),
                        ),
                )
            }
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
