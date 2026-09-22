package com.aliflix.app.ui.discover

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.launch.AliflixHeatmapLogo
import com.aliflix.app.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A compact, draw-only orbital treatment around the application's ORIGINAL logo geometry. */
@Composable
internal fun DiscoverAskCard(onMode: (Int) -> Unit) {
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    val orbit = rememberInfiniteTransition(label = "ask-orbit")
    val phase by orbit.animateFloat(
        initialValue = 0f,
        targetValue = if (reducedMotion) 0f else 1f,
        animationSpec = infiniteRepeatable(tween(48000, easing = LinearEasing)),
        label = "subtle-orbit",
    )
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = AliflixSurfacePrimary,
        border = BorderStroke(1.dp, AliflixAccentSecondary.copy(alpha = .36f)),
    ) {
        Column(
            modifier = Modifier.background(
                Brush.linearGradient(listOf(Color(0xFF251635), Color(0xFF171625), Color(0xFF2A1C3E)))
            ).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(84.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Ask Aliflix",
                    maxLines = 1,
                    fontSize = 27.sp,
                    lineHeight = 31.sp,
                    fontWeight = FontWeight.Bold,
                    color = AliflixContentPrimary,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val r = size.minDimension * .41f
                        val angle = phase * (2f * PI.toFloat())
                        drawCircle(
                            Brush.radialGradient(
                                listOf(AliflixAccentSecondary.copy(alpha = .19f + .035f * sin(angle)), Color.Transparent),
                                center, r * 1.22f,
                            ),
                            radius = r * 1.22f,
                        )
                        listOf(.67f, .91f).forEachIndexed { index, fraction ->
                            val radius = r * fraction
                            drawCircle(AliflixAccentSecondary.copy(alpha = .25f), radius, style = Stroke(.65.dp.toPx()))
                            val theta = angle * if (index == 0) 1f else -.65f + index * 2.4f
                            drawCircle(
                                AliflixAccentSecondary.copy(alpha = .80f),
                                1.65.dp.toPx(),
                                Offset(center.x + cos(theta) * radius, center.y + sin(theta) * radius),
                            )
                        }
                    }
                    // The original heatmap renderer now starts at its illuminated
                    // lilac phase; use that same authentic mark as the Discover header.
                    AliflixHeatmapLogo(timeSeconds = 0f, modifier = Modifier.size(46.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Describe", "Find similar").forEachIndexed { index, label ->
                    Button(
                        onClick = { onMode(index) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(13.dp),
                        contentPadding = PaddingValues(horizontal = 9.dp),
                        border = BorderStroke(1.dp, AliflixAccentSecondary.copy(alpha = .35f)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (index == 0) Color(0xFFE9D9FF) else Color(0xFF423051),
                            contentColor = if (index == 0) Color(0xFF271337) else Color.White,
                        ),
                    ) {
                        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
