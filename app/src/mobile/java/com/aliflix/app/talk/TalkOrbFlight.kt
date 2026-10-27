package com.aliflix.app.talk

import kotlin.math.*

/** Native spatial adaptation of Lumaform Regard's retained gaze/body spring,
 * minimum-jerk attention. MIT, Ahmet Bektes, 2026.
 * Attribution and full notice: assets/talk-orb-motion-license.txt. */
internal data class TalkOrbPose(val x:Float,val y:Float,val diameter:Float,val bank:Float=0f)

internal class TalkOrbFlight {
 private var pose:TalkOrbPose?=null
 private var vx=0f;private var vy=0f;private var vs=0f
 private var phase="";private var arousal=0f
 private var attentionX=0f;private var attentionY=0f
 private var fromX=0f;private var fromY=0f;private var toX=0f;private var toY=0f
 private var age=0f;private var duration=.4f
 private fun minimumJerk(t:Float)=t*t*t*(10f+t*(-15f+6f*t))
 fun step(target:TalkOrbPose,seconds:Float,roam:Float,motion:Boolean,state:String="",energy:Float=0f,pointerX:Float=0f,pointerY:Float=0f):TalkOrbPose {
  if(pose==null||!motion){pose=target;vx=0f;vy=0f;vs=0f;return target}
  val dt=seconds.coerceIn(0f,.05f)
  // Regard's attention aversion is purposeful: look away to think, then
  // return to the viewer. No random body destinations or roaming timer.
  if(state!=phase){
   phase=state;fromX=attentionX;fromY=attentionY
   toX=if(state=="Thinking")-.20f else 0f;toY=if(state=="Thinking")-.25f else 0f
   duration=.36f+.34f*hypot(toX-fromX,toY-fromY);age=0f
  }
  arousal+=(energy.coerceIn(0f,1f)-arousal)*(1f-exp(-dt/if(energy>arousal).055f else .32f))
  age+=dt;val blend=minimumJerk((age/duration).coerceIn(0f,1f))
  attentionX=fromX+(toX-fromX)*blend;attentionY=fromY+(toY-fromY)*blend
  val follow=if(state=="Listening"||state=="Muted").45f else .18f
  val aimX=target.x+(attentionX+pointerX.coerceIn(-1f,1f)*follow)*roam
  val aimY=target.y+(attentionY+pointerY.coerceIn(-1f,1f)*follow)*roam*.6f-arousal*roam*.15f
  val size=target.diameter*(1f+arousal*(if(state=="Listening").035f else .022f))
  var p=requireNotNull(pose)
  // Regard's head arrives after attention. Retain all velocities when a card,
  // keyboard or mode changes the target; no animation is restarted mid-flight.
  val steps=maxOf(1,ceil(dt/.008f).toInt());val h=dt/steps
  repeat(steps){
   val omega=5f
   vx+=((aimX-p.x)*omega*omega-2f*omega*vx)*h
   vy+=((aimY-p.y)*omega*omega-2f*omega*vy)*h
   vs+=((size-p.diameter)*36f-12f*vs)*h
   p=p.copy(x=p.x+vx*h,y=p.y+vy*h,diameter=(p.diameter+vs*h).coerceAtLeast(1f))
  }
  p=p.copy(bank=(-vx/300f).coerceIn(-.8f,.8f))
  pose=p;return p
 }
}
