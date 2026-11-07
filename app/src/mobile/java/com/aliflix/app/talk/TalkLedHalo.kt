package com.aliflix.app.talk

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.sin

internal enum class TalkHaloStyle { SOFT, LED }
internal fun talkVoiceHaloReady(state:TalkVoiceState)=!state.textMode&&!state.muted&&
    state.phase in setOf("Listening","Speaking","Thinking")

/** Unlumen Glow: the same five-second conic sweep, adapted to a circular face.
 * The enclosing Ask-only layer supplies the reference's Gaussian blur. */
internal class TalkLedHaloField(width:Float,height:Float){
    private val dimension=minOf(width,height)
    private val center=Offset(width/2f,height/2f)
    private val colors=listOf(Color(0xFF7256E8),Color(0xFFC4ABFF),Color(0xFFF1B5DC),Color(0xFF8B9EFF),Color(0xFF7256E8))
    private val conic=Brush.sweepGradient(colors,center)
    private val ambient=Brush.radialGradient(0f to Color.Transparent,.55f to Color(0xFFAC8FF0).copy(alpha=.35f),1f to Color.Transparent,center=center,radius=dimension*.48f)
    fun draw(scope:DrawScope,ready:Float,energy:Float,seconds:Float,motion:Boolean)=with(scope){
        if(ready<=.001f)return@with
        val level=energy.coerceIn(0f,1f)
        val breath=if(motion)1f+sin(seconds*1.1f)*.012f+level*.065f else 1f
        scale(breath,pivot=center){
            drawCircle(ambient,dimension*.48f,center,alpha=ready*(.55f+level*.45f))
            rotate(if(motion)seconds*72f else 0f,pivot=center){
                drawCircle(conic,dimension*.366f,center,alpha=ready*(.50f+level*.50f))
            }
        }
    }
}
