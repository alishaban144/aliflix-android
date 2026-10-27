package com.aliflix.app.talk

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/** Known owned PCM only: a delayed room echo and simultaneous different speech. */
class TalkEchoProcessorDeviceTest {
 @Test fun silentGapsAndRendererCaptureStallsCannotBreakTheNativeClock(){
  val processor=requireNotNull(TalkEchoProcessor.create())
  val silence=ShortArray(160)
  val render=ByteArray(480)
  try{
   repeat(3){
    repeat(700){processor.capture(silence,160,0,1)}
    repeat(100){processor.render(render,0,render.size)}
    repeat(1000){processor.capture(silence,160,it%501,1)}
    processor.reset()
   }
  }finally{processor.close();processor.close()}
 }
 @Test fun delayedEchoIsCancelledWhileDifferentNearSpeechSurvives(){
  fun pcm(name:String):ShortArray{val b=talkValidationFixture(name).readBytes();return ShortArray(b.size/2){((b[it*2].toInt()and 255)or(b[it*2+1].toInt()shl 8)).toShort()}}
  val far=pcm("question-en.pcm");val near=pcm("question-de.pcm")
  val processor=requireNotNull(TalkEchoProcessor.create()){"Portable AEC must load"}
  var quietPower=0.0;var nearPower=0.0;var quietCount=0;var nearCount=0
  val started=System.nanoTime()
  try{
   for(frame in 0 until 2400){
    fun sample(index:Int)=if(index<0)0.0 else far[index%far.size].toDouble()*.65
    val at=frame*160
    val render=ByteArray(480)
    for(i in 0 until 240){val p=at+i*2.0/3;val n=p.toInt();val v=(sample(n)+(sample(n+1)-sample(n))*(p-n)).toInt();render[i*2]=v.toByte();render[i*2+1]=(v shr 8).toByte()}
    processor.render(render,0,render.size)
    val capture=ShortArray(160){i->val n=at+i;val voice=if(frame in 1000..1599)near[(n-160000)%near.size]*.7 else 0.0;(sample(n-1920)*.5+sample(n-2560)*.15+voice).toInt().coerceIn(-32768,32767).toShort()}
    val out=processor.capture(capture,160,120,1)
    if(frame in 600..899||frame in 1100..1499)for(i in out.indices step 2){val value=((out[i].toInt()and 255)or(out[i+1].toInt()shl 8)).toShort()/32768.0;if(frame<1000){quietPower+=value*value;quietCount++}else{nearPower+=value*value;nearCount++}}
   }
   val quiet=sqrt(quietPower/quietCount);val speech=sqrt(nearPower/nearCount)
   File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"talk-184-synthetic-echo.json").writeText(JSONObject().put("echoOnlyRms",quiet).put("doubleTalkRms",speech).put("cpuMs",(System.nanoTime()-started)/1000000).toString(2))
   assertTrue("Delayed speaker echo remains: $quiet",quiet<.003)
   assertTrue("Real speech was suppressed with echo: $speech",speech>.006&&speech>quiet*8)
  }finally{processor.close()}
 }
}
