package com.aliflix.app.talk

import androidx.compose.ui.geometry.Offset
import com.aliflix.app.ui.discover.OrbExpression

/** An attentive thought cycle: gather, look aside, consider, then refocus.
 * Unequal holds and a small nod avoid a metronomic spinner or a shaking face. */
internal fun playerThinkingExpression(seconds:Float,activity:TalkOrbActivity):OrbExpression {
    val t=((seconds%3.8f)+3.8f)%3.8f
    fun ease(a:Float,b:Float)=((t-a)/(b-a)).coerceIn(0f,1f).let{it*it*(3f-2f*it)}
    val left=ease(.10f,.64f)*(1f-ease(1.48f,1.93f))
    val right=ease(1.97f,2.39f)*(1f-ease(3.08f,3.76f))
    val gaze=when(activity){TalkOrbActivity.RECALL->Offset(-.12f,.10f);TalkOrbActivity.OBSERVE->Offset(.10f,-.06f);else->Offset.Zero}
    return OrbExpression(gaze+Offset(-.44f*left+.28f*right,.08f+.24f*left+.13f*right),
        .88f-.10f*left-.04f*right,.055f*left-.035f*right)
}
