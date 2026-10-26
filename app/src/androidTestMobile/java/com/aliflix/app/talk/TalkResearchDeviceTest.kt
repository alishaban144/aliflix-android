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

/** Opt-in physical-device test. Uses the real authenticated Worker, Gemini
 * speech engine, asynchronous tool dispatcher and native AudioTrack. */
class TalkResearchDeviceTest {
 @Test fun continuousResearchSpeechAndVoiceIdentity(){
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveResearch")=="true")
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
  fun activity():android.app.Activity?{var a:android.app.Activity?=null;i.runOnMainSync{a=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()};return a}
  context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  runBlocking{withTimeout(20000){while(activity() !is MainActivity)delay(50)}}
  val main=activity() as MainActivity;i.runOnMainSync{main.startActivity(Intent(main,TalkVoiceActivity::class.java).putExtra("validationFreshHistory",true))}
  runBlocking{withTimeout(20000){while(activity() !is TalkVoiceActivity)delay(50)}}
  val talk=activity() as TalkVoiceActivity;val session=talk.voice;val receipt=JSONArray()
  val originalVoice=session.state.value.voice
  File(context.getExternalFilesDir(null),"talk-research-176.json").writeText(JSONObject().put("turns",receipt).put("inProgress",true).toString())
  val continuityOnly=InstrumentationRegistry.getArguments().getString("voiceContinuityOnly")=="true"
  val prompts=listOf(
   "In one sentence, how can Aliflix help me find films based on my Favorites?",
   "Use web_research to verify current noteworthy science fiction film releases in 2026. Name two with their exact years, briefly.",
   if(continuityOnly)"Compare Blade Runner 2049 and Moon as identity-themed science fiction, in two sentences." else "Use recommendations for a broad, varied selection of science fiction films exploring personal identity. Offer just two choices, briefly.",
   "How do those two choices differ in atmosphere? Keep it to one sentence.",
   "Explain the ending of The Shining (1980), in three sentences.",
   "What does the final photograph mean? Distinguish interpretation from confirmed facts in two sentences.",
   "Antworte ab jetzt auf Deutsch. Erkläre kurz Dark Staffel 2 Folge 1, ohne Informationen aus späteren Folgen.",
   "أجب بالعربية الآن. اشرح فكرة السفر عبر الزمن في Dark دون حرق أي شيء بعد الموسم الثاني الحلقة الأولى. جملتان فقط.",
   "Answer in English again. In one sentence, which film with a final photograph did we discuss earlier?",
   "Tell me a detailed, spoiler-free description of the atmosphere and filmmaking of The Shining in about one hundred words."
  )
  try {runBlocking {
   withTimeout(25000){while(session.state.value.phase!="Listening")delay(50)}
   if(session.state.value.voice!="Aoede"){withContext(Dispatchers.Main){session.selectVoice("Aoede")};withTimeout(25000){while(session.state.value.phase!="Listening")delay(50)}}
   if(InstrumentationRegistry.getArguments().getString("voiceOnly")!="true") for((n,prompt)in prompts.withIndex()){
    val began=SystemClock.elapsedRealtime();val underruns=session.debugUnderruns
    withContext(Dispatchers.Main){session.debugInjectText(prompt)}
    if(n==9){
     withTimeout(35000){while(session.debugFirstAudioAt==0L)delay(25)}
     delay(700);val before=session.debugInterruptions
     val pcm=File(context.getExternalFilesDir(null),"talk-validation/app.pcm")
     assertTrue("Owned speech fixture must exist",pcm.isFile)
     withContext(Dispatchers.Main){session.debugInjectUtterance(pcm.readBytes())}
     withTimeout(20000){while(session.debugInterruptions<=before)delay(25)}
    }
    withTimeout(90000){while(session.debugFirstAudioAt==0L||session.debugTurnCompleteAt<session.debugFirstAudioAt||session.state.value.phase=="Speaking")delay(50)}
    val answer=session.state.value.lines.lastOrNull{it.role=="assistant"}?.text.orEmpty()
    assertTrue("Turn $n: $answer",answer.length>25);assertEquals("Aoede",session.debugVoice);assertEquals("gemini-3.8-live",session.debugModel)
    if(n==7)assertTrue("Arabic answer",answer.count{it in '\u0600'..'\u06ff'}>15)
    receipt.put(JSONObject().put("turn",n+1).put("firstAudioMs",session.debugFirstAudioAt-began).put("completeMs",SystemClock.elapsedRealtime()-began).put("underruns",session.debugUnderruns-underruns).put("voice",session.debugVoice).put("answer",answer).put("topic",session.state.value.words))
    File(context.getExternalFilesDir(null),"talk-research-176.json").writeText(JSONObject().put("turns",receipt).put("tools",JSONObject(session.debugToolCalls as Map<*,*>)).put("webEvidenceCount",session.debugWebEvidenceCount).put("recommendationCount",session.debugRecommendationCount).put("episodeEvidence",session.debugEpisodeEvidence).put("recommendationScenarioExercised",!continuityOnly).put("connectionIssue",session.debugConnectionIssue).put("knowledgeRequest",session.debugKnowledgeRequest).put("stallRecoveries",session.debugStallRecoveries).toString(2))
    if(n==8)assertTrue("Retained film context: $answer",answer.contains("Shining",true))
    if(n==4)assertTrue("Substantive ending: $answer",answer.contains("Jack",true)&&Regex("(?i)1921|freez|frozen|maze|Danny|Wendy").containsMatchIn(answer))
    if(n==6)assertFalse("Wrong source year: $answer",answer.contains("2052"))
    assertEquals("Sticky language on turn ${n+1}",when(n){6->"de";7->"ar";else->"en"},session.debugResponseLanguage)
    if(n==6){assertTrue("Requested episode evidence: $answer; ${session.debugEpisodeEvidence.take(240)}",session.debugEpisodeEvidence.contains("wikipedia-episode-")&&session.debugEpisodeEvidence.contains("2053"));assertFalse("No later Claudia return: $answer",Regex("(?i)Claudia.{0,40}(?:kehrt|reist|kommt).{0,25}2020").containsMatchIn(answer))}
    if(n==5){val ready=session.debugReadyGeneration;withContext(Dispatchers.Main){session.debugReconnect()};withTimeout(25000){while(session.debugReadyGeneration<=ready)delay(50)}}
   }
   if(InstrumentationRegistry.getArguments().getString("voiceOnly")!="true") {
   assertTrue("Real web research tool",(session.debugToolCalls["web_research"]?:0)>0)
   if(!continuityOnly)assertTrue("Shared recommendation tool",(session.debugToolCalls["recommendations"]?:0)>0)
   assertTrue("Actual Tavily response",session.debugWebEvidenceCount>0)
   if(!continuityOnly)assertTrue("At least 20 independently verified results",session.debugRecommendationCount>=20)
   }
   val ready=session.debugReadyGeneration;withContext(Dispatchers.Main){session.selectVoice("Kore")};withTimeout(25000){while(session.debugReadyGeneration<=ready)delay(50)}
   withContext(Dispatchers.Main){session.debugInjectText("In one sentence, continue our discussion of The Shining's atmosphere.")}
   withTimeout(35000){while(session.debugFirstAudioAt==0L||session.debugTurnCompleteAt<session.debugFirstAudioAt||session.state.value.phase=="Speaking")delay(50)}
   assertEquals("Kore",session.debugVoice)
   val resumed=session.debugReadyGeneration;withContext(Dispatchers.Main){session.debugReconnect()};withTimeout(25000){while(session.debugReadyGeneration<=resumed)delay(50)};assertEquals("Kore",session.debugVoice)
  }}finally{
   File(context.getExternalFilesDir(null),"talk-research-last-state-176.json").writeText(JSONObject().put("phase",session.state.value.phase).put("tools",JSONObject(session.debugToolCalls as Map<*,*>)).put("lineCount",session.state.value.lines.size).put("firstAudioAt",session.debugFirstAudioAt).put("completeAt",session.debugTurnCompleteAt).put("connectionIssue",session.debugConnectionIssue).toString())
   context.getSharedPreferences("aliflix-talk",0).edit().putString("voice",originalVoice).apply()
   i.runOnMainSync{talk.finish()};runBlocking{withTimeout(12000){while(activity() !is MainActivity)delay(50)}}
  }
 }
}
