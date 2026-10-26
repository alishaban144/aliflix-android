package com.aliflix.app.talk

import kotlin.math.*

internal data class TalkLetterPose(val x:Float,val y:Float,val rotation:Float,val alpha:Float)

/** A brief damped orbit, then a fixed baseline. Independent of refresh rate. */
internal fun talkTopicOrbit(age:Float,index:Int):TalkLetterPose {
    val delay=(index%7)*.012f
    val t=((age-delay)/.82f).coerceIn(0f,1f)
    if(t>=1f)return TalkLetterPose(0f,0f,0f,1f)
    val phase=index*2.399963f+t*9f
    val amplitude=exp(-3.8f*t)*(1f-t)*(1f-t)
    return TalkLetterPose(cos(phase)*amplitude,sin(phase)*amplitude*.72f,sin(phase)*amplitude*11f,(age/.09f).coerceIn(0f,1f))
}

/** Reversing background-position shine, with zero velocity at each end. */
internal fun talkTopicShine(seconds:Float):Float=(.5-.5*cos(PI*seconds.coerceAtLeast(0f)/1.1)).toFloat()

internal fun talkTopicReveal(age:Float):Float {
    val t=(age/.16f).coerceIn(0f,1f)
    return t*t*(3f-2f*t)
}
