package com.aliflix.app.talk

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.sin

class Talk183TurnOwnershipDeviceTest {
 @Test fun lateTranscriptsAndProviderActivityCannotStopAudibleOutputWithoutLocalSpeech(){
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val session=activity.voice
  try{runBlocking{
   withContext(Dispatchers.Main){session.debugStartLocalAudio();session.debugSetInputActivity(true);session.debugSetInputActivity(false)}
   val bytes=ByteArray(24000*2*5){0}
   for(n in 0 until bytes.size/2){val v=(sin(2*Math.PI*317*n/24000)*.05*32767).toInt();bytes[n*2]=v.toByte();bytes[n*2+1]=(v shr 8).toByte()}
   withContext(Dispatchers.Main){session.debugQueueSpeech(bytes)}
   withTimeout(5000){while(session.debugRenderedSamples<2400)delay(20)}
   val before=session.debugRenderedSamples;val interruptions=session.debugInterruptions
   withContext(Dispatchers.Main){
    session.debugReplayServerMessage(JSONObject("""{"serverContent":{"inputTranscription":{"text":"A late independent transcript"}}}"""))
    session.debugReplayServerMessage(JSONObject("""{"voiceActivity":{"type":"ACTIVITY_START"}}"""))
    session.debugReplayServerMessage(JSONObject("""{"serverContent":{"interrupted":true}}"""))
   }
   delay(600)
   assertEquals("Only local microphone speech can interrupt",interruptions,session.debugInterruptions)
   assertTrue("Late input metadata must not flush hardware playback",session.debugRenderedSamples>before+8000)
   assertTrue(session.debugOutputPlaying)
   val began=SystemClock.elapsedRealtime()
   withContext(Dispatchers.Main){session.debugSetInputActivity(true)}
   assertFalse("A genuine local activity stops playback before network acknowledgement",session.debugOutputPlaying)
   assertEquals(0,session.debugPlayedSamples)
   assertTrue(SystemClock.elapsedRealtime()-began<250)
  }}finally{i.runOnMainSync{session.suspendSession();activity.finish()}}
 }
 @Test fun neuralSpeechInterruptsTheRealOutputWithoutWaitingForRemoteVad(){
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val session=activity.voice
  val receipt=File(i.targetContext.getExternalFilesDir(null),"talk-183-local-barge.json")
  try{runBlocking{
   withContext(Dispatchers.Main){session.debugStartLocalAudio()}
   val pcm=talkValidationFixture("question-en.pcm").readBytes()
   val tone=ByteArray(24000*2*12)
   for(n in 0 until tone.size/2){val v=(sin(2*Math.PI*317*n/24000)*.035*32767).toInt();tone[n*2]=v.toByte();tone[n*2+1]=(v shr 8).toByte()}
   withContext(Dispatchers.Main){session.debugQueueSpeech(tone)}
   withTimeout(5000){while(session.debugRenderedSamples<2400)delay(20)}
   val before=session.debugLocalBarges;val began=SystemClock.elapsedRealtime()
   val injection=launch(Dispatchers.Main){session.debugInjectDuplexUtterance(pcm)}
   withTimeout(1200){while(session.debugLocalBarges<=before)delay(10)}
   val latency=SystemClock.elapsedRealtime()-began
   receipt.writeText(JSONObject().put("latencyMs",latency).put("events",JSONArray(session.debugActivityEvents.toList())).put("audio",session.debugAudioSnapshot()).toString(2))
   assertTrue("Speech start should interrupt well below a second: $latency ms",latency<650)
   assertFalse(session.debugOutputPlaying)
   injection.cancelAndJoin()
  }}finally{i.runOnMainSync{session.suspendSession();activity.finish()}}
 }
 @Test fun shortStopInterruptsAfterMuteWithoutMuteCuttingTheAnswer(){
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val session=activity.voice
  try{runBlocking{
   withContext(Dispatchers.Main){session.debugStartLocalAudio()}
   val pcm=talkValidationFixture("stop-183.pcm").readBytes()
   val tone=ByteArray(24000*2*10)
   for(n in 0 until tone.size/2){val v=(sin(2*Math.PI*317*n/24000)*.035*32767).toInt();tone[n*2]=v.toByte();tone[n*2+1]=(v shr 8).toByte()}
   withContext(Dispatchers.Main){session.debugQueueSpeech(tone)}
   withTimeout(5000){while(session.debugRenderedSamples<2400)delay(20)}
   val before=session.debugRenderedSamples;val local=session.debugLocalBarges
   withContext(Dispatchers.Main){session.toggleMute()}
   delay(300)
   assertTrue("Muting the microphone must preserve playing speech",session.debugRenderedSamples>before+4000&&session.debugOutputPlaying)
   assertEquals(local,session.debugLocalBarges)
   withContext(Dispatchers.Main){session.toggleMute()}
   val began=SystemClock.elapsedRealtime()
   val injection=launch(Dispatchers.Main){session.debugInjectDuplexUtterance(pcm)}
   withTimeout(1000){while(session.debugLocalBarges<=local)delay(10)}
   val latency=SystemClock.elapsedRealtime()-began
   File(i.targetContext.getExternalFilesDir(null),"talk-183-short-stop.json").writeText(JSONObject().put("latencyMs",latency).put("events",JSONArray(session.debugActivityEvents.toList())).toString(2))
   assertTrue("A short Stop must seize the floor promptly: $latency ms",latency<650)
   assertFalse(session.debugOutputPlaying)
   injection.cancelAndJoin()
  }}finally{i.runOnMainSync{session.suspendSession();activity.finish()}}
 }
}
