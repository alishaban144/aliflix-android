package com.aliflix.app.talk

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aliflix.app.MainActivity
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File

/** Opt-in factual regression for the actual episode leakage observed on Pixel. */
class TalkEpisodeGroundingDeviceTest {
 @Test fun darkSecondSeasonFirstEpisodeStaysInsideRetrievedEvidence(){
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveEpisode")=="true")
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  fun activity():android.app.Activity?{var a:android.app.Activity?=null;i.runOnMainSync{a=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()};return a}
  c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  runBlocking{withTimeout(15000){while(activity() !is MainActivity)delay(50)}}
  val main=activity() as MainActivity
  i.runOnMainSync{main.startActivity(Intent(c,TalkVoiceActivity::class.java).putExtra("validationFreshHistory",true))}
  runBlocking{withTimeout(15000){while(activity() !is TalkVoiceActivity)delay(50)}}
  val talk=activity() as TalkVoiceActivity;val session=talk.voice
  try{runBlocking{
   withTimeout(25000){while(session.state.value.phase!="Listening")delay(50)}
   withContext(Dispatchers.Main){session.debugInjectText("Antworte auf Deutsch. Erkläre kurz Dark Staffel 2 Folge 1, ohne Informationen aus späteren Folgen.")}
   withTimeout(50000){while(session.debugFirstAudioAt==0L||session.debugTurnCompleteAt<session.debugFirstAudioAt||session.state.value.phase=="Speaking")delay(50)}
   val answer=session.state.value.lines.lastOrNull{it.role=="assistant"}?.text.orEmpty()
   File(c.getExternalFilesDir(null),"talk-episode-grounding-176.json").writeText(JSONObject().put("answer",answer).put("firstAudioAt",session.debugFirstAudioAt).put("evidenceAppliedAt",session.debugKnowledgeAppliedAt).put("request",session.debugKnowledgeRequest).put("evidence",session.debugEpisodeEvidence).toString(2))
   assertTrue("Verified episode row",session.debugEpisodeEvidence.contains("wikipedia-episode-")&&session.debugEpisodeEvidence.contains("2053"))
   assertTrue("Substantive recap: $answer",answer.length>70)
   assertFalse("Source character names: $answer",answer.contains("Johanna",true))
   assertFalse("Wrong/future timeline: $answer",Regex("2052|1987|1954").containsMatchIn(answer))
   assertFalse("Future Claudia meeting: $answer",Regex("(?i)Claudia.{0,60}(?:älter|Bibliothek|reist|trifft)").containsMatchIn(answer))
  }}finally{i.runOnMainSync{talk.finish()};runBlocking{withTimeout(12000){while(activity() !is MainActivity)delay(50)}}}
 }
}
