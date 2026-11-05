package com.aliflix.app.player

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.*
import com.aliflix.app.ui.common.rememberMobileMotionEnabled
import com.aliflix.app.ui.common.rememberMobileResumed
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixContentPrimary

internal const val RISING_BALL_CYCLE_MS=1000
private val risingEase=CubicBezierEasing(.42f,0f,.58f,1f)
internal fun risingBallLeg(phase:Float)=if(phase<=.5f)phase*2f else (1f-phase)*2f
internal fun risingBallScale(leg:Float):Offset = when {
    leg<.4f -> risingEase.transform((leg/.4f).coerceIn(0f,1f)).let{Offset(1f-.2f*it,.7f+.5f*it)}
    leg<.6f -> risingEase.transform(((leg-.4f)/.2f).coerceIn(0f,1f)).let{Offset(.8f+.2f*it,1.2f-.2f*it)}
    else -> Offset(1f,1f)
}

@Composable internal fun RisingBallLoadingIndicator(modifier:Modifier=Modifier) {
    val motion=rememberMobileMotionEnabled()&&rememberMobileResumed()&&android.animation.ValueAnimator.areAnimatorsEnabled()
    val phase=if(motion){
        val loop=rememberInfiniteTransition(label="rising-ball")
        loop.animateFloat(0f,1f,infiniteRepeatable(tween(RISING_BALL_CYCLE_MS,easing=LinearEasing)),label="reference-cycle").value
    }else .3f
    RisingBallFrame(phase,motion,modifier)
}

@Composable internal fun RisingBallFrame(phase:Float,motion:Boolean,modifier:Modifier=Modifier){
    Canvas(modifier.semantics{contentDescription="Loading video";progressBarRangeInfo=ProgressBarRangeInfo.Indeterminate}){
        val unit=minOf(size.width/136f,size.height/206f)
        translate((size.width-120f*unit)/2f,(size.height-190f*unit)/2f+80f*unit){
            scale(unit,unit,pivot=Offset.Zero){
                val step=if(motion)risingEase.transform(phase)else .5f
                for(n in 0..3){
                    val fromX=when(n){0,1->75f;2->40f;else->5f}
                    val toX=when(n){0->75f;1->40f;else->5f}
                    val fromY=when(n){0,1->10f;2->50f;else->90f}
                    val toY=when(n){0->10f;1->50f;else->90f}
                    val alpha=when(n){0->step;3->1f-step;else->1f}
                    drawRoundRect(AliflixContentPrimary.copy(alpha=alpha*.78f),
                        topLeft=Offset(fromX+(toX-fromX)*step,fromY+(toY-fromY)*step),size=Size(45f,7f),cornerRadius=CornerRadius(4f))
                }
                val leg=risingBallLeg(phase)
                val center=Offset(65f,45f-110f*risingEase.transform(leg))
                val stretch=if(motion)risingBallScale(leg)else Offset(1f,1f)
                scale(stretch.x,stretch.y,pivot=center){drawCircle(AliflixAccentSecondary,15f,center)}
            }
        }
    }
}
