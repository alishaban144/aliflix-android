package com.aliflix.app.ui.discover

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.aliflix.app.ui.common.AliflixLogoMark
import com.aliflix.app.ui.theme.*
import kotlin.math.cos

/** MIT Smit-Prajapati/tricky-turtle-16: five nested discs, staggered 2 s ripples.
 * The original UI glyph and underline are replaced by Aliflix's canonical mark.
 */
@Composable
fun AliflixRippleLoader(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "aliflix-ripple")
    val phase by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "ripple-phase")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val diameter = minOf(size.width, size.height) / 1.35f
            for (i in 4 downTo 0) {
                val t = (phase - i * .1f + 1f) % 1f
                val pulse = ((1 - cos(t * 2 * Math.PI)) / 2).toFloat()
                val d = diameter * (.2f + i * .2f) * (1f + .3f * pulse)
                val origin = Offset(center.x - d / 2, center.y - d / 2)
                drawCircle(Color.Black.copy(alpha = .16f), d / 2, center + Offset(0f, d * (.025f + .035f * pulse)))
                drawCircle(Brush.verticalGradient(listOf(AliflixAccentSecondary.copy(alpha = .035f),
                    AliflixAccentPrimary.copy(alpha = .075f))), d / 2, center)
                drawArc(AliflixAccentSecondary.copy(alpha = (1f - i * .18f) * (.3f + .25f * pulse)),
                    190f, 160f, false, origin, Size(d, d), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
            }
        }
        AliflixLogoMark(Modifier.fillMaxSize(.24f))
    }
}
