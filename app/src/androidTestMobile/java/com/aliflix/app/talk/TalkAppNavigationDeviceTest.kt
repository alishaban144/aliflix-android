package com.aliflix.app.talk

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** Real native routes; no library writes, downloads or playback are started. */
class TalkAppNavigationDeviceTest {
 @get:Rule val compose=createEmptyComposeRule()
 @Test fun appCommandsOpenTheActualSettingsDownloadsAndAskScreens(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  for(screen in listOf("settings","downloads","ask")) {
   val intent=Intent(context,TalkTitleActivity::class.java).putExtra("assistantAction",JSONObject().put("action","open_screen").put("screen",screen).toString())
   ActivityScenario.launch<TalkTitleActivity>(intent).use {
    when(screen){
     "settings"->{compose.waitUntil(15000){compose.onAllNodesWithContentDescription("Close settings").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()};compose.onNodeWithTag("settings-content").assertIsDisplayed()}
     "downloads"->{compose.waitUntil(15000){compose.onAllNodesWithTag("downloads-section").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()};compose.onNodeWithTag("downloads-section").assertIsDisplayed()}
     "ask"->{compose.waitUntil(15000){compose.onAllNodesWithTag("ask-unified").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()};compose.onNodeWithTag("ask-unified").assertIsDisplayed()}
    }
   }
  }
 }
}
