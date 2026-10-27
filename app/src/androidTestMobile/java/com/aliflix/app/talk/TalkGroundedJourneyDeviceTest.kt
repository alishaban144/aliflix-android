package com.aliflix.app.talk

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Real service, actual loaded Compose artwork and AudioTrack, in one chat. */
class TalkGroundedJourneyDeviceTest {
 @get:Rule val compose=createAndroidComposeRule<TalkVoiceActivity>()
 @Test fun spokenSelectionPostersEditionFollowupsAndNativeUndoRemainConsistent() {
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalk180")=="true")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val session=compose.activity.voice;val turns=JSONArray()
  var restoreSaved:(()->Unit)?=null
  val receipt=File(context.getExternalFilesDir(null),"talk-grounded-180.json")
  try {runBlocking {
   compose.runOnUiThread{session.newChat()}
   suspend fun turn(prompt:String,chat:Boolean=false):String {
    val began=SystemClock.elapsedRealtime();val from=session.state.value.lines.size
    withContext(Dispatchers.Main){if(chat){session.setTextMode(true);session.sendText(prompt)}else session.debugInjectText(prompt)}
    withTimeout(85000){while(session.debugTurnCompleteAt<began||session.state.value.sending||session.state.value.phase=="Speaking")delay(70)}
    val answer=session.state.value.lines.drop(from).filter{it.role=="assistant"&&!it.stopped}.joinToString(" "){it.text}
    turns.put(JSONObject().put("prompt",prompt).put("answer",answer).put("ms",SystemClock.elapsedRealtime()-began).put("cards",JSONArray(session.state.value.cards.visible.map{it.json()})).put("deliveryCorrections",session.debugDeliveryRejections))
    receipt.writeText(JSONObject().put("turns",turns).toString(2))
    assertTrue("Every request receives a substantive reply: $prompt",answer.isNotBlank())
    assertFalse("Do not ask an already established edition: $answer",Regex("(?i)which (?:one|version|edition|annihilation)").containsMatchIn(answer))
    assertFalse("Do not narrate obvious card visibility: $answer",Regex("(?i)(?:card|cards|it|they).{0,40}(?:now shown|now displayed|now visible|on (?:your |the )?screen)").containsMatchIn(answer))
    if(!chat)assertTrue("Voice reaches the physical AudioTrack",session.debugRenderedSamples>0)
    return answer
   }
   turn("Hi")
   val recommendationFrom=session.state.value.lines.size
   val answer=turn("Recommend three thoughtful science fiction movies, with one short reason each.")
   val group=session.state.value.cards.visible
   assertEquals(3,group.size)
   assertEquals("One exact speech/poster selection must commit",1,session.debugPresentationCommits)
   assertFalse("A completed selection cannot append a phantom lookup error",Regex("(?i)system encountered|try again later").containsMatchIn(answer))
   assertTrue("Every spoken recommendation must equal the selected cards: $answer",talkAnswerNamesAllTitles(answer,group))
   val delivered=session.state.value.lines.drop(recommendationFrom).filter{it.role=="assistant"&&!it.stopped}
   assertTrue("No canned search filler may precede the selected recommendations",delivered.filter{it.titles.isEmpty()}.isEmpty())
   for(card in group) {
    compose.waitUntil(20000){compose.onAllNodesWithTag("talk-poster-${card.key}-loaded",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
    val media=com.aliflix.app.recommendation.RecommendationAiClient(com.aliflix.app.BuildConfig.RECOMMENDATION_AI_BASE_URL).getTitleDetails(card.type.routeName,card.id).media
    assertEquals("Poster belongs to the canonical spoken film",card.title,media.title)
    assertTrue("Science fiction cannot degrade to unrelated fallback genres: ${card.title}",media.genres.contains("Science Fiction"))
   }
   compose.onRoot().captureToImage().let{bitmap->File(context.getExternalFilesDir(null),"talk-grounded-180.png").outputStream().use{bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
   turn("Tell me more about the third one, briefly and without spoilers.")
   assertEquals(listOf(group[2].key),session.state.value.cards.visible.map{it.key})
   turn("Show its card again.")
   assertEquals(listOf(group[2].key),session.state.value.cards.visible.map{it.key})
   turn("Show all three recommendations again.")
   assertEquals(group.map{it.key},session.state.value.cards.visible.map{it.key})
   turn("Show the card for Annihilation, Alex Garland's 2018 movie.")
   assertEquals(listOf("movie:300668"),session.state.value.cards.visible.map{it.key})
   turn("Tell me more about Annihilation without spoilers, briefly.")
   assertEquals(listOf("movie:300668"),session.state.value.cards.visible.map{it.key})
   turn("Show me its card.",chat=true)
   assertEquals(listOf("movie:300668"),session.state.value.cards.visible.map{it.key})
   val library=(context.applicationContext as com.aliflix.app.AliflixApplication).libraryStore
   turn("Check whether this movie is saved in My List.",chat=true)
   val selected=session.state.value.cards.visible.single();val saved=library.isInMyList(selected.media())
   restoreSaved={if(library.isInMyList(selected.media())!=saved)library.toggleMyList(selected.media())}
   turn(if(saved)"Remove this movie from My List." else "Save this movie to My List.",chat=true)
   assertEquals(!saved,library.isInMyList(selected.media()))
   turn("Undo that change.",chat=true)
   assertEquals(saved,library.isInMyList(selected.media()))
   turn("Hello",chat=true)
   assertEquals(0,session.debugStallRecoveries)
  }} finally {
   receipt.writeText(JSONObject().put("turns",turns).put("phase",session.state.value.phase).put("tools",JSONObject(session.debugToolCalls.toMap())).put("deliveryCorrections",session.debugDeliveryRejections).put("connectionIssue",session.debugConnectionIssue).put("events",JSONArray(session.debugWireEvents.toList())).put("lines",JSONArray(session.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text)})).toString(2))
   compose.runOnUiThread{restoreSaved?.invoke();session.suspendSession()}
  }
 }
}
