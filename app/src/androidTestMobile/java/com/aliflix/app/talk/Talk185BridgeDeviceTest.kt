package com.aliflix.app.talk

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Talk185BridgeDeviceTest {
 @Test fun aComedyConstraintLookupSpeaksBeforeItsFinalSelection()=checkBridge("Find five brisk comedies where everyone means well but a misunderstanding keeps escalating. Prefer different countries and avoid franchise films.","comedy")
 @Test fun attributedFilmmakingResearchAlsoSpeaksWhileItsEvidenceIsPending()=checkBridge("Look up what Antonioni actually said about the use of red in Red Desert (1964). Separate attributed comments from interpretation.","filmmaking")
 private fun checkBridge(question:String,name:String) {
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  try{runBlocking{
   withContext(Dispatchers.Main){voice.debugInjectText(question)}
   withTimeout(35000){while(voice.state.value.lines.none{it.role=="assistant"&&it.titles.isEmpty()&&talkResearchNoteAllowed(it.text)}||voice.debugFirstAudioAt==0L)delay(25)}
   assertEquals("The authored bridge must pass speech validation","played",voice.debugAudioSnapshot().getString("bridgeBoundary"))
   assertTrue((voice.debugToolCalls["lookup_bridge_accepted"]?:0)>0)
  }}finally{
   File(i.targetContext.getExternalFilesDir(null),"talk-185-bridge-$name.json").writeText(JSONObject().put("audio",voice.debugAudioSnapshot()).put("tools",JSONObject(voice.debugToolCalls.toMap())).put("lines",JSONArray(voice.state.value.lines.map{JSONObject().put("text",it.text).put("titles",JSONArray(it.titles.map{c->c.json()}))})).toString(2))
   i.runOnMainSync{voice.suspendSession();activity.finish()}
  }
 }
}
