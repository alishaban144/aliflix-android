package com.aliflix.app.talk

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.*

@Composable internal fun playerOrbTransition(transition:Transition<EnterExitState>,motion:Boolean):Modifier{
    val progress by transition.animateFloat(transitionSpec={
        if(!motion)tween(0)
        else if(targetState==EnterExitState.Visible)keyframes{
            durationMillis=620
            0f at 0 using FastOutSlowInEasing
            .74f at 270 using FastOutSlowInEasing
            1.045f at 440 using FastOutSlowInEasing
            1f at 620
        }else tween(360,easing=FastOutSlowInEasing)
    },label="player-orb-flight"){if(it==EnterExitState.Visible)1f else 0f}
    val density=LocalDensity.current
    val x=with(density){68.dp.toPx()};val y=with(density){94.dp.toPx()}
    return Modifier.graphicsLayer{
        val p=progress;val arc=sin(p*PI.toFloat());val size=.22f+.78f*p
        alpha=((p-.025f)/.64f).coerceIn(0f,1f)
        translationX=-x*(1f-p);translationY=-y*(1f-p)+arc*y*.16f
        scaleX=size*(1f+arc*.085f);scaleY=size*(1f-arc*.065f)
        rotationZ=-17f*(1f-p)+arc*5f
    }
}
