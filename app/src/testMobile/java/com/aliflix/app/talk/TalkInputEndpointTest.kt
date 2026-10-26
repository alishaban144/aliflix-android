package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkInputEndpointTest {
 @Test fun shortGreetingEndsOnceAfterSilenceWithoutGatingAudio() {
  val endpoint=TalkInputEndpoint()
  assertFalse(endpoint.accept(.05,50,1000,false))
  assertFalse(endpoint.accept(.04,50,1050,false))
  assertFalse(endpoint.accept(0.0,50,1800,false))
  assertFalse(endpoint.accept(0.0,50,2050,false))
  assertTrue(endpoint.accept(0.0,50,2850,false))
  assertFalse(endpoint.accept(0.0,50,2900,false))
 }
 @Test fun farEndAudioAndClicksCannotFinishAUserTurn() {
  val endpoint=TalkInputEndpoint()
  endpoint.accept(.3,50,1000,true)
  assertFalse(endpoint.accept(0.0,50,2500,false))
  endpoint.accept(.1,50,3000,false)
  assertFalse(endpoint.accept(0.0,50,4500,false))
 }
}
