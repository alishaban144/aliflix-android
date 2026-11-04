package com.aliflix.app.talk

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.ui.theme.AliflixMobileTheme
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

class Talk190UiDeviceTest {
 @get:Rule val compose=createComposeRule()
 @Test fun voiceHasNoVisibleStatusOrMicrophoneAndGreetingRetainsIdentityAndLanguage(){
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
  try{
   compose.setContent{AliflixMobileTheme{TalkVoiceScreen(session,{}, {})}}
   for(phase in listOf("Connecting","Reconnecting","Listening","Speaking")){
    compose.runOnIdle{session.debugPreviewOrbContext(phase,"")}
    compose.onNodeWithText(phase).assertDoesNotExist()
   }
   compose.onNodeWithContentDescription("Mute microphone").assertDoesNotExist()
   compose.runOnIdle{
    session.debugPreviewUserQuestion("Why did she leave the room before the alarm?")
    val id=session.state.value.conversationId
    session.debugSetInputActivity(true)
    session.debugReplayServerMessage(JSONObject("""{"serverContent":{"inputTranscription":{"text":"Hola"}}}"""))
    session.debugSetInputActivity(false)
    assertEquals("en",session.debugResponseLanguage)
    assertEquals(id,session.state.value.conversationId)
    assertFalse(talkActionRequested("new_chat","Hi"))
   }
  }finally{session.close();scope.cancel()}
 }
 @Test fun aLongAnswerAndItsPostersDoNotScrollAwayTheQuestion(){
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
  try{
   session.setTextMode(true)
   compose.setContent{AliflixMobileTheme{TalkVoiceScreen(session,{}, {})}}
   val question="Which three films explore memory through unreliable narrators?"
   compose.runOnIdle{session.debugPreviewUserQuestion(question)}
   compose.runOnIdle{session.debugReplayServerMessage(JSONObject().put("serverContent",JSONObject().put("outputTranscription",JSONObject().put("text",("Memory shapes what these narrators believe, and the film gradually gives us evidence to challenge it. ").repeat(24)))))}
   compose.onNodeWithText(question).assertIsDisplayed()
   val orb=compose.onNodeWithTag("talk-chat-orb").fetchSemanticsNode().boundsInRoot
   val prompt=compose.onNodeWithText(question).fetchSemanticsNode().boundsInRoot
   assertTrue("Orb must never cover conversation text",orb.bottom<=prompt.top)
   compose.onNodeWithText("Connecting…").assertDoesNotExist()
   val i=InstrumentationRegistry.getInstrumentation()
   i.uiAutomation.takeScreenshot()?.let{bitmap->File(i.targetContext.getExternalFilesDir(null),"talk190-answer-anchor.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
  }finally{session.close();scope.cancel()}
 }

 @Test fun floatingBubbleGrowsVerticallyWithStreamingTextWithoutOpeningTheTalkWindow(){
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
  val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
  try{
   compose.setContent{AliflixMobileTheme{TalkPlayerBubble(session,{}, {})}}
   val question="Why did she return the key instead of keeping it?"
   compose.runOnIdle{session.debugPreviewUserQuestion(question)}
   compose.onNodeWithTag("talk-inline-conversation").assertIsDisplayed()
   val first=compose.onNodeWithTag("talk-inline-conversation").fetchSemanticsNode().boundsInRoot
   val opening="She returned it because the earlier agreement required her to leave it with its owner."
   compose.runOnIdle{session.debugReplayServerMessage(JSONObject().put("serverContent",JSONObject().put("outputTranscription",JSONObject().put("text",opening))))}
   val second=compose.onNodeWithTag("talk-inline-conversation").fetchSemanticsNode().boundsInRoot
   assertTrue("The reply should grow its whole bubble vertically",second.height>first.height)
   compose.onNode(hasText(question) and hasAnyAncestor(hasTestTag("talk-inline-conversation"))).assertIsDisplayed()
   compose.onNode(hasText(opening) and hasAnyAncestor(hasTestTag("talk-inline-conversation"))).assertIsDisplayed()
   compose.onNodeWithTag("talk-screen").assertDoesNotExist()
   val i=InstrumentationRegistry.getInstrumentation()
   i.uiAutomation.takeScreenshot()?.let{bitmap->File(i.targetContext.getExternalFilesDir(null),"talk190-streaming-bubble.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
  }finally{session.close();scope.cancel()}
 }
}
