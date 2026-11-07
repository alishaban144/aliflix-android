package com.aliflix.app.talk

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Talk196CardsDeviceTest {
 @Test fun namedTitlesAppearAutomaticallyAlongsideARealTypedAnswer(){
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  val receipt=JSONObject()
  try{runBlocking {
   withContext(Dispatchers.Main){
    voice.setTextMode(true)
    voice.seedTitle(JSONObject().put("id",329865).put("type","movie").put("title","Arrival").put("year","2016"))
   }
   val question="What are The Boy and the Heron and Project Hail Mary about? One sentence for each, mentioning their names."
   receipt.put("question",question)
   val began=android.os.SystemClock.elapsedRealtime()
   withContext(Dispatchers.Main){voice.sendText(question)}
   val reply=withTimeout(100000){while(true){
    val state=voice.state.value
    val answer=state.lines.lastOrNull{it.role=="assistant"}
    if(answer!=null&&state.answerComplete&&!state.sending&&answer.titles.any{it.title.contains("Heron",true)}&&answer.titles.any{it.title.contains("Hail Mary",true)})return@withTimeout answer
    if(state.phase in setOf("Try again later","Reconnect","Reply unavailable"))error("Ask unavailable: ${state.phase}")
    delay(75)
   };@Suppress("UNREACHABLE_CODE")error("No answer")}
   receipt.put("answer",reply.text).put("elapsedMs",android.os.SystemClock.elapsedRealtime()-began).put("tools",JSONObject(voice.debugToolCalls.toMap()))
    .put("cards",JSONArray(reply.titles.map{it.json()})).put("microphoneFrames",voice.debugPcmFrames)
   assertTrue(reply.text.contains("Heron",true));assertTrue(reply.text.contains("Hail Mary",true))
   assertTrue("Every discussed title must have its canonical poster",reply.titles.all{!it.posterPath.isNullOrBlank()})
   assertEquals(0,voice.debugPcmFrames)
   val known=voice.state.value.cards.known
   val named=talkAnswerTitleNames(reply.text,known)
   for(name in named)assertTrue("Missing card for $name",reply.titles.any{card->listOfNotNull(card.title,card.originalTitle).any{it.equals(name,true)}})
   withTimeout(8000){while(reply.titles.any{voice.debugCardBounds[it.key]?.let{bounds->bounds.width>50&&bounds.height>100}!=true})delay(50)}
   delay(350) // Allow the composed poster row to reach the display before capture.
   receipt.put("renderedCardBounds",JSONObject(voice.debugCardBounds.mapValues{it.value.toString()}))
   i.uiAutomation.takeScreenshot()?.let{image->File(i.targetContext.getExternalFilesDir(null),"talk196-ask-cards.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
  }}finally{
   receipt.put("tools",JSONObject(voice.debugToolCalls.toMap())).put("connectionEvents",JSONArray(voice.debugConnectionEvents.toList())).put("wireEvents",JSONArray(voice.debugWireEvents.toList()))
   receipt.put("phase",voice.state.value.phase).put("lines",JSONArray(voice.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text).put("cards",JSONArray(it.titles.map{card->card.json()}))}))
   File(i.targetContext.getExternalFilesDir(null),"talk196-ask-cards.json").writeText(receipt.toString(2))
   i.runOnMainSync{voice.suspendSession();activity.finish()}
  }
 }
}
