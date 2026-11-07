package com.aliflix.app.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.ui.discover.DiscoverAskCard
import com.aliflix.app.ui.theme.AliflixMobileTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class Talk196MotionDeviceTest {
 @get:Rule val compose=createComposeRule()
 private fun save(name:String,image:android.graphics.Bitmap){
  File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),name).outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
 }
 @Test fun discoverUsesTheMirroredLiveShaderAndKeepsTheOrbClear(){
  compose.mainClock.autoAdvance=false
  compose.setContent{AliflixMobileTheme{Box(Modifier.fillMaxSize().background(Color(0xFF09080E))){DiscoverAskCard{}}}}
  compose.mainClock.advanceTimeBy(200)
  val header=compose.onNodeWithTag("discover-smoke-live",useUnmergedTree=true).assertExists()
  val first=header.captureToImage().asAndroidBitmap();save("talk196-discover-early.png",compose.onRoot().captureToImage().asAndroidBitmap())
  compose.mainClock.advanceTimeBy(2200)
  val later=header.captureToImage().asAndroidBitmap();save("talk196-discover-later.png",compose.onRoot().captureToImage().asAndroidBitmap())
  var changed=0
  for(y in 8 until first.height-8 step 4)for(x in 8 until first.width/2 step 4){
   if(first.getPixel(x,y)!=later.getPixel(x,y))changed++
  }
  assertTrue("The actual GPU field must move, not silently use a static fallback",changed>40)
  compose.onAllNodesWithText("Ask Aliflix").assertCountEquals(1)
  compose.onNodeWithText("Ask").assertHasClickAction();compose.onNodeWithText("Talk").assertHasClickAction()
 }
 @Test fun playerVoiceUsesTheThinkingFaceWithoutASecondLoader(){
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
  session.debugOfflineConnection=true
  try{
   compose.setContent{AliflixMobileTheme{Box(Modifier.fillMaxSize().background(Color.Black)){TalkPlayerBubble(session,{}, {})}}}
   compose.onNodeWithTag("talk-player-orb",useUnmergedTree=true).performClick()
   compose.runOnIdle { session.debugPreviewUserQuestion("Why did she leave the room?") }
   compose.onNodeWithTag("talk-thinking-animation",useUnmergedTree=true).assertDoesNotExist()
   assertNotEquals(playerThinkingExpression(1.5f,TalkOrbActivity.THINK),playerThinkingExpression(4.6f,TalkOrbActivity.THINK))
   save("talk196-player-thinking.png",compose.onRoot().captureToImage().asAndroidBitmap())
  }finally{session.close();scope.cancel()}
 }
}
