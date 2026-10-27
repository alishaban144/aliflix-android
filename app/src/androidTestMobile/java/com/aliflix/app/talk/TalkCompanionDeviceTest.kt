package com.aliflix.app.talk

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aliflix.app.MainActivity
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Physical ASR -> Gemini -> native output, including semantic changes without text-only injection. */
class TalkCompanionDeviceTest {
 @Test fun spokenThemesFollowUpTasteAndAppContext() {
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveCompanion")=="true")
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
  fun resumed():android.app.Activity? {var a:android.app.Activity?=null;i.runOnMainSync {a=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()};return a}
  context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  runBlocking {withTimeout(20000){while(resumed() !is MainActivity)delay(50)}}
  val main=resumed() as MainActivity
  i.runOnMainSync {main.startActivity(Intent(main,TalkVoiceActivity::class.java))}
  runBlocking {withTimeout(15000){while(resumed() !is TalkVoiceActivity)delay(50)}}
  val talk=resumed() as TalkVoiceActivity;val session=talk.voice;val receipt=JSONArray()
  try {runBlocking {
   withTimeout(25000){while(session.state.value.phase!="Listening")delay(50)}
   val fixture=File(context.getExternalFilesDir(null),"talk-validation")
   val turns=if(InstrumentationRegistry.getArguments().getString("companionFocus")=="true")listOf("app","favorites","another") else listOf("shining","follow-up","dark","app","favorites","another")
   for((index,name) in turns.withIndex()) {
    val began=SystemClock.elapsedRealtime()
    withContext(Dispatchers.Main) {
     when(name) {
      "follow-up"->session.debugInjectText("Okay, just tell me more about that atmosphere in one sentence.")
      "another"->session.debugInjectText("Give me one different science fiction recommendation. Briefly explain why it fits me. Do not repeat a movie you already suggested.")
      else->session.debugInjectUtterance(File(fixture,"$name.pcm").readBytes())
     }
    }
    withTimeout(60000){while(session.debugFirstAudioAt==0L||session.debugTurnCompleteAt<session.debugFirstAudioAt||session.state.value.phase=="Speaking")delay(50)}
    File(context.getExternalFilesDir(null),"talk-companion-progress-174.json").writeText(JSONObject().put("turn",name).put("topic",session.state.value.words).put("lines",JSONArray(session.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text)})).toString(2))
    assertEquals("Aoede",session.debugVoice);assertEquals("gemini-3.8-live",session.debugModel)
    assertNotEquals("ALIFLIX",session.state.value.words);assertEquals("en",session.debugResponseLanguage)
    if(name=="shining")assertTrue(session.state.value.words,session.state.value.words.contains("SHINING"))
    if(name=="follow-up")assertEquals(receipt.getJSONObject(0).getString("topic"),session.state.value.words)
    if(name=="app") {assertNotEquals("BRIEFLY",session.state.value.words);assertTrue("App answers must read native feature context",(session.debugToolCalls["aliflix_app"]?:0)>0)}
    if(name=="favorites")assertTrue("Recommendations must read actual taste",(session.debugToolCalls["viewing_context"]?:0)>0)
    if(name=="dark")assertTrue(session.state.value.words,session.state.value.words.contains("DARK"))
    val answer=session.state.value.lines.lastOrNull{it.role=="assistant"}?.text.orEmpty()
    assertTrue(answer,answer.length>30)
    if(name=="favorites"&&InstrumentationRegistry.getArguments().getString("companionReconnect")=="true") {
     assertTrue("Native resumption handle",session.debugResumeAvailable)
     val generation=session.debugReadyGeneration
     val before=(session.javaClass.getDeclaredField("ephemeral").apply{isAccessible=true}.get(session) as JSONObject).getString("session")
     withContext(Dispatchers.Main){session.debugReconnect()}
     withTimeout(25000){while(session.debugReadyGeneration<=generation)delay(50)}
     val after=(session.javaClass.getDeclaredField("ephemeral").apply{isAccessible=true}.get(session) as JSONObject).getString("session")
     assertEquals("Same issued session survives native resumption",before,after)
    }
    receipt.put(JSONObject().put("turn",name).put("topic",session.state.value.words)
     .put("firstAudioMs",session.debugFirstAudioAt-began).put("totalMs",SystemClock.elapsedRealtime()-began)
     .put("voice",session.debugVoice).put("language",session.debugResponseLanguage).put("answer",answer).put("toolCalls",JSONObject(session.debugToolCalls.toMap()))
     .put("input",session.state.value.lines.lastOrNull{it.role=="user"}?.text))
   }
   val data=talkAppContext(context)
   assertEquals("Android mobile",data.getString("platform"));assertTrue(data.getJSONObject("capabilities").getJSONObject("downloads").getBoolean("queuedIsCompleted").not())
   withContext(Dispatchers.Main){talk.finish()}
   withTimeout(10000){while(resumed()!==main)delay(50)};delay(1000)
   assertEquals("Resume",session.state.value.phase);assertFalse(main.isFinishing)
   File(context.getExternalFilesDir(null),"talk-companion-174.json").writeText(receipt.toString(2))
  }} finally {
   fun field(name:String)=runCatching {session.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(session)?.toString()}.getOrNull()
   File(context.getExternalFilesDir(null),"talk-companion-diagnostics-174.json").writeText(JSONObject()
    .put("phase",session.state.value.phase).put("topic",session.state.value.words).put("tools",JSONObject(session.debugToolCalls.toMap()))
    .put("inputText",field("inputText")).put("outputText",field("outputText")).put("lastQuestion",field("lastQuestion"))
    .put("pendingTools",field("tools")).put("firstAudio",session.debugFirstAudioAt).put("turnComplete",session.debugTurnCompleteAt)
    .put("lines",JSONArray(session.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text)})).toString(2))
   i.runOnMainSync {if(!talk.isFinishing)talk.finish()}
  }
 }
}
