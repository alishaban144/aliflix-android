package com.aliflix.app.talk

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aliflix.app.MainActivity
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Direct lifecycle observation avoids waiting for an animated screen to idle. */
class TalkExitDeviceTest {
 @Test fun automaticListeningAndExitReturnToTheExistingApp() {
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalkExit")=="true")
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val context=instrumentation.targetContext
  fun resumed():android.app.Activity? {
   var activity:android.app.Activity?=null
   instrumentation.runOnMainSync {activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()}
   return activity
  }
  context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  runBlocking {withTimeout(20000){while(resumed() !is MainActivity)delay(50)}}
  val main=resumed() as MainActivity
  instrumentation.runOnMainSync {main.startActivity(Intent(main,TalkVoiceActivity::class.java))}
  runBlocking {withTimeout(15000){while(resumed() !is TalkVoiceActivity)delay(50)}}
  val talk=resumed() as TalkVoiceActivity
  lateinit var session:TalkVoiceSession
  instrumentation.runOnMainSync {session=talk.voice}
  val receipt=JSONArray()
  try {runBlocking {
   withTimeout(25000){while(session.state.value.phase!="Listening")delay(50)}
   for((index,prompt) in listOf("Let's discuss The Shining (1980). Describe its atmosphere in one sentence without plot spoilers.","Okay, just continue about that same atmosphere in one sentence.","Why is Dark a German-language series? Answer briefly, with no plot spoilers.","Please answer in German now, one sentence about Dark.").withIndex()) {
    val began=android.os.SystemClock.elapsedRealtime()
    withContext(Dispatchers.Main){session.debugInjectText(prompt)}
    withTimeout(30000){while(session.debugFirstAudioAt==0L||session.debugTurnCompleteAt<session.debugFirstAudioAt||session.state.value.phase=="Speaking")delay(50)}
    assertEquals("Aoede",session.debugVoice);assertEquals("gemini-3.8-live",session.debugModel)
    assertNotEquals("ALIFLIX",session.state.value.words)
    assertEquals(if(index==3)"de" else "en",session.debugResponseLanguage)
    if(index>=2)assertTrue("New named subject must replace old topic",session.state.value.words.contains("DARK"))
    val answer=session.state.value.lines.lastOrNull{it.role=="assistant"}?.text.orEmpty()
    assertTrue(answer,if(index==3)Regex("(?i)\\b(ist|eine|die|der|und|wurde)\\b").containsMatchIn(answer)
        else Regex("(?i)\\b(the|its|is|and|was|because|it)\\b").containsMatchIn(answer))
    receipt.put(JSONObject().put("topic",session.state.value.words).put("firstAudioMs",session.debugFirstAudioAt-began).put("voice",session.debugVoice).put("language",session.debugResponseLanguage).put("answer",answer))
   }
   assertEquals(receipt.getJSONObject(0).getString("topic"),receipt.getJSONObject(1).getString("topic"))
   withContext(Dispatchers.Main){talk.finish()}
   withTimeout(10000){while(resumed()!==main)delay(50)}
   delay(1700)
   assertEquals("Resume",session.state.value.phase)
   assertEquals(0f,session.state.value.level)
   instrumentation.runOnMainSync {assertFalse(main.isFinishing);assertFalse(main.isDestroyed)}
   File(context.getExternalFilesDir(null),"talk-exit-172.json").writeText(receipt.toString(2))
  }} finally {instrumentation.runOnMainSync {if(!talk.isFinishing)talk.finish()}}
 }
}
