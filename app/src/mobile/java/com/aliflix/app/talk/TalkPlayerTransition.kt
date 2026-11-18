package com.aliflix.app.talk

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.*

@Composable internal fun playerOrbTransition(transition:Transition<EnterExitState>,motion:Boolean):Modifier{
    val progress by transition.animateFloat(transitionSpec={
        if(!motion)tween(0)
        else if(targetState==EnterExitState.Visible)keyframes{
            durationMillis=320
            0f at 0 using LinearOutSlowInEasing
            .38f at 48 using LinearOutSlowInEasing
            .88f at 145 using FastOutSlowInEasing
            1.035f at 225 using FastOutSlowInEasing
            1f at 320
        }else tween(360,easing=FastOutSlowInEasing)
    },label="player-orb-flight"){if(it==EnterExitState.Visible)1f else 0f}
    val density=LocalDensity.current
    val compact=LocalConfiguration.current.screenHeightDp<400
    val x=with(density){68.dp.toPx()}
    val y=with(density){(if(compact)14.dp else 94.dp).toPx()}
    return Modifier.graphicsLayer{
        val entering=transition.targetState==EnterExitState.Visible
        val p=progress;val arc=sin(p*PI.toFloat());val size=.50f+.50f*p
        alpha=if(entering)(.82f+p*.48f).coerceAtMost(1f) else p.coerceIn(0f,1f)
        translationX=-x*(1f-p)+arc*x*.29f;translationY=-y*(1f-p)+arc*y*.24f
        scaleX=size*(1f+arc*.14f);scaleY=size*(1f-arc*.10f)
        rotationZ=-12f*(1f-p)+arc*8f
    }
}
