package com.aliflix.app.talk

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File

/** Opt-in hard journeys on the connected phone. Synthetic requests do not enter
 * the real archive. Speaker-echo coverage deliberately uses live AEC capture. */
class Talk182JourneyDeviceTest {
 @Test fun substantiveEllipsisAndSpeakerEchoStayOnTheSameConversation(){
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("live182")=="true")
  val context=instrumentation.targetContext
  val receipt=File(context.getExternalFilesDir(null),"talk-182-journey.json")
  val turns=JSONArray()
  val activity=launchFreshTalkActivity()
  run {
   val voice=activity.voice
   try{runBlocking{
    suspend fun turn(prompt:String,echo:Boolean=false):String{
     val began=SystemClock.elapsedRealtime()
     withContext(Dispatchers.Main){voice.debugInjectText(prompt)}
     if(echo){
      withTimeout(25000){while(voice.state.value.phase!="Speaking")delay(30)}
      withContext(Dispatchers.Main){if(voice.state.value.muted)voice.toggleMute()}
     }
     withTimeout(65000){while(voice.debugTurnCompleteAt<began||voice.state.value.sending||voice.state.value.phase=="Speaking")delay(50)}
     val lines=voice.state.value.lines
     val last=lines.indexOfLast{it.role=="user"}
     val answer=lines.drop(last+1).filter{it.role=="assistant"&&!it.stopped}.joinToString(" "){it.text}
     turns.put(JSONObject().put("prompt",prompt).put("answer",answer).put("renderedSamples",voice.debugRenderedSamples).put("elapsedMs",SystemClock.elapsedRealtime()-began)
      .put("interruptions",voice.debugInterruptions).put("localBarges",voice.debugLocalBarges))
     receipt.writeText(JSONObject().put("turns",turns).toString(2))
     assertTrue("Substantive reply required: $answer",answer.length>=45)
     assertTrue("Audio must actually render without another utterance",voice.debugRenderedSamples>0)
     return answer
    }
    val baseline=voice.debugInterruptions
    val first=turn("Discuss the visual storytelling and use of silence in 3-Iron, without revealing plot events. Give me a developed answer, not a two-word verdict.",echo=true)
    assertTrue(first.split(' ').size>=35)
    assertEquals("The physical speaker must not interrupt itself",baseline,voice.debugInterruptions)
    assertEquals("Echo is not a human barge-in",0,voice.debugLocalBarges)
    val follow=turn("And?")
    assertFalse("An elliptical continuation is not a new greeting",Regex("(?i)how can i help|hello|what movie would").containsMatchIn(follow))
    assertTrue("Continue the established subject",Regex("(?i)silence|visual|dialogue|3.iron|kim|storytelling|gesture|frame|camera|symbol|props|movement").containsMatchIn(follow))
    turn("Now compare Primer with The Vast of Night for a tired viewer who has exactly 90 minutes. Pick from those two only; no other suggestions.")
    val tools=voice.debugToolCalls.toMap()
    assertEquals("A closed decision must not become a recommendation search",0,tools["recommendations"]?:0)
    val stopFixture=File(context.getExternalFilesDir(null),"talk-validation/interrupt182.pcm")
    assertTrue("Owned interruption fixture required",stopFixture.exists())
    val interruptedBefore=voice.debugInterruptions;val localBefore=voice.debugLocalBarges
    withContext(Dispatchers.Main){voice.debugInjectText("Give a developed explanation of how cinematic sound shapes a viewer's sense of space. Keep going for several paragraphs.")}
    withTimeout(20000){while(voice.state.value.phase!="Speaking")delay(30)}
    delay(1500)
    withContext(Dispatchers.Main){voice.debugInjectDuplexUtterance(stopFixture.readBytes())}
    withTimeout(8000){while(voice.state.value.phase=="Speaking")delay(50)}
    assertTrue("Real speech through the production duplex gate must pause output",voice.debugLocalBarges>localBefore)
    assertTrue("The live service must recognize the interruption",voice.debugInterruptions>interruptedBefore)
    turns.put(JSONObject().put("case","injected near-end speech through neural duplex gate").put("localBarges",voice.debugLocalBarges-localBefore).put("interruptions",voice.debugInterruptions-interruptedBefore))
   }}finally{
    receipt.writeText(JSONObject().put("turns",turns).put("events",JSONArray(voice.debugWireEvents.toList())).put("inputTrace",JSONArray(voice.debugInputTrace.toList())).put("tools",JSONObject(voice.debugToolCalls.toMap())).toString(2))
    instrumentation.runOnMainSync{voice.suspendSession();activity.finish()}
   }
  }
 }
}
