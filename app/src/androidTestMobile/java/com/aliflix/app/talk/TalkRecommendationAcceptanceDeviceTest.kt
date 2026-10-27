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

/** Opt-in real Gemini/Tavily service and physical AudioTrack acceptance.
 * Owned speech is streamed through automatic VAD, without audioStreamEnd. */
class TalkRecommendationAcceptanceDeviceTest {
    @Test fun substantiveSpeechWorksWithoutGreetingAndContinuesAfterSearch() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveAsk178")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        context.startActivity(Intent(context,TalkVoiceActivity::class.java).putExtra("validationFreshHistory",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var activity:TalkVoiceActivity?=null
        runBlocking { withTimeout(15000) { while(activity==null) {
            instrumentation.runOnMainSync { activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<TalkVoiceActivity>().firstOrNull() }
            delay(50)
        } } }
        val talk=requireNotNull(activity);val session=talk.voice;val turns=JSONArray();val lateBridges=mutableListOf<String>()
        val receipt=File(context.getExternalFilesDir(null),"talk-acceptance-178.json")
        try { runBlocking {
            for((index,prompt)in listOf("movies about gentlemen","Something less violent, but still stylish. Give me two titles.","Search the web for two recent science fiction films about identity.","Recommend two thoughtful mystery films.").withIndex()) {
                val began=SystemClock.elapsedRealtime()
                if(index==0)withContext(Dispatchers.Main) {
                    session.debugInjectUtterance(File(context.getExternalFilesDir(null),"talk-validation/gentlemen.pcm").readBytes(),finishStream=false)
                } else withContext(Dispatchers.Main) { session.debugInjectText(prompt,forceNativeRecommendation=index==3) }
                val inputFinished=SystemClock.elapsedRealtime()
                withTimeout(60000) { while(session.debugRecommendationCount<2||session.debugTurnCompleteAt<began||session.state.value.sending||session.state.value.phase=="Speaking")delay(60) }
                val lastUser=session.state.value.lines.indexOfLast { it.role=="user" }
                val answer=session.state.value.lines.drop(lastUser+1).filter{it.role=="assistant"}.joinToString(" "){it.text}
                turns.put(JSONObject().put("prompt",prompt).put("answer",answer).put("firstAudioMs",session.debugFirstAudioAt-began)
                    .put("responseAfterInputMs",maxOf(0,session.debugFirstAudioAt-inputFinished))
                    .put("totalMs",SystemClock.elapsedRealtime()-began).put("recommendations",session.debugRecommendationCount).put("issue",session.debugRecommendationIssue))
                receipt.writeText(JSONObject().put("turns",turns).put("tools",JSONObject(session.debugToolCalls.toMap())).put("stallRecoveries",session.debugStallRecoveries).toString(2))
                assertTrue("Every selected title is required, not just one or a bridge: $answer",talkAnswerNamesAllTitles(answer,session.state.value.lines.drop(lastUser+1).flatMap{it.titles}.distinctBy{it.key}))
                if(session.debugFirstAudioAt<began||session.debugFirstAudioAt-inputFinished>=10000)lateBridges.add(prompt)
                assertTrue("Audio must render on the physical phone",session.debugRenderedSamples>0)
                assertEquals("No silence recovery should be necessary",0,session.debugStallRecoveries)
                assertEquals("One lookup per request; no model search loop",index+1,session.debugToolCalls["recommendations"])
            }
            assertTrue("Must speak while searching, without another greeting: $lateBridges",lateBridges.isEmpty())
            assertTrue("The app-owned fallback must also complete the same presentation",(session.debugToolCalls["native_recommendations"]?:0)>=1)
        } } finally {
            receipt.writeText(JSONObject().put("turns",turns).put("tools",JSONObject(session.debugToolCalls.toMap())).put("phase",session.state.value.phase)
                .put("lines",JSONArray(session.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text)})).put("issue",session.debugRecommendationIssue).put("connectionIssue",session.debugConnectionIssue)
                .put("presentationIssue",session.debugPresentationIssue).put("pool",JSONArray(session.debugRecommendationPoolKeys)).put("deliveryCorrections",session.debugDeliveryRejections).put("events",JSONArray(session.debugWireEvents.toList())).toString(2))
            instrumentation.runOnMainSync { talk.finish() }
        }
    }
}
