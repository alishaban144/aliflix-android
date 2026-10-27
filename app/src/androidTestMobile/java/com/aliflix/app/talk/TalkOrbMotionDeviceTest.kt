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

class TalkOrbMotionDeviceTest {
 @Test fun oneFaceTravelsBetweenVoiceResultsAndChatWithoutObscuringControls(){
  val i=InstrumentationRegistry.getInstrumentation()
  val automation=i.uiAutomation
  automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS}
  val activity=launchFreshTalkActivity(localAudio=true);val voice=activity.voice
  val captures=JSONArray();val directory=File(i.targetContext.getExternalFilesDir(null),"orb-motion-182").apply{mkdirs()}
  fun orbNodes(node:AccessibilityNodeInfo):List<AccessibilityNodeInfo> {
   val own=if(node.contentDescription?.startsWith("Aliflix voice:")==true)listOf(node)else emptyList()
   return own+(0 until node.childCount).flatMap{index->node.getChild(index)?.let(::orbNodes).orEmpty()}
  }
  suspend fun capture(name:String):Rect {
   delay(1300)
   if(android.os.Build.VERSION.SDK_INT>=33)automation.clearCache()
   var root=automation.rootInActiveWindow
   withTimeout(10000){while(root==null){delay(100);root=automation.rootInActiveWindow}}
   val window=requireNotNull(root);val orbs=orbNodes(window)
   assertEquals("One retained face must own the screen",1,orbs.size)
   val bounds=Rect().also{orbs.single().getBoundsInScreen(it)}
   val screen=Rect().also{window.getBoundsInScreen(it)}
   assertTrue("Face escaped the viewport: $bounds / $screen",screen.contains(bounds))
   i.uiAutomation.takeScreenshot()?.let{bitmap->File(directory,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
   captures.put(JSONObject().put("state",name).put("left",bounds.left).put("top",bounds.top).put("width",bounds.width()).put("height",bounds.height()))
   return bounds
  }
  try{runBlocking{
   val home=capture("voice")
   i.runOnMainSync{voice.debugPreviewTitles(listOf(TalkTitleCard(474331,MediaType.MOVIE,"Keep an Eye Out","2018")),false)}
   val single=capture("one-card")
   assertTrue("One card must leave the face readable",single.width()>=128*i.targetContext.resources.displayMetrics.density*.95f)
   i.runOnMainSync{voice.debugPreviewTitles(listOf(TalkTitleCard(474331,MediaType.MOVIE,"Keep an Eye Out","2018"),TalkTitleCard(348672,MediaType.MOVIE,"About Endlessness","2019"),TalkTitleCard(14337,MediaType.MOVIE,"Primer","2004")),false)}
   val triple=capture("three-cards")
   assertTrue("Three cards must leave the face readable",triple.width()>=128*i.targetContext.resources.displayMetrics.density*.95f)
   i.runOnMainSync{voice.debugPreviewTitles(listOf(TalkTitleCard(474331,MediaType.MOVIE,"Keep an Eye Out","2018"),TalkTitleCard(348672,MediaType.MOVIE,"About Endlessness","2019")),false)}
   val result=capture("results");assertTrue("Results must reclaim space",result.width()<home.width())
   i.runOnMainSync{voice.debugPreviewOrbContext("Speaking","Keep an Eye Out uses absurd procedural detail.")}
   val first=capture("first-title")
   i.runOnMainSync{voice.debugPreviewOrbContext("Speaking","About Endlessness has a very different visual rhythm.")}
   val second=capture("second-title")
   assertTrue("Conversation attention must travel to the title being discussed",second.centerX()>first.centerX()+30)
   i.runOnMainSync{voice.debugPreviewOrbContext("Thinking","")}
   val thinking=capture("thinking")
   assertTrue("Thinking must reclaim space and move away from reading content",thinking.width()<second.width()&&thinking.centerY()<second.centerY())
   i.runOnMainSync{voice.debugPreviewOrbContext("Listening","")}
   capture("listening")
   i.runOnMainSync{voice.setTextMode(true)}
   val chat=capture("chat");assertTrue("The same face must dock in the chat header",chat.width()<result.width()&&chat.top<result.top)
   i.runOnMainSync{voice.setTextMode(false)}
   capture("return")
  }}finally{
   File(directory,"bounds.json").writeText(captures.toString(2))
   i.runOnMainSync{voice.suspendSession();activity.finish()}
  }
 }
}
