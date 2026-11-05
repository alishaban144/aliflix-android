package com.aliflix.app.talk

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.player.RisingBallFrame
import com.aliflix.app.ui.theme.AliflixMobileTheme
import org.junit.*
import org.junit.Assert.*
import java.io.File
import kotlin.math.*

class Talk193MotionDeviceTest {
 @get:Rule val compose=createComposeRule()
 private fun save(name:String,image:android.graphics.Bitmap){
  File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),name).outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
 }
 @Test fun askLedWaitsForReadinessAndBrightensWithRealLevelInput(){
  val ready=mutableStateOf(false);val level=mutableFloatStateOf(0f)
  compose.setContent{AliflixMobileTheme{Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center){
   TalkLiveOrb("Listening",{level.floatValue},false,Modifier.size(300.dp).testTag("led"),haloStyle=TalkHaloStyle.LED,haloReady=ready.value)
  }}}
  fun sample():Pair<Double,android.graphics.Bitmap>{
   val image=compose.onNodeWithTag("led").captureToImage().asAndroidBitmap();var total=0L;var count=0
   for(y in 0 until image.height)for(x in 0 until image.width){
    val r=hypot((x-image.width/2f).toDouble(),(y-image.height/2f).toDouble())/image.width
    if(r in .35.. .47){val pixel=image.getPixel(x,y);total+=android.graphics.Color.red(pixel)+android.graphics.Color.green(pixel)+android.graphics.Color.blue(pixel);count++}
   }
   return total.toDouble()/count to image
  }
  val off=sample();save("talk193-led-off.png",off.second)
  compose.runOnIdle{ready.value=true};val idle=sample();save("talk193-led-ready.png",idle.second)
  compose.runOnIdle{level.floatValue=.95f};val speech=sample();save("talk193-led-speech.png",speech.second)
  assertTrue("Ready LED must be visible",idle.first>off.first+3)
  assertTrue("Speech intensity must brighten the LED",speech.first>idle.first*1.3)
  compose.runOnIdle{ready.value=false};assertEquals(off.first,sample().first,1.0)
 }
 @Test fun loaderKeepsTheReferenceTrajectoryAndNeverClipsItsBall(){
  val phase=mutableFloatStateOf(0f)
  compose.setContent{AliflixMobileTheme{Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment=Alignment.Center){
   RisingBallFrame(phase.floatValue,true,Modifier.size(144.dp,216.dp).testTag("loader"))
  }}}
  fun centroid(at:Float):Double{
   compose.runOnIdle{phase.floatValue=at}
   val image=compose.onNodeWithTag("loader").captureToImage().asAndroidBitmap();save("talk193-loader-${(at*1000).toInt()}.png",image)
   var sum=0L;var count=0;var top=image.height;var bottom=0
   for(y in 0 until image.height)for(x in 0 until image.width){val c=image.getPixel(x,y)
    if(android.graphics.Color.blue(c)-android.graphics.Color.green(c)>12&&android.graphics.Color.blue(c)-android.graphics.Color.red(c)>5){sum+=y;count++;top=min(top,y);bottom=max(bottom,y)}
   }
   assertTrue("A complete unclipped ball must remain visible",count>30&&top>1&&bottom<image.height-2)
   return sum.toDouble()/count
  }
  val base=centroid(0f);val middle=centroid(.25f);val apex=centroid(.5f);val reverse=centroid(.75f)
  assertTrue(apex<middle&&middle<base);assertEquals(middle,reverse,2.0)
 }
 @Test fun playerFlightSettlesAndCanReverseWithoutChangingTheSurface(){
  val shown=mutableStateOf(false)
  compose.mainClock.autoAdvance=false
  compose.setContent{AliflixMobileTheme{Box(Modifier.fillMaxSize().background(Color(0xFF315A45)),contentAlignment=Alignment.Center){
   AnimatedVisibility(shown.value,modifier=Modifier.fillMaxSize(),enter=fadeIn(tween(180)),exit=fadeOut(tween(360))){
    val flight=playerOrbTransition(transition,true)
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
     Box(Modifier.size(96.dp).then(flight).background(Color(0xFFEFE8FF),CircleShape).testTag("flight"))
    }
   }
  }}}
  compose.runOnIdle{shown.value=true};compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeBy(220)
  val moving=compose.onNodeWithTag("flight").fetchSemanticsNode().boundsInRoot
  save("talk193-flight-in.png",compose.onRoot().captureToImage().asAndroidBitmap())
  compose.mainClock.advanceTimeBy(700);val settled=compose.onNodeWithTag("flight").fetchSemanticsNode().boundsInRoot
  assertTrue(moving.width<settled.width);assertTrue(moving.center.y<settled.center.y)
  compose.runOnIdle{shown.value=false};compose.mainClock.advanceTimeBy(160)
  save("talk193-flight-out.png",compose.onRoot().captureToImage().asAndroidBitmap())
  compose.mainClock.advanceTimeBy(450);compose.onNodeWithTag("flight").assertDoesNotExist()
  compose.runOnIdle{shown.value=true};compose.mainClock.advanceTimeBy(850)
  assertEquals(settled.width,compose.onNodeWithTag("flight").fetchSemanticsNode().boundsInRoot.width,.1f)
 }
}
