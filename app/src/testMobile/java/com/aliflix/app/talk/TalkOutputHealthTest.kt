package com.aliflix.app.talk

import org.junit.Assert.*
import org.junit.Test

class TalkOutputHealthTest {
 @Test fun queuedButUnrenderedSpeechRecoversWithoutMicrophoneEvents(){
  val health=TalkOutputHealth()
  assertEquals(0,health.inspect(0,1,0,12000,false))
  assertEquals(0,health.inspect(1499,1,0,12000,false))
  assertEquals(1,health.inspect(1500,1,0,12000,false))
  assertEquals(2,health.inspect(3000,1,0,12000,false))
 }
 @Test fun realProgressDrainsAndIntentionalInterruptionResetTheRecoveryClock(){
  val health=TalkOutputHealth()
  health.inspect(0,1,0,12000,false)
  assertEquals(0,health.inspect(1400,1,8000,12000,false))
  assertEquals(0,health.inspect(2500,1,8000,12000,true))
  assertEquals(0,health.inspect(3500,1,12000,12000,false))
  assertEquals(0,health.inspect(5000,2,0,12000,false))
  assertEquals(1,health.inspect(6500,2,0,12000,false))
 }
}
