package com.aliflix.app.talk

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class Talk193LiveHaloDeviceTest {
 @Test fun readyNetworkConnectionShowsTheAskHaloAndSpokenOutputRetainsEnglish(){
  val i=InstrumentationRegistry.getInstrumentation();assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalk193")=="true")
  val activity=launchFreshTalkActivity();val voice=activity.voice;val receipt=JSONObject()
  fun capture(name:String){i.uiAutomation.takeScreenshot()?.let{b->File(i.targetContext.getExternalFilesDir(null),name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
  try{runBlocking{
   withTimeout(35000){while(voice.state.value.phase!="Listening"||voice.debugAudioSnapshot().optBoolean("speaking"))delay(50)}
   assertTrue(talkVoiceHaloReady(voice.state.value));assertTrue(voice.debugAudioSnapshot().optBoolean("leaseActive"))
   assertTrue(voice.debugAudioSnapshot().optInt("recordSource")>0)
   // Let the 240ms ready-light transition reach its visible state.
   delay(350)
   receipt.put("readyAudio",voice.debugAudioSnapshot());capture("talk193-ask-network-ready.png")
   val id=voice.state.value.conversationId;val from=voice.state.value.lines.size
   val renderedBefore=voice.debugAudioSnapshot().optLong("rendered")
   val pcm=talkValidationFixture("talk193-ask.pcm").readBytes()
   launch(Dispatchers.Main){voice.debugInjectMicrophonePcm(pcm)}
   var shot=false;var unmutedSpeechAt=0L;var peakLevel=0f;var peakOutputEnergy=0.0
   fun sampleSpeech(){
    val state=voice.state.value;val audio=voice.debugAudioSnapshot()
    if(audio.optBoolean("playing")&&audio.optBoolean("speaking")){
     peakLevel=maxOf(peakLevel,state.level);peakOutputEnergy=maxOf(peakOutputEnergy,audio.optDouble("outputEnergy"))
     if(state.phase=="Speaking"&&!state.muted&&unmutedSpeechAt==0L)unmutedSpeechAt=android.os.SystemClock.elapsedRealtime()
     if(!shot&&unmutedSpeechAt>0&&android.os.SystemClock.elapsedRealtime()-unmutedSpeechAt>300&&state.phase=="Speaking"&&!state.muted&&state.level>.03f){capture("talk193-ask-speaking.png");shot=true}
    }
   }
   withTimeout(65000){while(true){
    val state=voice.state.value
    sampleSpeech()
    if(state.answerComplete&&state.lines.drop(from).any{it.role=="assistant"&&it.text.length>35})break
    delay(35)
   }}
   // Text generation may finish before the queued spoken answer starts playing.
   withTimeout(30000){while(voice.debugAudioSnapshot().let{it.optBoolean("speaking")||it.optBoolean("writing")||it.optInt("queued")>0}){sampleSpeech();delay(20)}}
   val answer=voice.state.value.lines.drop(from).last{it.role=="assistant"}.text
   assertTrue(answer,Regex("(?i)past|flashback|earlier|context|backstory").containsMatchIn(answer))
   assertEquals(id,voice.state.value.conversationId);assertEquals("en",voice.debugResponseLanguage)
   assertTrue("The full spoken answer must render",voice.debugAudioSnapshot().optLong("rendered")-renderedBefore>50000)
   receipt.put("answer",answer).put("english",voice.debugResponseLanguage).put("sameConversation",true).put("speakingCapture",shot)
    .put("peakSpeechLevel",peakLevel).put("peakOutputEnergy",peakOutputEnergy)
    .put("finalAudio",voice.debugAudioSnapshot())
   assertTrue("Rendered speech must drive the visible ready halo",shot&&peakOutputEnergy>0.0&&peakLevel>.03f)
  }}finally{
   receipt.put("connectionIssue",voice.debugConnectionIssue)
   File(i.targetContext.getExternalFilesDir(null),"talk193-ask-live.json").writeText(receipt.toString(2))
   i.runOnMainSync{voice.close();activity.finish()}
  }
 }
}
