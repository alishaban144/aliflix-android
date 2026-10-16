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
    @Test fun conversationRequiresStartAndPrivacyControlsRemainReachable() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val session=TalkVoiceSession(InstrumentationRegistry.getInstrumentation().targetContext,scope)
        var started=false;var closed=false
        try {
            compose.setContent { AliflixMobileTheme { TalkVoiceScreen(session,{started=true},{closed=true}) } }
            Thread.sleep(900)
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"talk-particle-screen.png")
                    .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
            compose.onNodeWithText("Start").assertIsDisplayed().performClick()
            compose.runOnIdle { assertTrue(started);assertEquals("Ready",session.state.value.phase) }
            compose.onNodeWithContentDescription("Privacy").performClick()
            compose.onNodeWithText("Keep history").assertIsDisplayed()
            compose.onNodeWithText("Delete history").performClick()
            compose.onNodeWithContentDescription("Close panel").performClick()
            compose.onNodeWithContentDescription("Transcript").performClick()
            compose.onNodeWithText("Transcript").assertIsDisplayed()
            compose.onNodeWithContentDescription("Close panel").performClick()
            compose.onNodeWithContentDescription("Close conversation").performClick()
            compose.runOnIdle { assertTrue(closed) }
        } finally { session.close();scope.cancel() }
    }
}
