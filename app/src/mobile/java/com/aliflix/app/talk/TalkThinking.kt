package com.aliflix.app.talk

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import kotlin.math.*

/** Native port of loading-ui TwinOrbit by Bartosz Zagrodzki (MIT).
 * d78e09e09f9963ae87974593784a4948f612f80d, twin-orbit.tsx.
 * Preserve the authored 155% radius, CSS ease and half-cycle delayed orbit. */
@Composable internal fun TalkThinking(motion:Boolean,modifier:Modifier=Modifier) {
    val phases=if(motion){
        val clock=rememberInfiniteTransition(label="twin-orbit")
        val first by clock.animateFloat(0f,1f,infiniteRepeatable(tween(1000,easing=CubicBezierEasing(.25f,.1f,.25f,1f))),label="orbit-one")
        val second by clock.animateFloat(0f,1f,infiniteRepeatable(tween(1000,easing=CubicBezierEasing(.25f,.1f,.25f,1f)),initialStartOffset=StartOffset(500)),label="orbit-two")
        first to second
    }else 0f to .5f
    Canvas(modifier.size(26.dp)) {
        val diameter=size.minDimension/4.1f
        drawCircle(AliflixAccentSecondary,radius=diameter/2)
        listOf(phases.first,phases.second).forEach { phase ->
            val angle=phase*2*PI
            drawCircle(AliflixAccentSecondary,radius=diameter/2,center=center+Offset(cos(angle).toFloat(),sin(angle).toFloat())*diameter*1.55f)
        }
    }
}
