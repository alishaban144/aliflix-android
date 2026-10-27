package com.aliflix.app.talk

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class TalkInputOwnershipRaceDeviceTest {
 @Test fun aQueuedPhysicalStartCannotSurviveAMuteUnmuteBoundary(){
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  try{runBlocking{
   i.runOnMainSync{voice.debugStartLocalAudio();voice.debugStallMicrophone()}
   val tone=ByteArray(24000*2*5)
   for(n in 0 until tone.size/2){val v=(sin(2*Math.PI*317*n/24000)*1200).toInt();tone[n*2]=v.toByte();tone[n*2+1]=(v shr 8).toByte()}
   i.runOnMainSync{voice.debugQueueSpeech(tone)}
   withTimeout(4000){while(voice.debugRenderedSamples<2400)delay(20)}
   val generation=voice.debugCaptureGeneration
   i.runOnMainSync{
    voice.toggleMute();voice.toggleMute()
    voice.debugApplyPhysicalDecision(start=true,end=false,generation=generation)
   }
   assertTrue("A stale frame must not cut the playing answer",voice.debugOutputPlaying)
   assertFalse(voice.debugAudioSnapshot().getBoolean("remoteSpeechActive"))
   assertTrue(voice.debugActivityEvents.isEmpty())
   i.runOnMainSync{voice.debugApplyPhysicalDecision(start=true,end=false,generation=voice.debugCaptureGeneration)}
   assertFalse("A current user activity must still stop output",voice.debugOutputPlaying)
   assertTrue(voice.debugAudioSnapshot().getBoolean("remoteSpeechActive"))
  }}finally{i.runOnMainSync{voice.suspendSession();activity.finish()}}
 }
 @Test fun muteClosesTheWireOwnerEvenAfterTheNeuralGateQueuedItsEnd(){
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  try{
   i.runOnMainSync{
    voice.debugStartLocalAudio();voice.debugStallMicrophone()
    // Main has emitted Start; the capture-thread gate may already be inactive.
    voice.debugSetInputActivity(true)
    val old=voice.debugCaptureGeneration
    voice.toggleMute()
    voice.debugApplyPhysicalDecision(start=false,end=true,generation=old)
    voice.toggleMute()
   }
   assertFalse("The service must not wait forever in an orphaned activity",voice.debugAudioSnapshot().getBoolean("remoteSpeechActive"))
   assertEquals("Exactly one activityEnd belongs to this user",1,voice.debugActivityEvents.count{it.has("end")})
  }finally{i.runOnMainSync{voice.suspendSession();activity.finish()}}
 }
 @Test fun partialMicrophoneSamplesCannotCrossAGenerationOrSourceBoundary(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val observed=mutableListOf<ByteArray>();val generations=mutableListOf<Long>()
  TalkSpeechMonitor(context){pcm,_,generation,_->observed+=pcm;generations+=generation}.use{monitor->
   monitor.offer(ByteArray(512){127},1)
   monitor.offer(ByteArray(512),2)
   assertTrue(observed.isEmpty())
   monitor.offer(ByteArray(512),2)
   assertEquals(listOf(2L),generations)
   assertTrue("Pre-mute samples cannot enter the next frame",observed.single().all{it==0.toByte()})
   monitor.offer(ByteArray(512){63},2,ownedFixture=true)
   monitor.offer(ByteArray(512),2)
   assertEquals(1,observed.size)
   monitor.offer(ByteArray(512),2)
   assertEquals(2,observed.size)
   assertTrue(observed.last().all{it==0.toByte()})
  }
 }
}
