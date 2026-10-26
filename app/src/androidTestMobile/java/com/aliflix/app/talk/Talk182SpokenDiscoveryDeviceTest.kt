package com.aliflix.app.talk

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File

/** Owned speech enters the production PCM/VAD path, with no text request,
 * forced turn boundary or follow-up greeting. Actual capture resumes for echo. */
class Talk182SpokenDiscoveryDeviceTest {
 @Test fun spokenResearchRendersBeforeAnySecondUtterance(){
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("spoken182")=="true")
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity();val voice=activity.voice
  val receipts=JSONArray();val receipt=File(i.targetContext.getExternalFilesDir(null),"talk-182-spoken-discovery.json")
  try{runBlocking{
   for(name in listOf("comedy","science","beehive")){
    if(name!="comedy")withContext(Dispatchers.Main){voice.newChat()}
    val interruptions=voice.debugInterruptions
    val pcm=File(i.targetContext.getExternalFilesDir(null),"talk-validation/$name-182.pcm").readBytes()
    val began=SystemClock.elapsedRealtime();val first=voice.state.value.lines.size
    withContext(Dispatchers.Main){voice.debugInjectMicrophonePcm(pcm)}
    withTimeout(100000){
     var settledAt=0L
     while(true){
      val audio=voice.debugAudioSnapshot()
      val settled=voice.debugTurnCompleteAt>=began&&!voice.state.value.sending&&voice.state.value.phase!="Speaking"&&audio.optInt("pendingTools")==0&&!audio.optBoolean("awaitingToolAnswer")
      if(!settled)settledAt=0L else if(settledAt==0L)settledAt=SystemClock.elapsedRealtime()
      if(settledAt>0&&SystemClock.elapsedRealtime()-settledAt>=1200)break
      delay(50)
     }
    }
    val lines=voice.state.value.lines.drop(first)
    val answer=lines.filter{it.role=="assistant"&&!it.stopped}.joinToString(" "){it.text}
    receipts.put(JSONObject().put("fixture",name).put("lines",JSONArray(lines.map{JSONObject().put("role",it.role).put("text",it.text)})).put("firstAudioMs",voice.debugFirstAudioAt-began).put("rendered",voice.debugRenderedSamples).put("audio",voice.debugAudioSnapshot()))
    receipt.writeText(JSONObject().put("turns",receipts).toString(2))
    assertTrue("Real speech must reach transcription",lines.any{it.role=="user"&&it.text.length>15})
    assertTrue("Answer must render without a second utterance: $answer",voice.debugRenderedSamples>0&&voice.debugFirstAudioAt>=began)
    assertTrue("A response must contain substance",answer.length>40)
    assertEquals("Assistant output must use the assistant stream",android.media.AudioAttributes.USAGE_ASSISTANT,voice.debugAudioSnapshot().getInt("outputUsage"))
    assertEquals("The physical speaker cannot interrupt its own response",interruptions,voice.debugInterruptions)
    if(name=="beehive"){
     assertTrue("Developed discussion must go beyond an opening remark: $answer",answer.split(Regex("\\s+")).size>=65)
     val concrete=listOf("colour","color","light","fram","camera","compos","honey","amber","window","shadow","yellow","palette","contrast","close-up","projection","interior","tone")
     assertTrue("Explain concrete visual choices: $answer",concrete.count{answer.contains(it,true)}>=2)
     val before=voice.state.value.lines.size;val followAt=SystemClock.elapsedRealtime()
     withContext(Dispatchers.Main){voice.debugInjectText("And?")}
     withTimeout(60000){while(voice.debugTurnCompleteAt<followAt||voice.state.value.sending||voice.state.value.phase=="Speaking")delay(50)}
     val follow=voice.state.value.lines.drop(before).filter{it.role=="assistant"&&!it.stopped}.joinToString(" "){it.text}
     receipts.put(JSONObject().put("fixture","beehive-ellipsis").put("answer",follow))
     assertTrue("And must develop the same visual discussion: $follow",follow.split(Regex("\\s+")).size>=35&&concrete.any{follow.contains(it,true)})
     assertFalse(Regex("(?i)how can i help|hello|what movie would").containsMatchIn(follow))
    }
    if(name=="comedy"){
     val question=lines.filter{it.role=="user"}.joinToString(" "){it.text}
     assertTrue("A soft negative clause cannot lose its negation: $question",Regex("(?i)avoid|not |exclude").containsMatchIn(question)&&question.contains("classics",true))
    }
   }
  }}finally{
   receipt.writeText(JSONObject().put("turns",receipts).put("audio",JSONArray(voice.debugAudioTimeline.toList())).put("tools",JSONObject(voice.debugToolCalls.toMap())).put("lines",JSONArray(voice.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text)})).put("events",JSONArray(voice.debugWireEvents.toList())).toString(2))
   i.runOnMainSync{voice.suspendSession();activity.finish()}
  }
 }
}
