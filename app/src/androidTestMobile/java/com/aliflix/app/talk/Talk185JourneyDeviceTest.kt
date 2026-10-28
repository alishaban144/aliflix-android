package com.aliflix.app.talk

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.MediaType
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Talk185JourneyDeviceTest {
 @Test fun fiveChoicesBridgeDecisionContinuationReconnectAndRestoreKeepTheirActualIdentity() {
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  val receipt=JSONArray();val file=File(i.targetContext.getExternalFilesDir(null),"talk-185-live-journey.json")
  try{runBlocking{
   suspend fun ask(question:String):List<TalkLine>{
    val before=voice.state.value.lines.size
    withContext(Dispatchers.Main){voice.debugInjectText(question)}
    withTimeout(110000){while(voice.debugFirstAudioAt==0L||voice.state.value.sending||voice.state.value.phase=="Speaking"||voice.state.value.lines.drop(before).none{it.role=="assistant"&&it.text.isNotBlank()})delay(50)}
    delay(800)
    val lines=voice.state.value.lines.drop(before).filter{it.role=="assistant"}
    receipt.put(JSONObject().put("question",question).put("reply",JSONArray(lines.map{it.text})).put("cards",voice.state.value.cards.snapshot()).put("audio",voice.debugAudioSnapshot()).put("tools",JSONObject(voice.debugToolCalls.toMap())))
    file.writeText(receipt.toString(2))
    assertFalse(lines.any{Regex("(?i)haven.t (?:named|recommended)|no record|haven.t suggested|no titles (?:yet|were)").containsMatchIn(it.text)})
    assertTrue("The whole viewport must retain readable cards and controls",voice.state.value.cards.visible.isNotEmpty())
    i.uiAutomation.takeScreenshot()?.let{b->File(i.targetContext.getExternalFilesDir(null),"talk-185-turn-${receipt.length()}.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    return lines
   }
   val first=ask("Suggest five wildly different films built around a strange social rule. Mix countries and decades, avoid franchise entries, and briefly distinguish how the rule changes people's lives.")
   val original=voice.state.value.cards.group
   assertEquals("Voice must support a genuine five-choice result",5,original.size)
   assertTrue("The actual lookup must compose its bridge",(voice.debugToolCalls["lookup_bridge_accepted"]?:0)>0)
   assertTrue("The composed bridge must actually be delivered",first.any{it.titles.isEmpty()&&talkResearchNoteAllowed(it.text)})
   ask("Which one of those five would you actually recommend tonight? Pick just one and explain why.")
   assertEquals(1,voice.state.value.cards.visible.size)
   val winner=voice.state.value.cards.visible.single();assertTrue(original.any{it.key==winner.key})
   assertEquals(original.map{it.key},voice.state.value.cards.group.map{it.key})
   ask("And? Tell me something more about that one.")
   assertEquals(winner.key,voice.state.value.cards.visible.single().key)
   val ready=voice.debugReadyGeneration;withContext(Dispatchers.Main){voice.debugReconnect()}
   withTimeout(25000){while(voice.debugReadyGeneration<=ready)delay(50)}
   ask("What was the fourth film you first suggested? Show its card and say its title.")
   assertEquals(original[3].key,voice.state.value.cards.visible.single().key)
   ask("Bring back all five original choices.")
   assertEquals(original.map{it.key},voice.state.value.cards.visible.map{it.key})
   ask("Antworte auf Deutsch: Welches davon empfiehlst du? Entscheide dich für einen Film.")
   assertEquals(1,voice.state.value.cards.visible.size);assertTrue(original.any{it.key==voice.state.value.cards.visible.single().key})
  }}finally{file.writeText(receipt.toString(2));i.runOnMainSync{voice.suspendSession();activity.finish()}}
 }

 @Test fun eightCardsFocusAndExitLeaveTheEntireScreenUsable() {
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  val automation=i.uiAutomation
  automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
  fun all(n:AccessibilityNodeInfo):List<AccessibilityNodeInfo> = listOf(n)+(0 until n.childCount).flatMap{n.getChild(it)?.let(::all).orEmpty()}
  fun bounds(n:AccessibilityNodeInfo)=Rect().also{n.getBoundsInScreen(it)}
  val cards=(1..8).map{TalkTitleCard(90000+it,MediaType.MOVIE,"Distinct choice $it","200$it")}
  val snapshots=JSONArray()
  suspend fun inspect(name:String):Float {
   delay(1100);if(android.os.Build.VERSION.SDK_INT>=33)automation.clearCache()
   val root=requireNotNull(automation.rootInActiveWindow);val nodes=all(root);val screen=bounds(root)
   val orb=bounds(nodes.single{it.contentDescription?.startsWith("Aliflix voice:")==true})
   val end=bounds(nodes.first{it.contentDescription=="End conversation"})
   assertTrue(screen.contains(orb));assertTrue(screen.contains(end));assertFalse(Rect.intersects(orb,end))
   assertTrue(orb.width()>=128*i.targetContext.resources.displayMetrics.density*.9f)
   val visible=voice.state.value.cards.visible
   val measured=visible.map{requireNotNull(voice.debugCardBounds[it.key]){"No actual bounds for ${it.key}"}}
   measured.forEach{r->assertTrue(r.left>=0&&r.right<=screen.right+1);assertTrue(r.bottom<end.top);assertFalse(r.overlaps(androidx.compose.ui.geometry.Rect(orb.left.toFloat(),orb.top.toFloat(),orb.right.toFloat(),orb.bottom.toFloat())))}
   measured.forEachIndexed{index,r->measured.drop(index+1).forEach{other->assertFalse("Card content overlaps another card",r.overlaps(other))}}
   snapshots.put(JSONObject().put("name",name).put("count",visible.size).put("orbWidth",orb.width()).put("cards",JSONArray(measured.map{it.toString()})))
   automation.takeScreenshot()?.let{b->File(i.targetContext.getExternalFilesDir(null),"talk-185-stage-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
   return measured.first().width
  }
  try{runBlocking{
   withContext(Dispatchers.Main){voice.debugPreviewTitles(cards,false)};inspect("eight")
   withContext(Dispatchers.Main){voice.debugPreviewTitles(cards.take(5),false)};val normal=inspect("five")
   withContext(Dispatchers.Main){voice.debugPreviewUserQuestion("Tell me about Distinct choice 4")}
   val focused=inspect("focused");assertTrue(focused>normal*1.15f)
   assertEquals(cards[3].key,voice.state.value.cards.visible.single().key)
   assertEquals(cards.take(5).map{it.key},voice.state.value.cards.group.map{it.key})
   withContext(Dispatchers.Main){voice.debugPreviewTitles(cards.take(5),false)};inspect("restored")
  }}finally{File(i.targetContext.getExternalFilesDir(null),"talk-185-stage.json").writeText(snapshots.toString(2));i.runOnMainSync{voice.suspendSession();activity.finish()}}
 }
}
