package com.aliflix.app.talk

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.*

internal enum class TalkHaloStyle { SOFT, LED }
internal fun talkVoiceHaloReady(state:TalkVoiceState)=!state.textMode&&!state.muted&&
    state.phase in setOf("Listening","Speaking","Thinking")

internal class TalkLedHaloField(width:Float,height:Float){
    private val dimension=minOf(width,height)
    private val center=Offset(width/2f,height/2f)
    private val extent=dimension*.49f
    private val edge=Brush.radialGradient(
        0f to Color.Transparent,.605f to Color.Transparent,
        .633f to Color(0xFFF5EEFF).copy(alpha=.76f),.66f to Color(0xFFE1CEFF).copy(alpha=.47f),
        .74f to Color(0xFFB69AEF).copy(alpha=.22f),.87f to Color(0xFF9875D5).copy(alpha=.06f),1f to Color.Transparent,
        center=center,radius=extent)
    private val emitters=Array(3){n->
        val angle=(n*2f*PI/3f-.75f).toFloat()
        val at=center+Offset(cos(angle),sin(angle))*dimension*.301f
        at to Brush.radialGradient(0f to Color(0xFFF8F2FF).copy(alpha=.85f),.17f to Color(0xFFE7D5FF).copy(alpha=.58f),
            .43f to Color(0xFFBA9BEF).copy(alpha=.23f),1f to Color.Transparent,center=at,radius=dimension*.155f)
    }
    fun draw(scope:DrawScope,ready:Float,energy:Float,seconds:Float,motion:Boolean)=with(scope){
        if(ready<=.001f)return@with
        val level=energy.coerceIn(0f,1f)
        val breath=if(motion)1f+sin(seconds*.9f)*.002f+level*.012f else 1f
        scale(breath){
            drawCircle(edge,extent,center,alpha=ready*(.51f+level*.44f))
            rotate(if(motion)seconds*9f else 0f,pivot=center){
                for(n in emitters.indices){val (at,brush)=emitters[n]
                    drawCircle(brush,dimension*.155f,at,alpha=ready*(.37f+level*.58f))
                }
            }
        }
    }
}
