package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkOrbFlightTest {
 @Test fun aMidFlightLayoutChangePreservesContinuityAndSettlesAtItsNewAnchor(){
  val flight=TalkOrbFlight();val home=TalkOrbPose(200f,400f,300f)
  flight.step(home,0f,0f,true)
  var p=home
  repeat(18){p=flight.step(TalkOrbPose(50f,50f,42f),1f/60,0f,true)}
  val before=p
  p=flight.step(TalkOrbPose(300f,150f,160f),1f/60,0f,true)
  assertTrue(kotlin.math.abs(p.x-before.x)<20f);assertTrue(kotlin.math.abs(p.diameter-before.diameter)<20f)
  repeat(300){p=flight.step(TalkOrbPose(300f,150f,160f),1f/60,0f,true)}
  assertEquals(300f,p.x,.1f);assertEquals(150f,p.y,.1f);assertEquals(160f,p.diameter,.1f)
 }
 @Test fun reducedMotionAndFrameHitchesRemainBounded(){
  val flight=TalkOrbFlight();val target=TalkOrbPose(50f,80f,42f)
  assertEquals(target,flight.step(target,10f,8f,false))
  repeat(10000){val p=flight.step(target,if(it%17==0)5f else 1f/120,8f,true)
   assertTrue(p.x.isFinite()&&p.y.isFinite()&&p.diameter>0f);assertTrue(kotlin.math.abs(p.x-target.x)<30f)
  }
 }
}
