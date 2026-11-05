package com.aliflix.app.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.player.MobilePlayerTopBar
import com.aliflix.app.ui.theme.AliflixMobileTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class Talk192UiDeviceTest {
 @get:Rule val compose=createComposeRule()
 @Test fun toolbarTalkUsesTheSameBaselineAndTouchTargetAsTheExistingActions(){
  var clicks=0
  compose.setContent{AliflixMobileTheme{Box(Modifier.fillMaxSize().background(Color.Black)){
   MobilePlayerTopBar("A deliberately long episode title",true,{},{},{},{},{},detail="S01 • E01",onQuality={},onTalk={clicks++})
  }}}
  val labels=listOf("Episodes","Subtitles","Quality","Talk","Rotate","More")
  val bounds=labels.map{label->compose.onNodeWithContentDescription(label).assertHasClickAction().fetchSemanticsNode().boundsInRoot}
  val talk=bounds[3]
  assertEquals(bounds[2].width,talk.width,.1f);assertEquals(bounds[2].height,talk.height,.1f)
  for(i in bounds.indices)for(j in i+1 until bounds.size)assertFalse("Overlapping toolbar actions",bounds[i].overlaps(bounds[j]))
  compose.onNodeWithContentDescription("Talk").performClick();compose.runOnIdle{assertEquals(1,clicks)}
 }
 @Test fun pressingTheWholeOrbKeepsEveryOuterCornerTransparent(){
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
  session.debugOfflineConnection=true
  try{
   compose.setContent{AliflixMobileTheme{Box(Modifier.fillMaxSize().background(Color(0xFF315A45))){TalkPlayerBubble(session,{}, {})}}}
   val orb=compose.onNodeWithTag("talk-player-orb-container",useUnmergedTree=true)
   val area=orb.fetchSemanticsNode().boundsInRoot
   orb.performTouchInput{down(center)}
   compose.mainClock.advanceTimeBy(350);compose.waitForIdle()
   val image=compose.onRoot().captureToImage().asAndroidBitmap()
   for((x,y) in listOf(area.left+2 to area.top+2,area.right-3 to area.top+2,area.left+2 to area.bottom-3,area.right-3 to area.bottom-3)){
    val pixel=image.getPixel(x.toInt(),y.toInt())
    assertEquals("Pressed indication must not paint a square",android.graphics.Color.rgb(49,90,69),pixel)
   }
   orb.performTouchInput{up()}
   val instrumentation=InstrumentationRegistry.getInstrumentation()
   instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"talk192-orb-interaction.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
  }finally{session.close();scope.cancel()}
 }
}
