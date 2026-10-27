package com.aliflix.app.talk

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real loudspeaker and capture/AEC/neural path; far-end speech is owned test
 * material. No ambient microphone PCM is saved. No model or network involved. */
class Talk183SpeakerStressDeviceTest {
 @Test fun sustainedSpeakerSpeechCannotBecomeItsOwnMicrophoneActivity(){
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  val receipt=File(i.targetContext.getExternalFilesDir(null),"talk-183-speaker-stress.json")
  receipt.delete()
  try{runBlocking{
   val original=talkValidationFixture("question-en.pcm").readBytes()
   val samples=ShortArray(original.size/2){at->((original[at*2].toInt() and 255) or (original[at*2+1].toInt() shl 8)).toShort()}
   val pcm=ByteArray(samples.size*3)
   for(n in 0 until pcm.size/2){val position=n*2.0/3;val left=position.toInt().coerceAtMost(samples.lastIndex);val right=(left+1).coerceAtMost(samples.lastIndex);val value=(samples[left]+(samples[right]-samples[left])*(position-left)).toInt();pcm[n*2]=value.toByte();pcm[n*2+1]=(value shr 8).toByte()}
   withContext(Dispatchers.Main){voice.debugStartLocalAudio()}
   val began=SystemClock.elapsedRealtime();var rendered=0L
   repeat(12){
    withContext(Dispatchers.Main){voice.debugQueueSpeech(pcm)}
    withTimeout(12000){while(voice.debugAudioSnapshot().optBoolean("speaking")||voice.debugRenderedSamples<rendered+pcm.size/2)delay(25)}
    rendered=voice.debugRenderedSamples
    assertEquals("Loudspeaker speech must not open a local user turn",0,voice.debugLocalBarges)
    delay(180)
   }
   delay(1500)
   assertTrue(voice.debugActivityEvents.isEmpty())
   receipt.writeText(JSONObject().put("durationMs",SystemClock.elapsedRealtime()-began).put("rendered",rendered).put("activity",JSONArray(voice.debugActivityEvents.toList())).put("audio",voice.debugAudioSnapshot()).toString(2))
  }}finally{if(!receipt.exists())receipt.writeText(JSONObject().put("activity",JSONArray(voice.debugActivityEvents.toList())).put("audio",voice.debugAudioSnapshot()).toString(2));i.runOnMainSync{voice.suspendSession();activity.finish()}}
 }
}
