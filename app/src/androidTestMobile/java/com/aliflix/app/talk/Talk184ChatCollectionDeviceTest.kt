package com.aliflix.app.talk

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real research through text chat, without microphone capture or audio tests. */
class Talk184ChatCollectionDeviceTest {
 @Test fun aSubstantialResearchedCollectionAndRecencyRefinementKeepEarlierCardsStable() {
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  val requests=JSONArray()
  val receipt=File(i.targetContext.getExternalFilesDir(null),"talk-184-chat-collection.json")
  try{runBlocking{
   withContext(Dispatchers.Main){voice.setTextMode(true)}
   suspend fun ask(text:String):TalkLine {
    val count=voice.state.value.lines.size
    requests.put(JSONObject().put("question",text).put("intent",talkConversationIntent(text,voice.state.value.cards.group.size)).put("discovery",talkDiscoveryQuestion(text,voice.state.value.cards.group.isNotEmpty())))
    withContext(Dispatchers.Main){voice.sendText(text)}
    return withTimeout(110000){
     while(true){
      val state=voice.state.value
      val reply=state.lines.drop(count).lastOrNull{it.role=="assistant"&&it.titles.isNotEmpty()}
      if(reply!=null&&state.answerComplete&&!state.sending)return@withTimeout reply
      if(state.answerComplete&&!state.sending&&voice.debugRecommendationIssue.isNotBlank()&&state.lines.drop(count).any{it.role=="assistant"})error("Collection lookup failed: "+voice.debugRecommendationIssue)
      if(state.phase in setOf("Try again later","Reconnect","Audio unavailable"))error("Chat unavailable: ${state.phase}")
      delay(100)
     }
     @Suppress("UNREACHABLE_CODE") error("No collection")
    }
   }
   val first=ask("Give me twelve Japanese animated films from 2005–2025. Exclude Studio Ghibli and franchise sequels. Include unusual independent work alongside larger productions.")
   receipt.writeText(JSONObject().put("first",JSONArray(first.titles.map{it.json()})).put("reply",first.text).toString(2))
   assertTrue("A broad collection must genuinely exceed the three-card voice surface",first.titles.size>=10)
   assertEquals(first.titles.size,first.titles.map{it.key}.distinct().size)
   assertTrue(first.titles.all{it.type==com.aliflix.app.model.MediaType.MOVIE&&(it.year.toIntOrNull()?:0) in 2005..2025})
   val keys=first.titles.map{it.key}
   val second=ask("Only films from 2020 onward. Keep the other constraints.")
   assertTrue(second.titles.isNotEmpty());assertTrue(second.titles.all{(it.year.toIntOrNull()?:0)>=2020})
   assertEquals(keys,voice.state.value.lines.first{it===first||it.text==first.text}.titles.map{it.key})
   receipt.writeText(JSONObject().put("first",JSONArray(first.titles.map{it.json()})).put("refined",JSONArray(second.titles.map{it.json()})).put("reply",second.text).put("microphoneFrames",voice.debugPcmFrames).toString(2))
   assertEquals("Text chat must not capture microphone audio",0,voice.debugPcmFrames)
   i.uiAutomation.takeScreenshot()?.let{bitmap->File(i.targetContext.getExternalFilesDir(null),"talk-184-chat-collection.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
  }}finally{
   File(i.targetContext.getExternalFilesDir(null),"talk-184-chat-state.json").writeText(JSONObject().put("requests",requests).put("phase",voice.state.value.phase).put("sending",voice.state.value.sending).put("answerComplete",voice.state.value.answerComplete)
    .put("tools",JSONObject(voice.debugToolCalls.toMap())).put("lookupIssue",voice.debugRecommendationIssue).put("state",voice.debugAudioSnapshot()).put("lines",JSONArray(voice.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text).put("titles",JSONArray(it.titles.map{card->card.json()}))})).toString(2))
   i.uiAutomation.takeScreenshot()?.let{bitmap->File(i.targetContext.getExternalFilesDir(null),"talk-184-chat-state.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
   i.runOnMainSync{voice.suspendSession();activity.finish()}
  }
 }
}
