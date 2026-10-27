package com.aliflix.app.talk

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real navigation, sheet transactions and Ask entry point on the phone. */
class Talk184DiscoverConsolidationDeviceTest {
 @Test fun discoverOwnsFiltersAndAskOpensChatWithoutTheOldModePicker() {
  val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext;val automation=i.uiAutomation
  fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null)emptyList() else listOf(node)+(0 until node.childCount).flatMap{nodes(node.getChild(it))}
  fun current():List<AccessibilityNodeInfo>{if(android.os.Build.VERSION.SDK_INT>=33)automation.clearCache();return nodes(automation.rootInActiveWindow)}
  fun waitFor(label:String):AccessibilityNodeInfo {
   val until=android.os.SystemClock.elapsedRealtime()+30000
   while(android.os.SystemClock.elapsedRealtime()<until){current().firstOrNull{it.text?.toString()==label||it.contentDescription?.toString()==label}?.let{return it};Thread.sleep(100)}
   error("Missing $label. Visible: "+current().mapNotNull{it.text}.joinToString(" | ").take(1600))
  }
  fun click(label:String){
   val until=android.os.SystemClock.elapsedRealtime()+30000
   while(android.os.SystemClock.elapsedRealtime()<until){
    for(candidate in current().filter{it.text?.toString()==label||it.contentDescription?.toString()==label}) {
     var node=candidate;while(!node.isClickable&&node.parent!=null)node=node.parent
     if(node.isClickable&&node.isVisibleToUser&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(250);return}
    }
    Thread.sleep(80)
   }
   error("Missing clickable $label")
  }
  fun screenshot(name:String){automation.takeScreenshot()?.let{bitmap->File(context.getExternalFilesDir(null),"discover-184-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
  val intent=Intent(context,TalkTitleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("assistantAction",JSONObject().put("action","open_screen").put("screen","discover").toString())
  ActivityScenario.launch<TalkTitleActivity>(intent).use {
   waitFor("Filters");waitFor("Ask");waitFor("Talk")
   assertFalse(current().any{it.text?.toString() in setOf("Describe","Find similar","Surprise me")})
   screenshot("entry")
   click("Filters");waitFor("Genres")
   assertFalse("Sorting belongs to loaded results",current().any{it.text?.toString() in setOf("Sort","Most Popular","Highest Rated")})
   assertFalse(current().any{it.text?.toString()=="Japanese Animation"})
   click("Genres");click("Japanese Animation");click("American Animation")
   waitFor("2 selected");screenshot("draft")
   val fixedClose=android.graphics.Rect().also{waitFor("Close filters").getBoundsInScreen(it)}
   val display=android.graphics.Rect().also{automation.rootInActiveWindow.getBoundsInScreen(it)}
   fun swipe(from:Float,to:Float){
    val start=android.os.SystemClock.uptimeMillis();val x=display.centerX().toFloat()
    for(step in 0..16){val event=android.view.MotionEvent.obtain(start,start+step*16L,if(step==0)android.view.MotionEvent.ACTION_DOWN else if(step==16)android.view.MotionEvent.ACTION_UP else android.view.MotionEvent.ACTION_MOVE,x,from+(to-from)*step/16f,0);automation.injectInputEvent(event,true);event.recycle()}
    Thread.sleep(400)
   }
   swipe(display.height()*.75f,display.height()*.35f);swipe(display.height()*.35f,display.height()*.75f)
   val afterScroll=android.graphics.Rect().also{waitFor("Close filters").getBoundsInScreen(it)}
   assertEquals("Scrolling content must not drag the sheet",fixedClose,afterScroll);waitFor("Apply")
   click("Close filters");click("Filters")
   assertFalse("Dismissing a draft must not apply it",current().any{it.text?.toString()=="2 selected"})
   click("Genres");click("Japanese Animation");click("American Animation");click("Apply")
   waitFor("Ask");waitFor("Sort");screenshot("applied")
   click("Sort");waitFor("Highest Rated");screenshot("sorting");click("Close sorting")
   click("Ask");waitFor("Message Aliflix")
   assertFalse(current().any{it.text?.toString() in setOf("Describe","Similar","Surprise me","Movies","Series")})
   assertFalse(current().any{it.text?.toString()?.contains("History on")==true||it.text?.toString()?.contains("Paused ·")==true})
   screenshot("chat")
   click("Close conversation")
  }
 }
}
