package com.aliflix.app.talk

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File

/** Opt-in real services and AudioTrack on the connected phone, one continuous chat. */
class TalkConversationJourneyDeviceTest {
 @Test fun greetingsGeneralConversationSelectionAndExplicitCardsWorkInOneConversation() {
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveAsk179")=="true")
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
  context.startActivity(Intent(context,TalkVoiceActivity::class.java).putExtra("validationFreshHistory",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  var activity:TalkVoiceActivity?=null
  runBlocking{withTimeout(15000){while(activity==null){i.runOnMainSync{activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<TalkVoiceActivity>().firstOrNull()};delay(50)}}}
  val talk=requireNotNull(activity);val session=talk.voice;val turns=JSONArray()
  val receipt=File(context.getExternalFilesDir(null),"talk-journey-179.json")
  try {runBlocking {
   suspend fun turn(prompt:String,pcm:String?=null,chat:Boolean=false):String {
    val began=SystemClock.elapsedRealtime();val lineCount=session.state.value.lines.size
    withContext(Dispatchers.Main){if(chat){session.setTextMode(true);session.sendText(prompt)}else if(pcm==null)session.debugInjectText(prompt) else session.debugInjectUtterance(File(context.getExternalFilesDir(null),"talk-validation/$pcm.pcm").readBytes(),finishStream=false)}
    withTimeout(65000){while(session.debugTurnCompleteAt<began||session.state.value.sending||session.state.value.phase=="Speaking")delay(60)}
    val answer=session.state.value.lines.drop(lineCount).filter{it.role=="assistant"}.joinToString(" "){it.text}
    turns.put(JSONObject().put("prompt",prompt).put("answer",answer).put("firstAudioMs",session.debugFirstAudioAt-began).put("totalMs",SystemClock.elapsedRealtime()-began).put("cards",JSONArray(session.state.value.cards.visible.map{it.json()})))
    receipt.writeText(JSONObject().put("turns",turns).put("tools",JSONObject(session.debugToolCalls.toMap())).toString(2))
    assertTrue("Every utterance must receive an answer: $prompt",answer.isNotBlank())
    assertFalse("Must not restrict conversation to films: $answer",Regex("(?i)only (?:discuss|respond|talk|answer).*(?:film|movie|series)").containsMatchIn(answer))
    if(!chat)assertTrue("Audio must reach the physical output",session.debugRenderedSamples>0)
    return answer
   }
   turn("Hi","hi")
   turn("Hello","hello")
   val general=turn("Why is the sky blue? Briefly.")
   assertTrue(general.contains("light",true)||general.contains("scatter",true))
   turn("movies about gentlemen","gentlemen")
   val group=session.state.value.cards.visible
   assertEquals("Three visible choices",3,group.size)
   val selected=group[2]
   turn("Tell me briefly why I should watch ${selected.title}.")
   assertEquals(listOf(selected.key),session.state.value.cards.visible.map{it.key})
   turn("What makes its style interesting? Briefly.")
   assertEquals("Same subject keeps its card",listOf(selected.key),session.state.value.cards.visible.map{it.key})
   turn("Show me the movie card for ${selected.title} again.")
   assertEquals(listOf(selected.key),session.state.value.cards.visible.map{it.key})
   turn("Show me the card for Dark, the 2017 TV series.")
   assertEquals(listOf("tv:70523"),session.state.value.cards.visible.map{it.key})
   turn("Let's change subjects. Why do leaves turn red in autumn? Briefly.")
   assertTrue("A different subject dismisses the film",session.state.value.cards.visible.isEmpty())
   turn("Hi",chat=true)
   turn("Show me the card for Dark again.",chat=true)
   assertEquals("Text chat controls the same persistent cards",listOf("tv:70523"),session.state.value.cards.visible.map{it.key})
   assertEquals("No silent stall recovery",0,session.debugStallRecoveries)
  }} finally {
   receipt.writeText(JSONObject().put("turns",turns).put("phase",session.state.value.phase).put("pcmFrames",session.debugPcmFrames).put("events",JSONArray(session.debugWireEvents.toList())).put("lines",JSONArray(session.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text)})).put("tools",JSONObject(session.debugToolCalls.toMap())).put("connectionIssue",session.debugConnectionIssue).toString(2))
   i.runOnMainSync{talk.finish()}
  }
 }
}
