@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.party

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.MainActivity
import com.aliflix.app.model.*
import com.aliflix.app.player.*
import com.aliflix.app.downloads.DownloadSession
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import java.io.File

/** Opt-in: run host on Pixel and guest on Redmi against the deployed Worker. */
class WatchPartyDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private fun await(seconds: Int = 90, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + seconds * 1000
        while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
        assertTrue("Watch Party condition timed out", condition())
    }
    private fun <T> main(block: () -> T): T { var value: T? = null; instrument.runOnMainSync { value = block() }; @Suppress("UNCHECKED_CAST") return value as T }
    private fun capture(name: String) { val image = instrument.uiAutomation.takeScreenshot(); val folder = File(context.getExternalFilesDir(null), "party-proof").apply { mkdirs() }; File(folder,name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; image.recycle() }

    @Test fun twoPhonesUseIndependentProvidersAndAuthoritativeControls() {
        assumeTrue(args.getString("partyRole") in listOf("host", "guest"))
        grantNativeFixtureNetworkPermission()
        context.stopService(Intent(context, NativePlaybackService::class.java))
        val host = args.getString("partyRole") == "host"
        val client = main { WatchPartyClient.get(context).also { it.leave() } }
        val selection = PlaybackSelection(Media(70523, MediaType.TV, "Dark", posterPath = "/apbrbWs8M9lyOpJYU5WXrpFbk1Z.jpg", year = "2017", imdbId = "tt5753856", originalLanguage = "de"),1,1,
            source = PlaybackSource(if(host) MobilePlaybackProvider.SERIENSTREAM else PlaybackProviderId.CINEJOY))
        if(host) {
            runBlocking { withContext(Dispatchers.Main) { client.create(selection) } }
            await { client.state.value.data != null }
            File(context.filesDir,"party-fixture.json").writeText(JSONObject().put("room",client.state.value.room).put("invite",client.state.value.invite).toString())
        } else {
            val invitation=android.net.Uri.parse(WatchPartyClient.BASE+"/party/"+args.getString("partyRoom")+"#"+args.getString("partyInvite"))
            ActivityScenario.launch<WatchPartyActivity>(Intent(Intent.ACTION_VIEW,invitation,context,WatchPartyActivity::class.java)).use {
                await { client.state.value.members.size == 2 }
                compose.onNodeWithText("Dark").assertIsDisplayed()
                compose.onNodeWithText("S1 · E1").assertIsDisplayed()
                capture("redmi-invitation-title-match.png")
            }
        }
        // Resolve the requested provider itself, so a successful alternate cannot hide a broken adapter.
        lateinit var request: NativePlaybackRequest
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { activity ->
            lateinit var owner: MainActivity
            lateinit var resolverHost: android.widget.FrameLayout
            activity.onActivity { owner=it;resolverHost=DownloadSession.get(it).host }
            request = runBlocking { withContext(Dispatchers.Main) {
                NativeStreamResolver(owner, (owner.application as com.aliflix.app.AliflixApplication).playbackProgressStore, resolverHost).use {
                    it.resolve(selection, 0, emptySet(), audioLanguage = if(host) "de" else null) {}
                }
            } }
        }
        val intent=Intent(context,NativePlayerActivity::class.java).putExtra("selection",selection.nativeJson()).putExtra("request",request.copy(playing=false,selectionJson=selection.nativeJson()).toJson())
        ActivityScenario.launch<NativePlayerActivity>(intent).use { activity ->
            await { main { NativePlaybackService.playbackReady && NativePlaybackService.hasSelectedAudio && NativePlaybackService.renderedStreamUrl != null } }
            assertEquals(selection.source.identity, main { nativeSelection(NativePlaybackService.activeRequest!!.selectionJson).source.identity })
            if(host) {
                await(180) { client.state.value.members.size == 2 && client.state.value.members.all { it.optString("status") in listOf("ready","playing") } }
                activity.onActivity { it.playbackController!!.play() }
                await { client.state.value.data?.optBoolean("playing") == true }
                // Reconnection is performed by the second real phone while this clock keeps running.
                await { client.state.value.members.any { it.optString("status") == "buffering" } }
                Thread.sleep(1000)
                assertTrue(client.state.value.data?.optBoolean("playing") == true)
                await { client.state.value.members.none { it.optString("status") == "buffering" } }
                Thread.sleep(3000)
                activity.onActivity { it.playbackController!!.seekTo(60000) }
                await { client.state.value.data?.optLong("position") in 59500L..60500L }
                Thread.sleep(5000)
                activity.onActivity { it.playbackController!!.pause() }
                await { client.state.value.data?.optBoolean("playing") == false }
                val other=client.state.value.members.first { it.optString("id") != client.state.value.self }.getString("id")
                activity.onActivity { it.startActivity(Intent(it,WatchPartyActivity::class.java).putExtra("selection",selection.nativeJson())) }
                run {
                    compose.onNodeWithContentDescription("Manage Guest").performScrollTo().performClick()
                    compose.onNodeWithText("Seek").performScrollTo().performClick()
                    compose.waitUntil(5000){client.state.value.members.first { it.optString("id")==other }.getJSONObject("rights").getBoolean("seek")}
                    capture("pixel-individual-permission.png")
                    compose.onNodeWithText("Done").performClick()
                    compose.onNodeWithContentDescription("Back").performScrollTo().performClick()
                }
                await { client.state.value.data?.optLong("position") in 89500L..90500L }
                activity.onActivity { it.startActivity(Intent(it,WatchPartyActivity::class.java).putExtra("selection",selection.nativeJson())) }
                run {
                    compose.onNodeWithContentDescription("Manage Guest").performScrollTo().performClick()
                    compose.onNodeWithText("Make host").performScrollTo().performClick()
                    compose.waitUntil(5000){!client.state.value.host}
                    compose.onNodeWithContentDescription("Back").performScrollTo().performClick()
                }
                await { !client.state.value.host }
                await { client.state.value.content?.optInt("episode") == 2 }
                await { main { NativePlaybackService.activeRequest?.selectionJson?.let { nativeSelection(it).episodeNumber } == 2 && NativePlaybackService.playbackReady && NativePlaybackService.hasSelectedAudio && NativePlaybackService.renderedStreamUrl != null } }
                capture("pixel-party-episode2.png")
                await { !client.state.value.active }
            } else {
                await { client.state.value.data?.optBoolean("playing") == true }
                await { itPosition(activity) > 1000 }
                main { client.reconnectNow() }
                await { client.clock.ready && client.state.value.members.size == 2 && client.state.value.phase != "reconnecting" }
                // Publish an actual participant buffering state without disturbing the host stream.
                main { client.status("buffering") }
                Thread.sleep(2500)
                main { client.status("ready") }
                await { client.state.value.data?.optLong("position") in 59500L..60500L }
                await { kotlin.math.abs(itPosition(activity) - PartyProtocol.target(client.state.value.data!!,client.clock.serverNow())) < 1800 }
                await { client.state.value.data?.optBoolean("playing") == false }
                // Host-only pause/play is denied even when a local MediaSession command is attempted.
                activity.onActivity { it.playbackController!!.play() }
                Thread.sleep(1000)
                var playing=false;activity.onActivity { playing=it.playbackController!!.playWhenReady };assertFalse(playing)
                await { client.state.value.can("seek") }
                activity.onActivity { it.playbackController!!.seekTo(90000) }
                await { client.state.value.data?.optLong("position") in 89500L..90500L }
                capture("redmi-party-permission.png")
                await { client.state.value.host }
                main { client.episode(selection.copy(episodeNumber=2)) }
                await { client.state.value.content?.optInt("episode") == 2 }
                await { main { NativePlaybackService.activeRequest?.selectionJson?.let { nativeSelection(it).episodeNumber } == 2 && NativePlaybackService.playbackReady && NativePlaybackService.hasSelectedAudio && NativePlaybackService.renderedStreamUrl != null } }
                await { client.state.value.members.all { it.optString("status") in listOf("ready", "playing") } }
                capture("redmi-party-episode2.png")
                Thread.sleep(1000)
                main { client.leave(end=true) }
            }
            File(context.getExternalFilesDir(null),"party-proof/${if(host)"host" else "guest"}-result.json").writeText(JSONObject()
                .put("provider",selection.source.identity.name).put("device",android.os.Build.MODEL).put("decodedAudio",true)
                .put("decodedVideo",true).put("twoPhones",true).put("permissions",true).put("reconnect",true).put("bufferingProtocol",true).put("seek",true).put("hostTransfer",true).put("episodeChange",true).put("episode2Decoded",true).toString())
        }
    }
    private fun itPosition(activity: ActivityScenario<NativePlayerActivity>): Long { var position=0L;activity.onActivity {position=it.playbackController?.currentPosition?:0};return position }
}
