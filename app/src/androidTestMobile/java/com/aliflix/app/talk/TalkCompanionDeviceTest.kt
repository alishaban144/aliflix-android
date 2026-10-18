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
   val turns=listOf("shining","follow-up","dark","app","favorites","another")
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
    if(index==0)assertTrue(session.state.value.words,session.state.value.words.contains("SHINING"))
    if(index==1)assertEquals(receipt.getJSONObject(0).getString("topic"),session.state.value.words)
    if(index==3) {assertNotEquals("BRIEFLY",session.state.value.words);assertTrue("App answers must read native feature context",(session.debugToolCalls["aliflix_app"]?:0)>0)}
    if(index==4)assertTrue("Recommendations must read actual taste",(session.debugToolCalls["viewing_context"]?:0)>0)
    if(index==2)assertTrue(session.state.value.words,session.state.value.words.contains("DARK"))
    val answer=session.state.value.lines.lastOrNull{it.role=="assistant"}?.text.orEmpty()
    assertTrue(answer,answer.length>30)
    receipt.put(JSONObject().put("turn",name).put("topic",session.state.value.words)
     .put("firstAudioMs",session.debugFirstAudioAt-began).put("totalMs",SystemClock.elapsedRealtime()-began)
     .put("voice",session.debugVoice).put("language",session.debugResponseLanguage).put("answer",answer).put("toolCalls",JSONObject(session.debugToolCalls.toMap()))
     .put("input",session.state.value.lines.lastOrNull{it.role=="user"}?.text))
   }
   val data=talkAppContext(context)
   assertEquals("Android mobile",data.getString("platform"));assertTrue(data.getJSONArray("features").length()>=9)
   withContext(Dispatchers.Main){talk.finish()}
   withTimeout(10000){while(resumed()!==main)delay(50)};delay(1000)
   assertEquals("Resume",session.state.value.phase);assertFalse(main.isFinishing)
   File(context.getExternalFilesDir(null),"talk-companion-174.json").writeText(receipt.toString(2))
  }} finally {i.runOnMainSync {if(!talk.isFinishing)talk.finish()}}
 }
}
