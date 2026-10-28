package com.aliflix.app.talk

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Verifies actual composed speech during research; capture stays muted so
 * surrounding video cannot become a separate user request in this timing test. */
class Talk184ResearchObservationDeviceTest {
 @Test fun aSlowDiscoveryContributesAUsefulObservationInsteadOfASearchSlogan() {
  checkObservation("Find two restrained science-fiction films about an impossible social rule, with human-scale consequences rather than space battles.","discovery",setOf("recommendations"))
 }
 @Test fun aSceneLookupAlsoContributesContextWhileEvidenceIsRetrieved() {
  checkObservation("Look up the scene with the marionette performance in The Double Life of Veronique (1991), and explain how it changes whose viewpoint we follow. Do not spoil the ending.","scene",setOf("film_knowledge","web_research"))
 }
 private fun checkObservation(question:String,name:String,lookupTools:Set<String>) {
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  val began=SystemClock.elapsedRealtime()
  try{runBlocking{
   withContext(Dispatchers.Main){voice.debugInjectText(question)}
   withTimeout(110000){while(voice.debugFirstAudioAt<began||voice.state.value.sending||voice.state.value.phase=="Speaking"||voice.state.value.lines.none{it.role=="assistant"&&it.titles.isNotEmpty()})delay(50)}
   val delay=voice.debugFirstAudioAt-began
   val notes=voice.state.value.lines.filter{it.role=="assistant"&&it.titles.isEmpty()&&it.text.isNotBlank()}
   if(delay>=8000){assertTrue("Long research must use the model-composed observation",((voice.debugToolCalls["research_note_accepted"]?:0)+(voice.debugToolCalls["lookup_bridge_accepted"]?:0))>0);assertTrue(notes.any{talkResearchNoteAllowed(it.text)})}
   assertFalse(voice.state.value.lines.any{it.text.contains("stories that stay with you",true)})
   assertTrue("The request must use its real research path",lookupTools.any{(voice.debugToolCalls[it]?:0)>0})
  }}finally{
   File(i.targetContext.getExternalFilesDir(null),"talk-184-research-observation-$name.json").writeText(JSONObject().put("firstAudioMs",voice.debugFirstAudioAt-began).put("tools",JSONObject(voice.debugToolCalls.toMap())).put("audio",voice.debugAudioSnapshot()).put("lines",JSONArray(voice.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text).put("titles",JSONArray(it.titles.map{c->c.json()}))})).toString(2))
   i.runOnMainSync{voice.suspendSession();activity.finish()}
  }
 }
}
