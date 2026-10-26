package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkOrbIntentTest {
 @Test fun attentionFollowsTheCurrentlySpokenVisibleTitleAndNotAStaleCard(){
  val names=listOf(listOf("Primer"),listOf("The Vast of Night"))
  val first=talkOrbMention("Primer uses a constrained visual language.",names)
  val second=talkOrbMention("Primer is denser. The Vast of Night is more relaxed.",names)
  assertTrue(talkOrbIntent("Speaking",2,first).x<.5f)
  assertTrue(talkOrbIntent("Speaking",2,second).x>.5f)
  assertEquals(-1,talkOrbMention("Let's talk about the lighting.",names))
  assertEquals(.5f,talkOrbIntent("Listening",2,second).x,0f)
 }
 @Test fun thinkingVacatesTheReadingSpaceAndListeningReturnsAttentionToTheViewer(){
  val thought=talkOrbIntent("Thinking",2,-1);val answer=talkOrbIntent("Speaking",2,0)
  assertTrue(thought.y<answer.y&&thought.scale<answer.scale)
  assertEquals(.5f,talkOrbIntent("Listening",0,-1).x,0f)
  assertEquals(TalkOrbIntent(.5f,.5f,1f),talkOrbIntent("Ready",0,-1))
 }
}
