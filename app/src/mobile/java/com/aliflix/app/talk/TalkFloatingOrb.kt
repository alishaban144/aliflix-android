package com.aliflix.app.talk

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aliflix.app.ui.discover.DiscoverTouchTracker

/** One retained face, irrespective of voice/chat, keyboard and result layouts.
 * A fixed drawing surface keeps shaders and face geometry warm during flight. */
@Composable internal fun TalkFloatingOrb(target:TalkOrbPose,roam:Float,phase:String,level:()->Float,motion:Boolean,tracker:DiscoverTouchTracker,tag:String,attention:androidx.compose.ui.geometry.Offset?=null,
    activity:TalkOrbActivity?=null,initialPose:TalkOrbPose?=null,haloReady:Boolean=false,
    eyesClosed:Boolean=false,blinkOnConnect:Boolean=false,motionBounds:Rect?=null){
 BoxWithConstraints(Modifier.fillMaxSize()){
 val density=LocalDensity.current
 val drawingSize=minOf(320.dp,maxWidth)
 val base=with(density){drawingSize.toPx()}
 val engine=remember{TalkOrbFlight(initialPose)}
 val goal by rememberUpdatedState(target)
 val travel by rememberUpdatedState(roam)
 val response by rememberUpdatedState(phase)
 val signal by rememberUpdatedState(level)
 val pose=remember{mutableStateOf(initialPose ?: target)}
 LaunchedEffect(motion){
  if(!motion){pose.value=engine.step(goal,0f,0f,false);return@LaunchedEffect}
  var previous=0L
  while(true)withInfiniteAnimationFrameNanos{now->
   val dt=if(previous==0L)0f else (now-previous)/1_000_000_000f;previous=now
   val point=tracker.point?.takeIf{android.os.SystemClock.uptimeMillis()-tracker.lastTouchMillis<1800}
   val px=point?.let{(it.x-goal.x)/base}?:0f;val py=point?.let{(it.y-goal.y)/base}?:0f
   pose.value=engine.step(goal,dt,travel,true,response,signal(),px,py)
  }
 }
 LaunchedEffect(target,motion){if(!motion)pose.value=engine.step(target,0f,0f,false)}
 TalkLiveOrb(phase,level,motion,Modifier.wrapContentSize(Alignment.TopStart,unbounded=true).size(drawingSize).graphicsLayer{
  val p=pose.value
  val diameter=motionBounds?.let{minOf(p.diameter,it.width,it.height)}?:p.diameter
  val radius=diameter/2f
  val x=motionBounds?.let{p.x.coerceIn(it.left+radius,it.right-radius)}?:p.x
  val y=motionBounds?.let{p.y.coerceIn(it.top+radius,it.bottom-radius)}?:p.y
  translationX=x-base/2f;translationY=y-base/2f
  scaleX=diameter/base;scaleY=scaleX;rotationZ=if(motionBounds==null)p.bank else 0f
 }.testTag(tag),tracker,attention,activity,TalkHaloStyle.LED,haloReady,eyesClosed=eyesClosed,blinkOnConnect=blinkOnConnect)
}
}
