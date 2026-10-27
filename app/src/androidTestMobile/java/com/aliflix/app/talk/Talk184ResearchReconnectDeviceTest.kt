package com.aliflix.app.talk

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Research ownership uses the real service and speaker. Physical capture is
 * muted here to isolate reconnect/cancellation from nearby environmental speech. */
class Talk184ResearchReconnectDeviceTest {
 @Test fun anInFlightLookupSurvivesAConnectionReplacementAndSpeaksWithoutAnotherGreeting() {
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  val receipt=JSONObject();val started=SystemClock.elapsedRealtime()
  try{runBlocking{
   withContext(Dispatchers.Main){voice.debugInjectText("Find two science-fiction films from 2019 onward built around a single impossible rule. No space battles or franchise entries.",forceNativeRecommendation=true)}
   val before=voice.debugAudioSnapshot();receipt.put("before",before)
   assertTrue("A real lookup must be active before replacing the connection",before.getInt("pendingTools")>0)
   withContext(Dispatchers.Main){voice.debugReconnect()}
   withTimeout(110000){while(true){val s=voice.state.value;if(voice.debugFirstAudioAt>=started&&voice.debugRenderedSamples>24000&&s.lines.any{it.role=="assistant"&&it.text.length>40}&&!s.sending&&s.phase!="Speaking")break;delay(50)}}
   assertTrue("The researched reply must carry actual titles",voice.state.value.cards.visible.isNotEmpty())
   assertTrue(voice.debugAudioSnapshot().getLong("epoch")>before.getLong("epoch"))
   assertTrue(voice.debugAudioSnapshot().getBoolean("nsEnabled"))
  }}finally{
   File(i.targetContext.getExternalFilesDir(null),"talk-184-research-reconnect.json").writeText(receipt.put("durationMs",SystemClock.elapsedRealtime()-started).put("audio",voice.debugAudioSnapshot()).put("tools",JSONObject(voice.debugToolCalls.toMap())).put("lines",JSONArray(voice.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text).put("titles",JSONArray(it.titles.map{c->c.json()}))})).toString(2))
   i.runOnMainSync{voice.suspendSession();activity.finish()}
  }
 }
 @Test fun leavingTheConversationStillCancelsItsLookup() {
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  try{runBlocking{
   withContext(Dispatchers.Main){voice.debugInjectText("Find two quiet science-fiction films about an impossible social rule, without space battles.",forceNativeRecommendation=true)}
   assertTrue(voice.debugAudioSnapshot().getInt("pendingTools")>0)
   val afterQuestion=voice.state.value.lines.size
   withContext(Dispatchers.Main){voice.suspendSession()}
   delay(2000)
   assertEquals(0,voice.debugAudioSnapshot().getInt("pendingTools"));assertFalse(voice.debugOutputPlaying)
   assertTrue(voice.state.value.lines.drop(afterQuestion).none{it.role=="assistant"})
  }}finally{i.runOnMainSync{voice.suspendSession();activity.finish()}}
 }
}
