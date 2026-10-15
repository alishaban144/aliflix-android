@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.party

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.aliflix.app.model.*
import com.aliflix.app.player.nativeJson
import org.junit.*
import org.junit.Assume.assumeTrue
import java.io.File

class WatchPartyUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("livePartyUI") == "true")
    private fun capture(name: String) { val image=instrument.uiAutomation.takeScreenshot();val folder=File(context.getExternalFilesDir(null),"party-ui").apply{mkdirs()};File(folder,name).outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle() }
    @Test fun createLobbyInviteConfirmationAndEndUseNativeControls() {
        enabled()
        lateinit var client:WatchPartyClient
        instrument.runOnMainSync {client=WatchPartyClient.get(context);client.leave()}
        val selected=PlaybackSelection(Media(70523,MediaType.TV,"Dark",posterPath="/apbrbWs8M9lyOpJYU5WXrpFbk1Z.jpg",year="2017",originalLanguage="de"),1,1)
        ActivityScenario.launch<WatchPartyActivity>(Intent(context,WatchPartyActivity::class.java).putExtra("selection",selected.nativeJson())).use {
            compose.onNodeWithText("Create room").performScrollTo().assertIsDisplayed();capture("create-portrait.png")
            compose.onNodeWithText("Create room").performClick()
            compose.waitUntil(20000){client.state.value.data!=null}
            compose.onNodeWithText("Dark").assertIsDisplayed()
            compose.onNodeWithText("Private room").assertIsDisplayed()
            compose.onNodeWithText("Ready").performScrollTo().assertIsDisplayed()
            capture("lobby-portrait.png")
            compose.onNodeWithText("Everyone controls").performScrollTo().performClick()
            compose.waitUntil(5000){client.state.value.data?.optBoolean("everyone")==true}
            compose.onNodeWithText("Invite",useUnmergedTree=true).performScrollTo().performClick()
            Thread.sleep(500);instrument.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            compose.onNodeWithContentDescription("Leave room").performScrollTo().performClick()
            compose.onNodeWithText("Leave or end room?").assertIsDisplayed();capture("leave-confirmation.png")
            compose.onNodeWithText("Stay").performClick()
            compose.onNodeWithContentDescription("Leave room").performScrollTo().performClick()
            compose.onNodeWithText("End for everyone").performClick()
            compose.waitUntil(5000){!client.state.value.active}
        }
    }
    @Test fun expiredHttpsInvitationHasARecoverableNativeState() {
        enabled()
        val uri=android.net.Uri.parse(WatchPartyClient.BASE+"/party/"+"1".repeat(32)+"#"+"a".repeat(43))
        ActivityScenario.launch<WatchPartyActivity>(Intent(Intent.ACTION_VIEW,uri,context,WatchPartyActivity::class.java)).use {
            compose.waitUntil(20000){WatchPartyClient.get(context).state.value.phase=="room-ended"}
            compose.onNodeWithText("Invitation expired").performScrollTo().assertIsDisplayed();capture("expired-invitation.png")
        }
    }
}
