package com.aliflix.app.party

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.aliflix.app.MainActivity
import com.aliflix.app.model.*
import com.aliflix.app.player.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

class WatchPartyPlaybackConsentTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun otherTitlesAndEpisodesRequireConsentAndRepeatedTapsOnlyOpenOnePrompt() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePartyConsent") == "true")
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val client = WatchPartyClient.get(context)
        val original = PlaybackSelection(Media(70523, MediaType.TV, "Dark", originalLanguage = "de"), 1, 1)
        context.stopService(Intent(context, NativePlaybackService::class.java))
        instrument.runOnMainSync { client.leave() }
        runBlocking { withContext(Dispatchers.Main) { client.create(original) } }
        compose.waitUntil(15000) { client.state.value.content != null }
        val room = client.state.value.room
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            var proceeds = 0
            scenario.onActivity { withPlaybackRoomConsent(it, original) { proceeds++ } }
            assertEquals(1, proceeds)
            val other = PlaybackSelection(Media(218, MediaType.MOVIE, "The Terminator"))
            scenario.onActivity { activity -> repeat(5) { withPlaybackRoomConsent(activity, other) { proceeds++ } } }
            compose.onNodeWithText("Leave Watch Together?").assertIsDisplayed()
            compose.onNodeWithText("The Terminator").assertIsDisplayed()
            val image = instrument.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "leave-party-consent.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            image.recycle()
            compose.onNodeWithText("Stay").performClick()
            assertEquals(1, proceeds); assertEquals(room, client.state.value.room)
            val episode = original.copy(episodeNumber=2, episodeTitle="Lies")
            scenario.onActivity { withPlaybackRoomConsent(it, episode) { proceeds++ } }
            compose.onNodeWithText("S1 · E2").assertIsDisplayed()
            compose.onNodeWithText("Leave & play").performClick()
            assertEquals(2, proceeds); assertFalse(client.state.value.active)
            scenario.onActivity { withPlaybackRoomConsent(it, other) { proceeds++ } }
            assertEquals(3, proceeds)
        }
    }
}
