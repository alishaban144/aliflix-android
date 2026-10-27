package com.aliflix.app.talk

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Talk184SpeakerDiversityDeviceTest {
 @Test fun differentVoicesLanguagesLevelsAndShortRepliesNeverTakeTheHumanFloor(){
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  val rows=JSONArray();val receipt=File(i.targetContext.getExternalFilesDir(null),"talk-184-speaker-diversity.json");receipt.delete()
  val began=SystemClock.elapsedRealtime()
  var controlledNoise:android.media.audiofx.NoiseSuppressor?=null
  try{runBlocking{
   withContext(Dispatchers.Main){voice.debugStartLocalAudio()}
   if(InstrumentationRegistry.getArguments().getString("platformNs")=="true"&&!voice.debugAudioSnapshot().optBoolean("nsEnabled")){
    val field=TalkVoiceSession::class.java.getDeclaredField("capture").apply{isAccessible=true}
    val record=field.get(voice) as android.media.AudioRecord
    controlledNoise=android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)
    assertNotNull(controlledNoise);assertEquals(0,controlledNoise!!.setEnabled(true));assertTrue(controlledNoise!!.enabled)
   }
   val selected=voice.debugAudioSnapshot()
   if(selected.optBoolean("aecEnabled")&&android.media.audiofx.NoiseSuppressor.isAvailable())assertTrue("Selected platform speech processing must keep noise suppression enabled",selected.optBoolean("nsEnabled")||controlledNoise?.enabled==true)
   val names=listOf("question-ar.pcm","question-de.pcm","stop-183.pcm","beehive-182.pcm","comedy-182.pcm","science-182.pcm")
   var rendered=0L
   for(gain in listOf(.35,.7,1.0))for(name in names){
    val bytes=talkValidationFixture(name).readBytes()
    val samples=ShortArray(bytes.size/2){((bytes[it*2].toInt()and 255)or(bytes[it*2+1].toInt()shl 8)).toShort()}
    val pcm=ByteArray((samples.size*3/2)*2)
    for(n in 0 until pcm.size/2){val p=n*2.0/3;val a=p.toInt().coerceAtMost(samples.lastIndex);val b=(a+1).coerceAtMost(samples.lastIndex);val value=((samples[a]+(samples[b]-samples[a])*(p-a))*gain).toInt();pcm[n*2]=value.toByte();pcm[n*2+1]=(value shr 8).toByte()}
    withContext(Dispatchers.Main){voice.debugQueueSpeech(pcm)}
    withTimeout(30000){while(voice.debugAudioSnapshot().optBoolean("speaking")||voice.debugRenderedSamples<rendered+pcm.size/2)delay(20)}
    rendered=voice.debugRenderedSamples
    rows.put(JSONObject().put("fixture",name).put("gain",gain).put("rendered",rendered).put("barges",voice.debugLocalBarges))
    receipt.writeText(JSONObject().put("rows",rows).put("audio",voice.debugAudioSnapshot()).toString(2))
    assertEquals("Speaker echo became an activity: $name, gain=$gain",0,voice.debugLocalBarges)
    delay(if(name.startsWith("stop"))1700 else 220)
   }
   delay(1000);assertTrue(voice.debugActivityEvents.isEmpty())
   receipt.writeText(JSONObject().put("durationMs",SystemClock.elapsedRealtime()-began).put("rows",rows).put("audio",voice.debugAudioSnapshot()).toString(2))
  }}finally{receipt.writeText(JSONObject().put("durationMs",SystemClock.elapsedRealtime()-began).put("rows",rows).put("controlledNs",controlledNoise?.enabled).put("audio",voice.debugAudioSnapshot()).put("activity",JSONArray(voice.debugActivityEvents.toList())).put("timeline",JSONArray(voice.debugAudioTimeline.toList())).toString(2));controlledNoise?.release();i.runOnMainSync{voice.suspendSession();activity.finish()}}
 }
}
