package com.aliflix.app.talk

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.MediaType
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Talk184ChatCapabilitiesDeviceTest {
 @Test fun seriesSimilarityOrdinalFollowupAndCompactSourcesWorkThroughChat() {
  val i=InstrumentationRegistry.getInstrumentation();val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  val receipt=JSONObject()
  fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null)emptyList() else listOf(node)+(0 until node.childCount).flatMap{nodes(node.getChild(it))}
  fun current():List<AccessibilityNodeInfo>{if(android.os.Build.VERSION.SDK_INT>=33)i.uiAutomation.clearCache();return nodes(i.uiAutomation.rootInActiveWindow)}
  suspend fun waitNode(scroll:Boolean=false,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo = withTimeout(15000){while(true){val all=current();all.firstOrNull(predicate)?.let{return@withTimeout it};if(scroll)all.firstOrNull{it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);delay(250)};error("Missing node")}
  fun click(candidate:AccessibilityNodeInfo){var node=candidate;while(!node.isClickable&&node.parent!=null)node=node.parent;assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))}
  try{runBlocking{
   withContext(Dispatchers.Main){voice.setTextMode(true);voice.seedTitle(TalkTitleCard(95396,MediaType.TV,"Severance","2022").json())}
   suspend fun ask(text:String):TalkLine {
    val count=voice.state.value.lines.size
    withContext(Dispatchers.Main){voice.sendText(text)}
    return withTimeout(110000){while(true){val s=voice.state.value;val reply=s.lines.drop(count).lastOrNull{it.role=="assistant"};if(reply!=null&&s.answerComplete&&!s.sending)return@withTimeout reply;delay(100)};error("No reply")}
   }
   val collection=ask("Give me ten ended series similar to Severance (2022): unsettling institutions, concealed identities and layered mysteries. Include non-English shows. Exclude Severance itself and any still-running series.")
   receipt.put("collection",JSONArray(collection.titles.map{it.json()})).put("reply",collection.text).put("sourceCount",collection.sources.size)
   assertTrue("Public research evidence must remain attached to the completed reply",collection.sources.isNotEmpty())
   assertEquals("One question owns one collection",1,voice.state.value.lines.drop(voice.state.value.lines.indexOfLast{it.role=="user"}+1).count{it.titles.isNotEmpty()})
   assertTrue("The similarity search must return genuine matches rather than padding",collection.titles.size>=3)
   assertTrue(collection.titles.all{it.type==MediaType.TV&&it.id!=95396})
   val context=voice.state.value.cards
   val ordinal=listOf("first","second","third","fourth","fifth","sixth","seventh")[minOf(6,collection.titles.lastIndex)]
   val selected=collection.titles[minOf(6,collection.titles.lastIndex)]
   assertEquals(selected,context.resolve("the $ordinal one").single())
   val source=waitNode(scroll=true){it.contentDescription?.startsWith("Sources, ")==true}
   click(source);waitNode{it.text?.toString()=="Sources"}
   assertTrue("Source sheet must show actual publishers",current().count{it.text?.toString()?.contains('.')==true}>0)
   i.uiAutomation.takeScreenshot()?.let{bitmap->File(i.targetContext.getExternalFilesDir(null),"talk-184-sources.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
   click(waitNode{it.contentDescription?.toString()=="Close sources"})
   val followup=ask("Tell me about the $ordinal one without spoilers. What is its central setting?")
   receipt.put("selectedTitle",selected.title).put("followup",followup.text).put("followupCards",JSONArray(followup.titles.map{it.json()}))
   assertTrue(followup.text.isNotBlank())
   assertEquals(collection.titles,voice.state.value.lines.first{it.titles==collection.titles}.titles)
   assertEquals(0,voice.debugPcmFrames)
  }}finally{
   i.uiAutomation.takeScreenshot()?.let{bitmap->File(i.targetContext.getExternalFilesDir(null),"talk-184-chat-capabilities.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
   File(i.targetContext.getExternalFilesDir(null),"talk-184-chat-capabilities.json").writeText(receipt.put("tools",JSONObject(voice.debugToolCalls.toMap())).put("issue",voice.debugRecommendationIssue).put("lines",JSONArray(voice.state.value.lines.map{JSONObject().put("role",it.role).put("text",it.text).put("titles",JSONArray(it.titles.map{c->c.json()}))})).toString(2))
   i.runOnMainSync{voice.suspendSession();activity.finish()}
  }
 }
}
