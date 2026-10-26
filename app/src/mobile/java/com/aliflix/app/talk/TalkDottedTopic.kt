package com.aliflix.app.talk

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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

/** Dotted shapes enter on individual damped orbits, settle, then carry a
 * reversing pearl shine. Font shaping and geometry stay off the frame thread. */
@Composable
internal fun TalkDottedTopic(words:String, motion:Boolean) {
    val density=LocalDensity.current
    val assets=LocalContext.current.applicationContext.assets
    val font by produceState<Typeface?>(null,assets) {
        value=withContext(Dispatchers.IO) {runCatching {
            val latin=FontFamily.Builder(Font.Builder(assets,"fonts/Fredoka.ttf")
                .setWeight(700).setFontVariationSettings("'wght' 700").build()).build()
            val arabic=FontFamily.Builder(Font.Builder(assets,"fonts/BalooBhaijaan2.ttf")
                .setWeight(800).setFontVariationSettings("'wght' 800").build()).build()
            Typeface.CustomFallbackBuilder(latin).addCustomFallback(arabic)
                .setStyle(FontStyle(700,FontStyle.FONT_SLANT_UPRIGHT)).setSystemFallback("sans-serif").build()
        }.getOrDefault(Typeface.create("sans-serif-rounded",Typeface.BOLD))}
    }
    val fontSize=with(density){(if(words.length>26)34.sp else 44.sp).toPx()}
    val step=with(density){1.15.dp.toPx()}.coerceAtLeast(1.5f)
    var size by remember {mutableStateOf(IntSize.Zero)}
    val glyph by produceState<TalkDotGlyph?>(null,words,size,fontSize,step,font) {
        if(size.width>0&&size.height>0&&font!=null)value=withContext(Dispatchers.Default) {
            TalkDotGlyph(size.width,size.height,words,fontSize,step,requireNotNull(font))
        }
    }
    val clock=rememberMobileAnimationClock(motion)
    var began by remember(glyph){mutableFloatStateOf(Float.NaN)}
    var current by remember{mutableStateOf<TalkDotGlyph?>(null)}
    var former by remember{mutableStateOf<TalkDotGlyph?>(null)}
    LaunchedEffect(glyph){if(glyph!=null){former=if(motion)current else null;current=glyph;began=clock.value}}
    Canvas(Modifier.fillMaxWidth().height(154.dp).onSizeChanged{size=it}
        .testTag("talk-topic").semantics {text=AnnotatedString(words)}) {
        val elapsed=if(!motion)10f else if(began.isNaN())0f else (clock.value-began).coerceAtLeast(0f)
        if(motion&&elapsed<.16f)former?.draw(this,10f+clock.value,1f-talkTopicReveal(elapsed),motion)
        current?.draw(this,elapsed,if(motion)talkTopicReveal(elapsed)else 1f,motion)
    }
}

internal class TalkDotGlyph(width:Int,height:Int,words:String,fontSize:Float,private val step:Float,font:Typeface=Typeface.create("sans-serif",Typeface.NORMAL)) {
    private val x:FloatArray
    private val y:FloatArray
    private val coverage:FloatArray
    private data class Letter(val bitmap:Bitmap,val left:Float,val top:Float,val center:Offset,val index:Int)
    private val letters:List<Letter>
    private val canvasWidth=width.toFloat()
    private val canvasHeight=height.toFloat()
    private val shineGradient=LinearGradient(-canvasWidth,0f,canvasWidth,0f,
        intArrayOf(0xFFB6A0F1.toInt(),0xFFF7F2FF.toInt(),0xFFB6A0F1.toInt()),null,Shader.TileMode.CLAMP)
    private val shineMatrix=Matrix()
    private val imagePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val settledPaint:Paint
    init {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color=android.graphics.Color.WHITE
            typeface=font
            textSize=fontSize
            letterSpacing=if(words.any{it in '\u0600'..'\u06ff'})0f else .005f
        }
        fun layoutText()=StaticLayout.Builder.obtain(words,0,words.length,paint,(width*.94f).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
            .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(paint.textSize*.13f,1f).build()
        var layout=layoutText()
        if(layout.height>height*.94f) {paint.textSize*=height*.94f/layout.height;layout=layoutText()}

        val left=width*.03f;val top=(height-layout.height)/2f
        android.graphics.Canvas(bitmap).apply {
            translate(left,top)
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
        val iterator=android.icu.text.BreakIterator.getCharacterInstance().apply{setText(words)}
        val boundaries=mutableListOf<Int>();var boundary=iterator.first()
        while(boundary!=android.icu.text.BreakIterator.DONE){boundaries.add(boundary);boundary=iterator.next()}
        data class Cell(val index:Int,val line:Int,val first:Float,val last:Float)
        val cells=boundaries.zipWithNext().mapIndexed { index,(start,end)->
            val line=layout.getLineForOffset(start)
            val first=layout.getPrimaryHorizontal(start)+left
            val last=if(end<words.length&&layout.getLineForOffset(end)!=line)
                (if(layout.getParagraphDirection(line)>0)layout.getLineRight(line)else layout.getLineLeft(line))+left
                else layout.getPrimaryHorizontal(end)+left
            Cell(index,line,minOf(first,last),maxOf(first,last))
        }
        val groups=cells.map{android.graphics.Path()}
        val countByGroup=IntArray(groups.size);val sumX=FloatArray(groups.size);val sumY=FloatArray(groups.size)
        val byLine=(0 until layout.lineCount).map{line->cells.filter{it.line==line}}
        for(i in x.indices) {
            val line=layout.getLineForVertical((y[i]-top).toInt().coerceIn(0,layout.height-1))
            val cell=byLine[line].minByOrNull{if(x[i]<it.first)it.first-x[i]else if(x[i]>it.last)x[i]-it.last else 0f}?:continue
            groups[cell.index].addCircle(x[i],y[i],sqrt(coverage[i])*step*.43f,android.graphics.Path.Direction.CW)
            countByGroup[cell.index]++;sumX[cell.index]+=x[i];sumY[cell.index]+=y[i]
        }
        // Rasterize once off the UI thread. Orbit frames transform small cached
        // images; the settled shine draws one mask, never thousands of circles.
        val mask=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val maskCanvas=android.graphics.Canvas(mask)
        val maskPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.WHITE}
        val letterPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{shader=shineGradient}
        letters=groups.mapIndexedNotNull{index,path->countByGroup[index].takeIf{it>0}?.let{
            maskCanvas.drawPath(path,maskPaint)
            val bounds=android.graphics.RectF().also{path.computeBounds(it,true)}
            val letterLeft=floor(bounds.left)-2f;val letterTop=floor(bounds.top)-2f
            val image=Bitmap.createBitmap((ceil(bounds.right)-letterLeft+2).toInt().coerceAtLeast(1),
                (ceil(bounds.bottom)-letterTop+2).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(image).apply{translate(-letterLeft,-letterTop);drawPath(path,letterPaint)}
            image.prepareToDraw()
            Letter(image,letterLeft,letterTop,Offset(sumX[index]/it,sumY[index]/it),index)
        }}
        mask.prepareToDraw()
        settledPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
            shader=ComposeShader(BitmapShader(mask,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP),shineGradient,PorterDuff.Mode.SRC_IN)
        }
    }
    internal val particleCount get()=x.size
    fun draw(scope:DrawScope,time:Float,alpha:Float,motion:Boolean)=with(scope) {
        drawIntoCanvas {canvas->
            val native=canvas.nativeCanvas
            if(!motion||time>=.9f) {
                val shine=if(motion)talkTopicShine(time-.9f)else .5f
                shineMatrix.setTranslate(canvasWidth*shine,0f)
                shineGradient.setLocalMatrix(shineMatrix)
                settledPaint.alpha=(alpha.coerceIn(0f,1f)*255).roundToInt()
                native.drawRect(0f,0f,canvasWidth,canvasHeight,settledPaint)
            } else for(letter in letters) {
                val pose=talkTopicOrbit(time,letter.index)
                imagePaint.alpha=((alpha*pose.alpha).coerceIn(0f,1f)*255).roundToInt()
                val checkpoint=native.save()
                native.translate(pose.x*step*15f,pose.y*step*15f)
                native.rotate(pose.rotation,letter.center.x,letter.center.y)
                native.drawBitmap(letter.bitmap,letter.left,letter.top,imagePaint)
                native.restoreToCount(checkpoint)
            }
        }
    }
}
