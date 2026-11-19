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
        else if(targetState==EnterExitState.Visible)tween(300,easing=LinearOutSlowInEasing)
        else tween(240,easing=FastOutSlowInEasing)
    },label="player-orb-flight"){if(it==EnterExitState.Visible)1f else 0f}
    val density=LocalDensity.current
    val compact=LocalConfiguration.current.screenHeightDp<400
    val x=with(density){68.dp.toPx()}
    val y=with(density){(if(compact)14.dp else 94.dp).toPx()}
    return Modifier.graphicsLayer{
        val p=progress;val arc=sin(p*PI.toFloat());val size=.74f+.26f*p
        alpha=(p*2.6f).coerceIn(0f,1f)
        translationX=-x*(1f-p)+arc*x*.29f;translationY=-y*(1f-p)+arc*y*.24f
        scaleX=size;scaleY=size
        rotationZ=-12f*(1f-p)+arc*8f
    }
}
