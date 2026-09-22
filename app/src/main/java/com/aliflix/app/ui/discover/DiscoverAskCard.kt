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
import kotlin.math.*

@Composable
internal fun DiscoverAskCard(onMode: (Int) -> Unit) {
    val reduced = !ValueAnimator.areAnimatorsEnabled()
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduced) {
        if (!reduced) {
            val animation = Animatable(0f)
            animation.animateTo(1f, infiniteRepeatable(tween(48000, easing = LinearEasing))) { phase = value }
        }
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(24.dp), color = AliflixSurfacePrimary,
        border = BorderStroke(1.dp, AliflixAccentSecondary.copy(alpha = .6f))) {
        Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF291747), Color(0xFF17182A), Color(0xFF342050)))).padding(14.dp)) {
            Row(Modifier.fillMaxWidth().height(154.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ask\nAliflix", fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold,
                    color = AliflixContentPrimary, modifier = Modifier.weight(1f))
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val radius = size.minDimension * .45f
                        val breathing = .16f + .035f * sin(phase * 4f * PI.toFloat())
                        drawCircle(Brush.radialGradient(listOf(AliflixAccentPrimary.copy(alpha = breathing), Color.Transparent), center, radius * 1.4f), radius * 1.4f)
                        listOf(.58f, .76f, 1f).forEachIndexed { index, factor ->
                            drawCircle(AliflixAccentSecondary.copy(alpha = .45f), radius * factor, style = Stroke(1.dp.toPx()))
                            val angle = phase * 2 * PI + index * 2.1
                            drawCircle(AliflixAccentSecondary, 2.dp.toPx(), Offset(center.x + cos(angle).toFloat() * radius * factor, center.y + sin(angle).toFloat() * radius * factor))
                        }
                    }
                    AliflixHeatmapLogo(0f, Modifier.size(74.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Describe", "Find similar").forEachIndexed { index, label ->
                    Button(onClick = { onMode(index) }, modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 12.dp),
                        border = BorderStroke(1.dp, AliflixAccentSecondary.copy(alpha = .6f)),
                        colors = ButtonDefaults.buttonColors(containerColor = if (index == 0) Color(0xFFF0E7FF) else Color(0xFF403154),
                            contentColor = if (index == 0) Color(0xFF322344) else Color.White)) {
                        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(19.dp))
                    }
                }
            }
        }
    }
}
