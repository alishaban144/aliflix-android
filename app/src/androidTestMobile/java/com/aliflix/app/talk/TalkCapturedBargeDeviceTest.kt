package com.aliflix.app.talk

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual phone speaker/capture. Owned near speech enters before software AEC
 * and VAD; platform effects, when selected, precede AudioRecord delivery. */
class TalkCapturedBargeDeviceTest {
 @Test fun aDifferentVoiceSeizesTheFloorThroughTheActualCaptureProcessing(){
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  try{runBlocking{
   withContext(Dispatchers.Main){voice.debugStartLocalAudio()}
   val original=talkValidationFixture("question-en.pcm").readBytes()
   val pcm=ShortArray(original.size/2){((original[it*2].toInt()and 255)or(original[it*2+1].toInt()shl 8)).toShort()}
   val far=ByteArray(pcm.size*3*5)
   for(n in 0 until far.size/2){val p=(n*2.0/3)%pcm.size;val a=p.toInt();val b=(a+1)%pcm.size;val value=(pcm[a]+(pcm[b]-pcm[a])*(p-a)).toInt();far[n*2]=value.toByte();far[n*2+1]=(value shr 8).toByte()}
   val latencies=JSONArray()
   repeat(2){round->
    withContext(Dispatchers.Main){voice.debugQueueSpeech(far)}
    delay(2500)
    assertEquals("Speaker echo after a flush must not seize the floor",round,voice.debugLocalBarges)
    val injection=launch(Dispatchers.Main){voice.debugInjectCapturedUtterance(talkValidationFixture("question-de.pcm").readBytes())}
    withTimeout(1800){while(voice.debugLocalBarges<=round)delay(10)}
    val latency=SystemClock.elapsedRealtime()-voice.debugNearOnsetAt
    assertTrue("Measure actual speech onset, not fixture silence",voice.debugNearOnsetAt>0)
    assertFalse("Barge must stop actual output",voice.debugOutputPlaying)
    assertTrue("Pre-AEC near speech must interrupt promptly: $latency",latency<450)
    latencies.put(latency)
    File(i.targetContext.getExternalFilesDir(null),"talk-184-captured-barge.json").writeText(JSONObject().put("latenciesMs",latencies).put("audio",voice.debugAudioSnapshot()).put("events",JSONArray(voice.debugActivityEvents.toList())).toString(2))
    injection.cancelAndJoin();delay(1200)
   }
  }}finally{i.runOnMainSync{voice.suspendSession();activity.finish()}}
 }
}
