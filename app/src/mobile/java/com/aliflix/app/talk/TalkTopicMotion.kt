package com.aliflix.app.talk

import kotlin.math.PI
import kotlin.math.sin

/** Absolute active time, shared by old/new words: no stepping, reset or settle seam. */
internal fun talkTopicWave(seconds:Float,position:Float):Float =
    sin(position*2f*PI.toFloat()-seconds*1.25f)

internal fun talkTopicReveal(age:Float):Float {
    val t=(age/.32f).coerceIn(0f,1f)
    return t*t*(3f-2f*t)
}
