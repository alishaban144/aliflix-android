package com.aliflix.app.talk

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.theme.AliflixMobileTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TalkTitleCardsDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val titles=listOf(TalkTitleCard(27205,MediaType.MOVIE,"Inception","2010","/9gk7adHYeDvHkCSEqAvQNLV5Uge.jpg"),TalkTitleCard(70523,MediaType.TV,"Dark","2017","/apbrbWs8M9lyOpJYU5WXrpFbk1Z.jpg"))
    @Test fun speakingKeepsCardsAndSelectingTheThirdCentersItThroughFollowups() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
        val third=TalkTitleCard(522627,MediaType.MOVIE,"The Gentlemen","2019")
        try {
            session.debugPreviewTitles(titles+third,false)
            compose.setContent{AliflixMobileTheme{TalkVoiceScreen(session,{}, {})}}
            compose.runOnIdle{session.debugPreviewUserQuestion("Could you tell me more?")}
            compose.onNodeWithTag("talk-title-movie:27205").assertIsDisplayed()
            compose.runOnIdle{session.debugPreviewUserQuestion("Tell me about The Gentlemen")}
            compose.waitForIdle()
            compose.onNodeWithTag("talk-title-movie:27205").assertDoesNotExist()
            compose.onNodeWithTag("talk-title-tv:70523").assertDoesNotExist()
            val card=compose.onNodeWithTag("talk-title-movie:522627").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val screen=compose.onNodeWithTag("talk-screen").fetchSemanticsNode().boundsInRoot
            assertEquals("The selected card must be centered in its retained stage",screen.center.x,card.center.x,2f)
            compose.runOnIdle{session.debugPreviewUserQuestion("Why would I enjoy it?")}
            compose.onNodeWithTag("talk-title-movie:522627").assertIsDisplayed()
            screenshot("talk-focused-179.png")
        } finally {session.close();scope.cancel()}
    }
    @Test fun voiceCardsFitBetweenTheAnimatedTopicAndControls() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
        try {
            session.debugPreviewTitles(titles,false)
            compose.setContent{AliflixMobileTheme{TalkVoiceScreen(session,{}, {})}}
            compose.onNodeWithTag("talk-topic").assertIsDisplayed()
            compose.onNodeWithTag("talk-orb").assertIsDisplayed()
            compose.onNodeWithTag("talk-title-movie:27205").assertIsDisplayed()
            val topic=compose.onNodeWithTag("talk-topic").fetchSemanticsNode().boundsInRoot
            for(title in titles) {
                val card=compose.onNodeWithTag("talk-title-${title.key}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("Each card must sit below the animated topic",card.top>=topic.bottom)
                assertTrue("Card metadata must retain real height",card.height>100*InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density)
            }
            screenshot("talk-cards-voice-178.png")
        } finally {session.close();scope.cancel()}
    }
    @Test fun chatCardsStayWithTheReplyAndKeepTheCompanionOrb() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope,restoreHistory=false)
        try {
            session.debugPreviewTitles(titles,true)
            compose.setContent{AliflixMobileTheme{TalkVoiceScreen(session,{}, {})}}
            compose.onNodeWithTag("talk-chat-orb").assertIsDisplayed()
            compose.onNodeWithTag("talk-title-movie:27205").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("talk-title-tv:70523").performScrollTo().assertIsDisplayed()
            screenshot("talk-cards-chat-178.png")
        } finally {session.close();scope.cancel()}
    }
    private fun screenshot(name:String){
        val i=InstrumentationRegistry.getInstrumentation();i.uiAutomation.takeScreenshot()?.let{bitmap->
            File(i.targetContext.getExternalFilesDir(null),name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
}
