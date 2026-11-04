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
 @Test fun singleModeButtonReturnsToVoiceAndKeepsTheDraft(){
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
  session.debugOfflineConnection=true
  try{
   compose.setContent{AliflixMobileTheme{TalkPlayerBubble(session,{scope.launch{session.connect()}}, {})}}
   compose.onNodeWithTag("talk-player-orb",useUnmergedTree=true).performClick()
   val id=session.state.value.conversationId
   repeat(3){round->
    compose.onAllNodesWithContentDescription("Type").assertCountEquals(1)
    compose.onNodeWithContentDescription("Type").performClick()
    compose.onAllNodesWithContentDescription("Voice conversation").assertCountEquals(1)
    compose.onNodeWithContentDescription("Type").assertDoesNotExist()
    if(round==0)compose.onNodeWithTag("talk-player-input").performTextInput("Why did she leave?")
    compose.onNodeWithTag("talk-player-input").assertTextContains("Why did she leave?")
    compose.onNodeWithContentDescription("Voice conversation").performClick()
    compose.runOnIdle{
     assertFalse(session.state.value.textMode);assertFalse(session.state.value.muted)
     assertEquals("Listening",session.state.value.phase);assertEquals(id,session.state.value.conversationId)
    }
    compose.onNodeWithTag("talk-player-input").assertDoesNotExist()
    compose.onNodeWithContentDescription("Voice conversation").assertDoesNotExist()
   }
  }finally{session.close();scope.cancel()}
 }
 @Test fun earlierChatStartsAboveTheViewportAndRemainsScrollableInBothDirections(){
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
  session.debugOfflineConnection=true
  try{
   repeat(9){n->session.debugPreviewUserQuestion("Earlier question number $n about the dialogue.")
    session.debugReplayServerMessage(JSONObject().put("serverContent",JSONObject().put("outputTranscription",JSONObject().put("text","Earlier answer number $n preserves the evidence and the story."))))}
   compose.setContent{AliflixMobileTheme{TalkPlayerBubble(session,{}, {})}}
   compose.onNodeWithTag("talk-player-orb",useUnmergedTree=true).performClick()
   compose.onNodeWithTag("talk-player-input").assertDoesNotExist()
   compose.onNodeWithText("Earlier question number 0 about the dialogue.").assertDoesNotExist()
   compose.onNodeWithTag("talk-inline-exchange").performTouchInput{swipeDown(durationMillis=800)}
   compose.onNodeWithText("Earlier answer number 8 preserves the evidence and the story.").assertIsDisplayed()
   compose.onNodeWithTag("talk-inline-exchange").performScrollToIndex(0)
   compose.onNodeWithText("Earlier question number 0 about the dialogue.").assertIsDisplayed()
   assertFalse("Reading history must not switch speech to typing",session.state.value.textMode)
   compose.onNodeWithTag("talk-player-input").assertDoesNotExist()
   compose.onNodeWithTag("talk-inline-exchange").performScrollToIndex(session.state.value.lines.size)
   compose.onNodeWithContentDescription("Type").performClick()
   compose.onNodeWithTag("talk-player-input").assertIsDisplayed()
   // Android IME resize is outside Compose's test clock; settle its window before absolute gestures.
   Thread.sleep(650);compose.waitForIdle()
   compose.onNodeWithText("Earlier question number 0 about the dialogue.").assertDoesNotExist()
   compose.onNodeWithTag("talk-inline-exchange").performTouchInput{swipeDown(durationMillis=800)}
   compose.onNodeWithText("Earlier answer number 8 preserves the evidence and the story.").assertIsDisplayed()
   val instrumentation=InstrumentationRegistry.getInstrumentation()
   instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"talk191-history-swipe.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
   compose.onNodeWithTag("talk-inline-exchange").performTouchInput{swipeUp(durationMillis=800)}
   compose.onNodeWithText("Earlier answer number 8 preserves the evidence and the story.").assertIsNotDisplayed()
   compose.onNodeWithTag("talk-inline-exchange").performScrollToIndex(0)
   compose.onNodeWithText("Earlier question number 0 about the dialogue.").assertIsDisplayed()
   compose.onNodeWithTag("talk-inline-exchange").performScrollToIndex(session.state.value.lines.size)
   compose.onNodeWithText("Earlier question number 0 about the dialogue.").assertDoesNotExist()
   compose.onNodeWithTag("talk-player-input").performTextInput("Could the woman in red see the doorway?")
   compose.onNodeWithContentDescription("Send").performClick()
   compose.runOnIdle{session.debugReplayServerMessage(JSONObject().put("serverContent",JSONObject().put("outputTranscription",JSONObject().put("text","She is facing away from the doorway, so the visible angle does not establish that she could see who entered."))))}
   compose.onNodeWithText("Could the woman in red see the doorway?").assertIsDisplayed()
   compose.onNodeWithContentDescription("Stop reply").assertDoesNotExist()
   compose.onNodeWithTag("talk-screen").assertDoesNotExist()
  }finally{session.close();scope.cancel()}
 }
 @Test fun longStreamKeepsChromeAndQuestionInsideTheGrowingBubble(){
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
  session.debugOfflineConnection=true
  try{
   compose.setContent{AliflixMobileTheme{TalkPlayerBubble(session,{}, {})}}
   val question="Is the man in orange definitely suffering a broken leg, or is that only what they claim? Who says he has video, and what is the suited man repeatedly trying to prevent? Separate visible evidence from allegations, without using anything after this moment."
   compose.onNodeWithTag("talk-player-orb",useUnmergedTree=true).performClick()
   compose.onNodeWithContentDescription("Type").performClick()
   compose.onNodeWithTag("talk-player-input").performTextInput(question)
   compose.onNodeWithContentDescription("Send").performClick()
   Thread.sleep(650);compose.waitForIdle()
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
  val image=shot()
  for(x in listOf(bounds.left+2,bounds.right-3))for(y in listOf(bounds.top+2,bounds.bottom-3)){
   val corner=image.getPixel(x.toInt(),y.toInt())
   assertEquals(49,android.graphics.Color.red(corner));assertEquals(90,android.graphics.Color.green(corner));assertEquals(69,android.graphics.Color.blue(corner))
  }
  image.recycle()
  val haloBefore=shot()
  compose.mainClock.advanceTimeBy(1000);Thread.sleep(100)
  val haloAfter=shot();var difference=0L;var pixels=0
  for(y in bounds.top.toInt() until bounds.bottom.toInt() step 2)for(x in bounds.left.toInt() until bounds.right.toInt() step 2){
   val dx=x-bounds.center.x;val dy=y-bounds.center.y;val radius=kotlin.math.sqrt(dx*dx+dy*dy)/bounds.width
   if(radius in .35f.. .46f){val a=haloBefore.getPixel(x,y);val b=haloAfter.getPixel(x,y)
    difference+=kotlin.math.abs(android.graphics.Color.red(a)-android.graphics.Color.red(b))+kotlin.math.abs(android.graphics.Color.green(a)-android.graphics.Color.green(b))+kotlin.math.abs(android.graphics.Color.blue(a)-android.graphics.Color.blue(b));pixels++}
  }
  haloBefore.recycle();haloAfter.recycle()
  if(android.animation.ValueAnimator.areAnimatorsEnabled())assertTrue("The ready halo must visibly move ($difference/$pixels)",difference>pixels)
  else assertEquals("Reduced motion keeps the halo still",0L,difference)
 }
}
