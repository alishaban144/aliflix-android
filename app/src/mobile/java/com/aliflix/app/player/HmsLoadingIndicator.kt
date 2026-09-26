package com.aliflix.app.player

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.*
import com.aliflix.app.ui.theme.AliflixAccentSecondary

/** HMS Loading by Nihal Erooth, Lottie Simple License. Vector geometry and timing
 * ported from the original two-layer animation; no bitmap frames or network runtime.
 * https://lottiefiles.com/free-animation/hms-loading-qkX0vJuZ0e
 */
@Composable internal fun HmsLoadingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "hms")
    val phase by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "orbit")
    Canvas(modifier.semantics {
        contentDescription = "Loading video"
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }) {
        val unit = size.minDimension / 520f
        translate(size.width / 2f, size.height / 2f) {
            scale(unit, unit, pivot = Offset.Zero) {
                rotate(phase * 360f, pivot = Offset.Zero) {
                    drawCircle(AliflixAccentSecondary, 50f, Offset(-100f, 0f))
                    drawCircle(AliflixAccentSecondary, 50f, Offset(100f, 0f))
                    // Trim 80..100 percent of the two oppositely wound circular paths.
                    val start = (0.8f + phase) % 1f
                    for (wrap in 0..1) {
                        val from = start - wrap
                        val to = from + 0.2f
                        for (circle in 0..1) {
                            val low = maxOf(from, circle * 0.5f)
                            val high = minOf(to, (circle + 1) * 0.5f)
                            if (high > low) {
                                val direction = if (circle == 0) -1f else 1f
                                val angle = (low - circle * 0.5f) * 720f
                                drawArc(AliflixAccentSecondary,
                                    startAngle = (if (circle == 0) 0f else 180f) + direction * angle,
                                    sweepAngle = direction * (high - low) * 720f,
                                    useCenter = false,
                                    topLeft = Offset(if (circle == 0) -200f else 0f, -100f),
                                    size = Size(200f, 200f), style = Stroke(52f, cap = StrokeCap.Round))
                            }
                        }
                    }
                }
            }
        }
    }
}
