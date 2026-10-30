package com.aliflix.app.talk

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.ui.theme.AliflixMobileTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class TalkVoiceUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun focusedTextComposerKeepsHeaderAndHistoryControlsReachable() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
        session.setTextMode(true)
        try {
            compose.setContent {
                val activity=androidx.activity.compose.LocalActivity.current
                androidx.compose.runtime.SideEffect { activity?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
                AliflixMobileTheme { TalkVoiceScreen(session,{}, {}) }
            }
            compose.onNodeWithTag("talk-composer").performClick().performTextInput("Recent sci-fi")
            compose.onNodeWithContentDescription("Conversation settings").assertIsDisplayed()
            compose.onNodeWithContentDescription("Close conversation").assertIsDisplayed()
            compose.onNodeWithTag("talk-composer").assertIsDisplayed()
            compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
            compose.onNodeWithContentDescription("Conversation settings").performClick()
            compose.onNodeWithTag("talk-recent-chats").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Close panel").assertIsDisplayed().performClick()
            compose.onNodeWithTag("talk-composer").assertTextContains("Recent sci-fi")
            compose.runOnIdle { assertEquals(0L,session.debugReadyGeneration) }
            Thread.sleep(500)
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.getExternalFilesDir(null),"talk-modern-composer-177.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
        } finally {session.close();scope.cancel()}
    }
    @Test fun orbObservesTouchesFarOutsideItsHaloWithoutEatingControls() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
        val tracker=com.aliflix.app.ui.discover.DiscoverTouchTracker()
        var closed=false
        try {
            compose.setContent { AliflixMobileTheme { TalkVoiceScreen(session,{}, {closed=true},tracker) } }
            compose.onNodeWithTag("talk-screen").performTouchInput { click(androidx.compose.ui.geometry.Offset(width*.08f,height*.78f)) }
            compose.runOnIdle { assertNotNull(tracker.point);assertTrue(tracker.lastTouchMillis>0) }
            compose.onNodeWithContentDescription("Close conversation").performClick()
            compose.runOnIdle { assertTrue(closed) }
        } finally {session.close();scope.cancel()}
    }
    @Test fun conversationStartsAutomaticallyAndPrivacyControlsRemainReachable() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope)
        var started=false;var closed=false
        try {
            compose.setContent { AliflixMobileTheme { TalkVoiceScreen(session,{started=true},{closed=true}) } }
            compose.onNodeWithText("Start").assertDoesNotExist()
            compose.onNodeWithText("Resume").assertDoesNotExist()
            compose.runOnIdle { assertTrue(started);assertEquals("Ready",session.state.value.phase) }
            compose.onNodeWithContentDescription("Conversation settings").performClick()
            compose.onNodeWithText("Privacy").performClick()
            compose.onNodeWithText("Keep history").assertIsDisplayed()
            compose.onNodeWithText("Delete history").assertIsDisplayed()
            compose.onNodeWithContentDescription("Close panel").performClick()
            compose.onNodeWithContentDescription("Conversation settings").performClick()
            compose.onNodeWithTag("talk-recent-chats").performClick()
            compose.onNodeWithText("Recent chats").assertIsDisplayed()
            compose.onNodeWithContentDescription("Close panel").performClick()
            compose.onNodeWithContentDescription("Close conversation").performClick()
            compose.runOnIdle { assertTrue(closed) }
        } finally { session.close();scope.cancel() }
    }
    @Test fun voiceChoicesAreReachableWithoutStartingOrChangingSession(){
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope)
        try{
            compose.setContent{AliflixMobileTheme{TalkVoiceScreen(session,{}, {})}}
            compose.onNodeWithContentDescription("Conversation settings").performClick()
            compose.onNodeWithTag("talk-voice-picker").performClick()
            for(voice in listOf("Aoede","Kore","Puck","Charon"))compose.onAllNodesWithText(voice).onLast().assertIsDisplayed()
            compose.onNodeWithContentDescription("Close panel").performClick()
            compose.runOnIdle{assertEquals("Ready",session.state.value.phase)}
        }finally{session.close();scope.cancel()}
    }
    @Test fun topTypingIconOpensChatAndStopCancelsWithoutNetwork() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
        try {
            compose.setContent { AliflixMobileTheme { TalkVoiceScreen(session,{}, {}) } }
            val screen=compose.onNodeWithTag("talk-screen").fetchSemanticsNode().boundsInRoot
            val chat=compose.onNodeWithTag("talk-chat-mode").fetchSemanticsNode().boundsInRoot
            assertTrue(chat.bottom<screen.height*.22f)
            compose.onNodeWithTag("talk-chat-mode").performClick()
            compose.onNodeWithTag("talk-composer").performTextInput("Draft preserved")
            compose.onNodeWithContentDescription("Voice conversation").performClick()
            compose.onNodeWithTag("talk-chat-mode").performClick()
            compose.onNodeWithTag("talk-composer").assertTextContains("Draft preserved")
            compose.runOnIdle { session.stopAnswer();assertFalse(session.state.value.sending);assertEquals(0L,session.debugReadyGeneration) }
        } finally { session.close();scope.cancel() }
    }
}
