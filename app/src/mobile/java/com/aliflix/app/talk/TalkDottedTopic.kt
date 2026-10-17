package com.aliflix.app.talk

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.common.rememberMobileAnimationClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

/** Native adaptation of SmoothUI's Pixel Flow Field text sampling and re-forming
 * grid. The visible lettering consists entirely of fine circular particles.
 * Shape/raster work happens once off-main, never in the animation draw loop. */
@Composable
internal fun TalkDottedTopic(words:String, level:()->Float, motion:Boolean) {
    val density=LocalDensity.current
    val fontSize=with(density){(if(words.length>26)28.sp else 36.sp).toPx()}
    val step=with(density){1.15.dp.toPx()}.coerceAtLeast(1.5f)
    var size by remember {mutableStateOf(IntSize.Zero)}
    val glyph by produceState<TalkDotGlyph?>(null,words,size,fontSize,step) {
        if(size.width>0&&size.height>0)value=withContext(Dispatchers.Default) {
            TalkDotGlyph(size.width,size.height,words,fontSize,step)
        }
    }
    val clock=rememberMobileAnimationClock(motion)
    var began by remember(glyph){mutableFloatStateOf(Float.NaN)}
    LaunchedEffect(glyph){began=clock.value}
    Canvas(Modifier.fillMaxWidth().height(154.dp).onSizeChanged{size=it}
        .testTag("talk-topic").semantics {text=AnnotatedString(words)}) {
        val elapsed=if(!motion)10f else if(began.isNaN())0f else (clock.value-began).coerceAtLeast(0f)
        glyph?.draw(this,elapsed,level().coerceIn(0f,1f),motion)
    }
}

internal class TalkDotGlyph(width:Int,height:Int,words:String,fontSize:Float,private val step:Float) {
    private val x:FloatArray
    private val y:FloatArray
    private val coverage:FloatArray
    private val fixed=Array(16){Path()}
    private val paths=Array(6){Path()}
    private val native=Array(6){paths[it].asAndroidPath()}
    private val colors=Array(6){i->androidx.compose.ui.graphics.lerp(Color(0xFFB6A0F1),Color(0xFFF7F2FF),i/5f)}
    init {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color=android.graphics.Color.WHITE
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            textSize=fontSize
            letterSpacing=if(words.any{it in '\u0600'..'\u06ff'})0f else .035f
        }
        fun layoutText()=StaticLayout.Builder.obtain(words,0,words.length,paint,(width*.94f).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
            .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(paint.textSize*.13f,1f).build()
        var layout=layoutText()
        if(layout.height>height*.94f) {paint.textSize*=height*.94f/layout.height;layout=layoutText()}

        android.graphics.Canvas(bitmap).apply {
            translate(width*.03f,(height-layout.height)/2f)
            layout.draw(this)
        }
        val pixels=IntArray(width*height)
        bitmap.getPixels(pixels,0,width,0,0,width,height);bitmap.recycle()
        val cols=ceil(width/step).toInt();val rows=ceil(height/step).toInt()
        val xs=FloatArray(cols*rows);val ys=FloatArray(cols*rows);val weights=FloatArray(cols*rows)
        var count=0
        for(row in 0 until rows)for(col in 0 until cols) {
            val px=((col+.5f)*step).toInt().coerceIn(0,width-1)
            val py=((row+.5f)*step).toInt().coerceIn(0,height-1)
            var alpha=0f
            for(oy in -1..1)for(ox in -1..1)
                alpha+=(pixels[(py+oy).coerceIn(0,height-1)*width+(px+ox).coerceIn(0,width-1)] ushr 24)/255f
            alpha/=9f
            if(alpha<.14f)continue
            xs[count]=px.toFloat();ys[count]=py.toFloat();weights[count]=alpha;count++
        }
        x=xs.copyOf(count);y=ys.copyOf(count);coverage=weights.copyOf(count)
        val fixedNative=Array(16){fixed[it].asAndroidPath()}
        for(i in x.indices) {
            val group=(x[i]/width*16).toInt().coerceIn(0,15)
            fixedNative[group].addCircle(x[i],y[i],step*(.32f+coverage[i]*.055f),android.graphics.Path.Direction.CW)
        }
    }
    internal val particleCount get()=x.size
    fun draw(scope:DrawScope,time:Float,energy:Float,motion:Boolean)=with(scope) {
        // Settled geometry is immutable: HWUI can cache its paths. Only a
        // travelling light changes; thousands of curves are not rebuilt at 60Hz.
        if(!motion||time>1.1f) {
            for(i in fixed.indices) {
                val light=if(motion)sin(i/16f*PI.toFloat()*2f-time*1.6f)else .5f
                drawPath(fixed[i],colors[((light+1f)*2.5f).toInt().coerceIn(0,5)])
            }
            return@with
        }
        paths.forEach{it.reset()}
        val settle=if(motion)(1f-exp(-time*6f)).coerceIn(0f,1f)else 1f
        for(i in x.indices) {
            val phase=x[i]*.017f+y[i]*.009f
            val wave=if(motion)sin(phase-time*1.6f)else .5f
            val tier=((wave+1f)*2.5f).toInt().coerceIn(0,5)
            val flow=if(motion)step*(.13f+energy*.18f)else 0f
            val px=x[i]+sin(phase+time*.8f)*flow+sin(i*2.399f)*(1f-settle)*step*12
            val py=y[i]+cos(phase*.7f+time)*flow+cos(i*2.399f)*(1f-settle)*step*10
            val radius=step*(.32f+coverage[i]*.055f)*settle
            if(radius>0f)native[tier].addCircle(px,py,radius,android.graphics.Path.Direction.CW)
        }
        for(i in paths.indices)drawPath(paths[i],colors[i])
    }
}
