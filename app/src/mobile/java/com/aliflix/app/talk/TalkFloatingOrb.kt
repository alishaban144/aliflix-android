package com.aliflix.app.talk

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aliflix.app.ui.discover.DiscoverTouchTracker

/** One retained face, irrespective of voice/chat, keyboard and result layouts.
 * A fixed drawing surface keeps shaders and face geometry warm during flight. */
@Composable internal fun TalkFloatingOrb(target:TalkOrbPose,roam:Float,phase:String,level:()->Float,motion:Boolean,tracker:DiscoverTouchTracker,tag:String){
 BoxWithConstraints(Modifier.fillMaxSize()){
 val density=LocalDensity.current
 val drawingSize=minOf(320.dp,maxWidth,maxHeight)
 val base=with(density){drawingSize.toPx()}
 val engine=remember{TalkOrbFlight()}
 val goal by rememberUpdatedState(target)
 val travel by rememberUpdatedState(roam)
 val response by rememberUpdatedState(phase)
 val signal by rememberUpdatedState(level)
 val pose=remember{mutableStateOf(target)}
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
 TalkLiveOrb(phase,level,motion,Modifier.size(drawingSize).graphicsLayer{
  val p=pose.value
  translationX=p.x-base/2f;translationY=p.y-base/2f
  scaleX=p.diameter/base;scaleY=scaleX;rotationZ=p.bank
 }.testTag(tag),tracker)
}
}
