package com.aliflix.app.talk

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.MediaType
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class TalkTitleNavigationDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun aTitleOpensNativeDetailsAndReturnsToTheSameConversation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAsk178")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val intent=Intent(context,TalkVoiceActivity::class.java).putExtra("validationFreshHistory",true).putExtra("textMode",true)
        ActivityScenario.launch<TalkVoiceActivity>(intent).use { scenario ->
            var chatId=""
            scenario.onActivity {
                it.voice.pauseSession()
                it.voice.debugPreviewTitles(listOf(TalkTitleCard(27205,MediaType.MOVIE,"Inception","2010")),true)
                chatId=it.voice.state.value.conversationId
            }
            compose.onNodeWithTag("talk-title-movie:27205").performClick()
            compose.waitUntil(30000){compose.onAllNodesWithContentDescription("Back to previous screen").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onNodeWithContentDescription("Back to previous screen").assertIsDisplayed()
            pressBack()
            compose.onNodeWithTag("talk-chat-orb").assertIsDisplayed()
            compose.onNodeWithTag("talk-title-movie:27205").assertIsDisplayed()
            scenario.onActivity {assertEquals(chatId,it.voice.state.value.conversationId)}
        }
    }
}
