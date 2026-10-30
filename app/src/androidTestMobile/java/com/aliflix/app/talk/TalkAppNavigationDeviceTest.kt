package com.aliflix.app.talk

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

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
     "ask"->{
      var talk:TalkVoiceActivity?=null
      try {
       compose.waitUntil(15000){
        InstrumentationRegistry.getInstrumentation().runOnMainSync{talk=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<TalkVoiceActivity>().singleOrNull()}
        talk!=null
       }
       InstrumentationRegistry.getInstrumentation().runOnMainSync{assertTrue("Ask opens the actual text conversation",talk!!.voice.state.value.textMode)}
       compose.onNodeWithTag("talk-composer").assertIsDisplayed()
       compose.onNodeWithText("Ask Aliflix").assertIsDisplayed()
      } finally {InstrumentationRegistry.getInstrumentation().runOnMainSync{talk?.finish()}}
     }
    }
   }
  }
 }
}
