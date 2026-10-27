@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.talk

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.downloads.OfflineDownloads
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File

class Talk183LiveExperienceDeviceTest {
 private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
 private suspend fun settled(voice:TalkVoiceSession,after:Long,timeout:Long=80000){withTimeout(timeout){while(voice.debugTurnCompleteAt<after||voice.state.value.sending||voice.debugAudioSnapshot().optBoolean("speaking"))delay(40)}}
 private fun receipt(name:String,voice:TalkVoiceSession){
  instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->File(instrumentation.targetContext.getExternalFilesDir(null),name.replace(".json",".png")).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
  File(instrumentation.targetContext.getExternalFilesDir(null),name).writeText(JSONObject()
  .put("audio",voice.debugAudioSnapshot()).put("connection",voice.debugConnectionIssue).put("selectedVoice",voice.state.value.voice).put("connectedVoice",voice.debugVoice)
  .put("tools",JSONObject(voice.debugToolCalls.toMap())).put("activity",JSONArray(voice.debugActivityEvents.toList())).put("interruptions",voice.debugInterruptions).put("unsolicitedInterruptions",voice.debugUnsolicitedInterruptions)
  .put("events",JSONArray(voice.debugWireEvents.toList())).put("lines",JSONArray(voice.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text).put("stopped",it.stopped)}))
  .put("downloads",JSONArray(voice.downloadWork.jobs.value.map{it.json()})).toString(2))
 }
 @Test fun aFreshGreetingAndVoiceChangeUseRealAudioAndDiscussedTitlesHaveCards(){
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("talk183Experience")=="true")
  val activity=launchFreshTalkActivity();val voice=activity.voice
  val preferences=activity.getSharedPreferences("aliflix-talk",0);val original=preferences.getString("voice","Aoede")!!
  try{runBlocking{
   withTimeout(30000){while(voice.debugRenderedSamples<=0||voice.state.value.lines.none{it.role=="assistant"})delay(50)}
   val greeting=voice.state.value.lines.first{it.role=="assistant"}.text
   assertTrue("A fresh call composes a short welcome",greeting.isNotBlank()&&greeting.length<220)
   assertTrue(voice.state.value.lines.none{it.role=="user"})
   settled(voice,0)
   delay(2000)
   val changeAt=SystemClock.elapsedRealtime();val selected=if(original=="Charon")"Kore" else "Charon"
   withContext(Dispatchers.Main){voice.debugInjectText("Change your voice to $selected.")}
   withTimeout(35000){while(voice.debugVoice!=selected||voice.state.value.voice!=selected)delay(40)}
   settled(voice,changeAt)
   assertTrue("Acknowledgement must render in the newly configured voice",voice.debugRenderedSamples>0)
   val before=SystemClock.elapsedRealtime()
   withContext(Dispatchers.Main){voice.debugInjectText("Discuss the visual composition of The Color of Pomegranates, without plot spoilers. Give two concrete observations.")}
   settled(voice,before)
   assertTrue("Every discussed film needs its real card",voice.state.value.cards.visible.any{it.title.contains("Pomegranate",true)||it.originalTitle?.contains("Նռան")==true})
   assertEquals("No microphone activity means no self interruption",0,voice.debugInterruptions)
  }}finally{receipt("talk-183-live-experience.json",voice);instrumentation.runOnMainSync{voice.suspendSession();preferences.edit().putString("voice",original).apply();activity.finish()}}
 }
 @Test fun anExactConstrainedDownloadKeepsWorkingAcrossAnotherConversationTurn(){
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("talk183Experience")=="true")
  val store=OfflineDownloads.get(instrumentation.targetContext)
  Assume.assumeFalse("Never replace the viewer's existing episode",store.entries.value.any{it.selection.media.id==615&&it.selection.seasonNumber==1&&it.selection.episodeNumber==1})
  val activity=launchFreshTalkActivity();val voice=activity.voice;var created:String?=null
  try{runBlocking{
   withTimeout(25000){while(voice.debugReadyGeneration==0L)delay(50)}
   var began=SystemClock.elapsedRealtime()
   withContext(Dispatchers.Main){voice.debugInjectText("Download Futurama season 1 episode 1 in the lowest quality with Arabic subtitles. Keep the preparation here in Talk.")}
   withTimeout(45000){while(created==null){created=voice.downloadWork.jobs.value.firstOrNull{it.card.title.equals("Futurama",true)&&it.season==1&&it.episode==1&&it.active}?.id;delay(50)}}
   settled(voice,began)
   val task=voice.downloadWork.jobs.value.single{it.id==created}
   assertEquals("LOW",task.quality);assertEquals("ar",task.subtitles)
   began=SystemClock.elapsedRealtime()
   withContext(Dispatchers.Main){voice.debugInjectText("While that runs, is a paradox the same as an inconsistency? Answer in two sentences.")}
   settled(voice,began)
   assertTrue("The next voice turn cannot cancel explicit background work",voice.downloadWork.jobs.value.single{it.id==created}.stage!="Cancelled")
   withTimeout(150000){while(voice.downloadWork.jobs.value.single{it.id==created}.stage in setOf("Queued","Resolving","Captions","Starting"))delay(200)}
   val result=voice.downloadWork.jobs.value.single{it.id==created}
   assertTrue("A real provider must enqueue the exact requested tracks, otherwise expose its specific failure: ${result.stage} / ${result.detail}",result.stage in setOf("Downloading","Waiting for network","Completed"))
   withTimeout(30000){while(store.entries.value.none{it.id==created&&it.downloadedBytes>0})delay(100)}
   val saved=store.entries.value.single{it.id==created}
   assertEquals("ar",saved.playback.subtitleLanguage.lowercase())
   assertTrue("Arabic captions must be present, not just requested",saved.playback.subtitlesVtt.isNotBlank()||saved.playback.preferEmbeddedSubtitles)
   File(instrumentation.targetContext.getExternalFilesDir(null),"talk-183-download-native.json").writeText(JSONObject().put("id",saved.id).put("quality",saved.quality).put("subtitleLanguage",saved.playback.subtitleLanguage)
    .put("captionCharacters",saved.playback.subtitlesVtt.length).put("embedded",saved.playback.preferEmbeddedSubtitles).put("bytes",saved.downloadedBytes).put("percent",saved.percent).put("state",saved.download.state).toString(2))
  }}finally{receipt("talk-183-background-download.json",voice);instrumentation.runOnMainSync{created?.let{voice.downloadWork.cancel(it)};voice.suspendSession();activity.finish()}}
 }
}
