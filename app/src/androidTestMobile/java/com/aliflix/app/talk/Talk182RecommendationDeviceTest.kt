package com.aliflix.app.talk

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File

class Talk182RecommendationDeviceTest {
 @Test fun difficultComedyAndScienceFictionSpeakWithoutAWakeUpAndKeepTheirCommittedCards(){
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("live182")=="true")
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity();val session=activity.voice
  val output=File(i.targetContext.getExternalFilesDir(null),"talk-182-recommendations.json");val turns=JSONArray()
  try{runBlocking{
   val groups=mutableListOf<Set<String>>()
   for(prompt in listOf(
    "Find two deadpan comedies about people trapped in pointless institutions, not broad slapstick. One should be outside English-language cinema. Do not choose Brazil or Office Space.",
    "Completely change the mood: two small-scale science-fiction films about a single impossible rule, no space battles, released since 2018. Do not reuse either comedy."
   )){
    val began=SystemClock.elapsedRealtime()
    withContext(Dispatchers.Main){session.debugInjectText(prompt)}
    withTimeout(75000){while(session.debugTurnCompleteAt<began||session.state.value.sending||session.state.value.phase=="Speaking")delay(50)}
    val last=session.state.value.lines.indexOfLast{it.role=="user"};val answers=session.state.value.lines.drop(last+1).filter{it.role=="assistant"&&!it.stopped}
    val answer=answers.joinToString(" "){it.text};val cards=answers.flatMap{it.titles}.distinctBy{it.key}
    turns.put(JSONObject().put("prompt",prompt).put("answer",answer).put("cards",JSONArray(cards.map{it.json()})).put("firstAudioMs",session.debugFirstAudioAt-began).put("renderedSamples",session.debugRenderedSamples)
     .put("issue",session.debugRecommendationIssue).put("deliveryRejections",session.debugDeliveryRejections))
    output.writeText(JSONObject().put("turns",turns).toString(2))
    assertEquals("The user asked for exactly two choices: $answer",2,cards.size)
    assertTrue("Speech and visible cards must agree: $answer",talkAnswerNamesAllTitles(answer,cards))
    assertTrue("Must render without a follow-up greeting",session.debugRenderedSamples>0)
    assertTrue("An initial audible bridge must arrive during research",session.debugFirstAudioAt>=began&&session.debugFirstAudioAt-began<15000)
    if(groups.isNotEmpty()){assertTrue("A new task cannot leak the preceding comedy choices",cards.none{it.key in groups.last()});assertTrue(cards.all{it.year.toInt()>=2018})}
    groups.add(cards.map{it.key}.toSet())
   }
  }}finally{
   output.writeText(JSONObject().put("turns",turns).put("phase",session.state.value.phase).put("lines",JSONArray(session.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text)})).put("events",JSONArray(session.debugWireEvents.toList())).put("tools",JSONObject(session.debugToolCalls.toMap())).put("issue",session.debugRecommendationIssue).toString(2))
   i.runOnMainSync{session.suspendSession();activity.finish()}
  }
 }
}
