package com.aliflix.app.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.ui.theme.AliflixMobileTheme
import com.aliflix.app.ui.discover.DiscoverTouchTracker
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class Talk191UiDeviceTest {
 @get:Rule val compose=createComposeRule()
 @Test fun earlierChatStartsAboveTheViewportAndRemainsScrollableInBothDirections(){
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
  try{
   repeat(9){n->session.debugPreviewUserQuestion("Earlier question number $n about the dialogue.")
    session.debugReplayServerMessage(JSONObject().put("serverContent",JSONObject().put("outputTranscription",JSONObject().put("text","Earlier answer number $n preserves the evidence and the story."))))}
   compose.setContent{AliflixMobileTheme{TalkPlayerBubble(session,{}, {})}}
   compose.onNodeWithTag("talk-player-orb",useUnmergedTree=true).performClick()
   compose.onNodeWithTag("talk-player-input").assertDoesNotExist()
   compose.onNodeWithContentDescription("Type").performClick()
   compose.onNodeWithTag("talk-player-input").assertIsDisplayed()
   compose.onNodeWithText("Earlier question number 0 about the dialogue.").assertDoesNotExist()
   compose.onNodeWithTag("talk-inline-exchange").performTouchInput{swipeDown(durationMillis=800)}
   compose.onNodeWithText("Earlier answer number 8 preserves the evidence and the story.").assertIsDisplayed()
   compose.onNodeWithTag("talk-inline-exchange").performTouchInput{swipeUp(durationMillis=800)}
   compose.onNodeWithText("Earlier answer number 8 preserves the evidence and the story.").assertIsNotDisplayed()
   compose.onNodeWithTag("talk-inline-exchange").performScrollToIndex(0)
   compose.onNodeWithText("Earlier question number 0 about the dialogue.").assertIsDisplayed()
   compose.onNodeWithTag("talk-inline-exchange").performScrollToIndex(session.state.value.lines.size)
   compose.onNodeWithText("Earlier question number 0 about the dialogue.").assertDoesNotExist()
   compose.runOnIdle{session.debugPreviewUserQuestion("Could the woman in red see the doorway?")}
   compose.runOnIdle{session.debugReplayServerMessage(JSONObject().put("serverContent",JSONObject().put("outputTranscription",JSONObject().put("text","She is facing away from the doorway, so the visible angle does not establish that she could see who entered."))))}
   compose.onNodeWithText("Could the woman in red see the doorway?").assertIsDisplayed()
   compose.onNodeWithContentDescription("Stop reply").assertDoesNotExist()
   compose.onNodeWithTag("talk-screen").assertDoesNotExist()
  }finally{session.close();scope.cancel()}
 }
 @Test fun longStreamKeepsChromeAndQuestionInsideTheGrowingBubble(){
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
  try{
   session.setTextMode(true)
   compose.setContent{AliflixMobileTheme{TalkPlayerBubble(session,{}, {})}}
   val question="Is the man in orange definitely suffering a broken leg, or is that only what they claim? Who says he has video, and what is the suited man repeatedly trying to prevent? Separate visible evidence from allegations, without using anything after this moment."
   compose.runOnIdle{session.debugPreviewUserQuestion(question)}
   compose.runOnIdle{session.debugReplayServerMessage(JSONObject().put("serverContent",JSONObject().put("outputTranscription",JSONObject().put("text","The visible posture and dialogue establish an allegation, rather than a confirmed diagnosis. ".repeat(12)))))}
   compose.onNodeWithContentDescription("Close Talk").assertIsDisplayed()
   val panel=compose.onNodeWithTag("talk-inline-conversation").fetchSemanticsNode().boundsInRoot
   val close=compose.onNodeWithContentDescription("Close Talk").fetchSemanticsNode().boundsInRoot
   assertTrue("Chrome clipped: $panel $close",panel.contains(close.center))
   compose.onNodeWithTag("talk-inline-exchange").performScrollToIndex(0)
   compose.onNodeWithText(question).assertIsDisplayed()
   val i=InstrumentationRegistry.getInstrumentation()
   i.uiAutomation.takeScreenshot()?.let{bitmap->java.io.File(i.targetContext.getExternalFilesDir(null),"talk191-long-bubble.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
  }finally{session.close();scope.cancel()}
 }
 @Test fun orbCornersStayTransparentAndEyesFollowTouches(){
  val tracker=DiscoverTouchTracker()
  var windowBounds=androidx.compose.ui.geometry.Rect.Zero
  val i=InstrumentationRegistry.getInstrumentation()
  compose.mainClock.autoAdvance=false
  compose.setContent{AliflixMobileTheme{Box(Modifier.fillMaxSize().background(Color(0xFF315A45))){
   TalkLiveOrb("Listening",{0f},true,Modifier.size(180.dp).testTag("tested-orb").onGloballyPositioned{windowBounds=it.boundsInWindow()},tracker=tracker)
  }}}
  compose.waitForIdle()
  val bounds=windowBounds
  fun shot()=requireNotNull(i.uiAutomation.takeScreenshot())
  fun eyeX():Double{val image=shot();var sum=0.0;var count=0
   for(y in (bounds.top+bounds.height*.15f).toInt() until (bounds.bottom-bounds.height*.15f).toInt())
    for(x in (bounds.left+bounds.width*.15f).toInt() until (bounds.right-bounds.width*.15f).toInt()){
     val c=image.getPixel(x,y);if(android.graphics.Color.red(c)<60&&android.graphics.Color.green(c)<60&&android.graphics.Color.blue(c)<60){sum+=x;count++}
    }
   image.recycle();assertTrue("Eyes must be rendered",count>2);return sum/count
  }
  compose.runOnIdle{tracker.point=androidx.compose.ui.geometry.Offset(bounds.left-100,bounds.center.y);tracker.lastTouchMillis=android.os.SystemClock.uptimeMillis()}
  compose.mainClock.advanceTimeBy(650);Thread.sleep(100);val left=eyeX()
  compose.runOnIdle{tracker.point=androidx.compose.ui.geometry.Offset(bounds.right+100,bounds.center.y);tracker.lastTouchMillis=android.os.SystemClock.uptimeMillis()}
  compose.mainClock.advanceTimeBy(650);Thread.sleep(100);val right=eyeX();if(android.animation.ValueAnimator.areAnimatorsEnabled())assertTrue("Gaze must move toward the touch ($left, $right)",right-left>bounds.width*.08) else assertEquals("Reduced motion keeps the eyes still",left,right,.1)
  val image=shot();val corner=image.getPixel((bounds.left+2).toInt(),(bounds.top+2).toInt());image.recycle()
  assertEquals(49,android.graphics.Color.red(corner));assertEquals(90,android.graphics.Color.green(corner));assertEquals(69,android.graphics.Color.blue(corner))
 }
}
