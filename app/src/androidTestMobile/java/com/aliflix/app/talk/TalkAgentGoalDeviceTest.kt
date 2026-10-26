package com.aliflix.app.talk

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File

class TalkAgentGoalDeviceTest {
 @Test fun voiceCreatesARealDurableProgrammeThatFinishesAfterTalkStops(){
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveAgent182")=="true")
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity();val voice=activity.voice
  val taskName="Validation Night "+java.util.UUID.randomUUID().toString().take(6)
  val existing=voice.agent.missions.value.map{it.optString("id")}.toSet()
  var created:JSONObject?=null
  try{runBlocking{
   val began=SystemClock.elapsedRealtime()
   withContext(Dispatchers.Main){voice.debugInjectText("Create a persistent task named $taskName. Programme Primer and The Vast of Night together, checking their actual runtimes first. Fit both plus a five-minute break into 180 minutes. Do not add or remove anything from My List or Favorites. Keep the task working when this conversation closes.")}
   withTimeout(65000){while(voice.debugTurnCompleteAt<began||voice.state.value.sending||voice.state.value.phase=="Speaking")delay(50)}
   assertTrue("This goal must actually use the agent workspace",(voice.debugToolCalls["agent_workspace"]?:0)>0)
   created=voice.agent.missions.value.firstOrNull{it.optString("id") !in existing&&it.optString("status")!="cancelled"&&it.optJSONObject("plan")?.optString("name")?.contains(taskName,true)==true}
   assertNotNull("A spoken promise without a persisted task is a failure",created)
   val id=created!!.getString("id")
   i.runOnMainSync{voice.suspendSession()}
   var result=created!!
   withTimeout(60000){while(result.optString("status") in setOf("queued","running")){
    delay(3000);result=voice.agent.command(JSONObject().put("operation","read").put("id",id))
   }}
   assertEquals(result.toString(),"ready",result.optString("status"))
   val steps=result.getJSONObject("plan").getJSONArray("steps");val output=result.getJSONObject("outputs").getJSONObject(steps.getJSONObject(steps.length()-1).getString("id"))
   assertEquals(2,output.getJSONArray("results").length());assertEquals(173,output.getInt("minutes"))
   File(i.targetContext.getExternalFilesDir(null),"talk-182-agent-goal.json").writeText(JSONObject().put("mission",result).put("continuedWithVoiceStopped",true).put("tools",JSONObject(voice.debugToolCalls.toMap())).toString(2))
  }}finally{
   File(i.targetContext.getExternalFilesDir(null),"talk-182-agent-trace.json").writeText(JSONObject().put("name",taskName).put("missions",org.json.JSONArray(voice.agent.missions.value)).put("tools",JSONObject(voice.debugToolCalls.toMap())).put("lines",org.json.JSONArray(voice.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text)})).toString(2))

   created?.let{mission->runBlocking{val read=voice.agent.command(JSONObject().put("operation","read").put("id",mission.getString("id")));voice.agent.command(JSONObject().put("operation","cancel").put("id",mission.getString("id")).put("expectedRevision",read.getInt("revision")))}}
   i.runOnMainSync{voice.suspendSession();activity.finish()}
  }
 }
}
