package com.aliflix.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
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
 * @param bottom the hero's lower edge, where the artwork must already be fully transparent.
 */
internal data class PosterBand(val top: Dp, val bottom: Dp) {
    val height: Dp get() = bottom - top
}

/**
 * Resolves where the poster sits inside a plane [planeHeight] tall.
 *
 * Both edges are clamped to the plane so a caller passing a hero measured inside an inset, or a
 * hero taller than the plane, still yields a band that is inside the plane and never inverted.
 * This keeps the artwork framed by the hero instead of cropping it to the entire screen.
 */
internal fun posterBand(planeHeight: Dp, posterTopInset: Dp, posterHeight: Dp): PosterBand {
    if (planeHeight <= 0.dp || planeHeight == Dp.Infinity) return PosterBand(0.dp, 0.dp)
    val top = posterTopInset.coerceIn(0.dp, planeHeight)
    val bottom = (top + posterHeight).coerceIn(top, planeHeight)
    return PosterBand(top = top, bottom = bottom)
}

/**
 * A stationary backdrop for a screen whose artwork must not move: it fills the plane it is given,
 * which is a sibling of the scrolling content rather than part of it. The sharp artwork fades into
 * the ambient plane BEFORE its lower edge, like a CSS alpha mask. A gradient starting below the
 * image cannot hide the discontinuity between two different crops. The status-bar inset contributes
 * to the frame height; artwork extends behind the status bar so it has no exposed top edge either.
 * No scroll offsets or gesture modifiers move this plane.
 *
 * Home and the movie/series details hero use [CinematicPosterPlane] instead, so their poster can
 * scroll away with the hero; this remains for a plane that has to stay put under scrolling content.
 */
@Composable
internal fun CinematicBackdrop(
    artwork: String?,
    modifier: Modifier = Modifier,
    posterTopInset: Dp = 0.dp,
    posterHeight: Dp = 0.dp,
) {
    Box(modifier.clipToBounds().background(AliflixBackgroundBase)) {
        if (artwork.isNullOrBlank()) return@Box
        BoxWithConstraints(Modifier.matchParentSize()) {
            CinematicPlane(artwork, posterBand(maxHeight, posterTopInset, posterHeight))
        }
    }
}

/**
 * The same three layers as [CinematicBackdrop], but sized by the caller's box instead of a
 * stationary full-screen plane, so a screen can host the artwork inside its scrolling content.
 *
 * Home draws this in the hero item rather than behind the list. The poster then occupies the hero
 * alone and travels with it, so scrolling down moves the artwork off the top and leaves the plain
 * Aliflix background underneath, instead of a second, unmoving copy of the same picture sitting
 * behind the rails. Nothing is drawn past the hero because the plane is bounded and clipped to it.
 *
 * @param planeAlpha crossfades two heroes while the pager is between pages; below 1 it is layered.
 */
@Composable
internal fun CinematicPosterPlane(
    artwork: String?,
    modifier: Modifier = Modifier,
    planeAlpha: Float = 1f,
) {
    Box(
        modifier
            .clipToBounds()
            .then(
                if (planeAlpha < 1f) Modifier.graphicsLayer { alpha = planeAlpha } else Modifier,
            ),
    ) {
        if (artwork.isNullOrBlank()) return@Box
        BoxWithConstraints(Modifier.matchParentSize()) {
            // The hero box is the whole plane, so the poster spans it and cannot reach past it.
            // posterBand still clamps, so an unbounded or collapsed host draws no artwork.
            CinematicPlane(artwork, posterBand(maxHeight, 0.dp, maxHeight))
        }
    }
}

/**
 * The layers of one plane, drawn into the receiving [Box]: the defocused copy, the fixed tint and
 * the sharp crop. [band] locates the sharp crop, which is anchored to the box's top edge and runs
 * down to [PosterBand.bottom], so a status bar inset moves only where the artwork ends.
 */
@Composable
private fun BoxScope.CinematicPlane(artwork: String, band: PosterBand) {
    AmbientArtworkLayer(artwork, Modifier.matchParentSize())
    // One continuous, fixed tint keeps the ambient plane readable behind the whole page.
    Box(
        Modifier.matchParentSize().background(
            Brush.verticalGradient(
                0f to AliflixBackgroundBase.copy(alpha = 0.32f),
                0.60f to AliflixBackgroundBase.copy(alpha = 0.64f),
                1f to AliflixBackgroundBase,
            ),
        ),
    )

    if (band.height <= 0.dp) return
    AsyncImage(
        model = artwork,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(band.bottom)
            .cinematicArtworkFade(),
    )
}

/** Isolate DstIn so the mask removes only the artwork, never the ambient layer or page content. */
internal fun Modifier.cinematicArtworkFade(): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithCache {
    val mask = Brush.verticalGradient(
        0f to Color.White,
        0.40f to Color.White,
        0.60f to Color.White.copy(alpha = 0.74f),
        0.78f to Color.White.copy(alpha = 0.32f),
        0.92f to Color.White.copy(alpha = 0.06f),
        0.98f to Color.Transparent,
        1f to Color.Transparent,
    )
    // The palette scrim is strongest beneath the title, while the upper artwork stays clear.
    val readability = Brush.horizontalGradient(
        0f to AliflixBackgroundBase.copy(alpha = 0.16f),
        0.60f to Color.Transparent,
        1f to Color.Transparent,
    )
    onDrawWithContent {
        drawContent()
        drawRect(readability)
        drawRect(mask, blendMode = BlendMode.DstIn)
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
        alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.32f else 0.10f
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
