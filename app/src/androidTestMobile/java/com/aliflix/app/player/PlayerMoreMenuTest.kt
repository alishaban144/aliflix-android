package com.aliflix.app.player

import androidx.compose.runtime.*
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.*
import com.aliflix.app.ui.theme.AliflixMobileTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlayerMoreMenuTest {
    @get:Rule val compose = createComposeRule()
    private val selection = PlaybackSelection(Media(101, MediaType.MOVIE, "Fixture"),
        source = PlaybackSource(MobilePlaybackProvider.FLIXER))

    @Test fun browsingSourcesDoesNotSwitchAndReopeningStartsAtMore() {
        var visible by mutableStateOf(true)
        var switches = 0
        var browsed: PlaybackProvider? = null
        compose.setContent { AliflixMobileTheme {
            MobilePlayerMoreSheet(
                visible, selection, listOf(MoviepireServerOption("current", "Current server", true)),
                false, null, {}, {}, { switches++ }, {}, { visible = false },
                providers = listOf(StreamingSourceOption(MobilePlaybackProvider.FLIXER, true),
                    StreamingSourceOption(PlaybackProviderId.CINEJOY)),
                onSelectProvider = { browsed = it },
                providerDiscovery = ProviderServerDiscovery(PlaybackProviderId.CINEJOY,
                    servers = listOf(MoviepireServerOption("other", "Other server", false))),
                onSelectProviderServer = { switches++ },
                onAudioBoostChange = {},
                onCast = {},
                onOpenWirelessDisplay = {},
                onStopPlayback = {},
            )
        } }
        captureMenu("player-more-home.png")
        compose.onNodeWithText("CineJoy").assertDoesNotExist()
        compose.onNodeWithText("Streaming source").performScrollTo().performClick()
        captureMenu("player-more-sources.png")
        compose.onNodeWithText("CineJoy").performClick()
        compose.onNodeWithText("Other server").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, switches); assertEquals(PlaybackProviderId.CINEJOY, browsed) }
        pressBack()
        compose.onNodeWithText("CineJoy").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to More").performClick()
        compose.onNodeWithContentDescription("Close More").performClick()
        compose.runOnIdle { visible = true }
        compose.onNodeWithText("Audio & subtitles").assertIsDisplayed()
        compose.onNodeWithText("CineJoy").assertDoesNotExist()
        compose.onNodeWithText("Streaming source").performScrollTo().performClick()
        compose.onNodeWithText("CineJoy").performClick()
        compose.onNodeWithText("Other server").performClick()
        compose.runOnIdle { assertEquals(1, switches); assertFalse(visible) }
    }

    @Test fun choosingCurrentServerDoesNotRestartPlayback() {
        var switches = 0
        var dismissed = false
        compose.setContent { AliflixMobileTheme {
            MobilePlayerMoreSheet(true, selection,
                listOf(MoviepireServerOption("current", "Current server", true)),
                false, null, {}, {}, { switches++ }, {}, { dismissed = true })
        } }
        compose.onNodeWithText("Streaming source").performScrollTo().performClick()
        compose.onNodeWithText("Flixer").performClick()
        compose.onNodeWithText("Current server").performClick()
        compose.runOnIdle { assertEquals(0, switches); assertTrue(dismissed) }
    }

    @Test fun audioBoostSwitchReflectsPreferenceAndOutputAvailability() {
        var enabled by mutableStateOf(false)
        var availability by mutableStateOf(AudioBoostAvailability.OFF)
        compose.setContent { AliflixMobileTheme {
            MobilePlayerMoreSheet(true, selection, emptyList(), false, null, {}, {}, {}, {}, {},
                audioBoost = enabled, audioBoostAvailability = availability,
                onAudioBoostChange = { enabled = it })
        } }
        compose.onNodeWithTag("player-audio-boost-switch").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("player-audio-boost-switch").assertIsOn()
        compose.runOnIdle { availability = AudioBoostAvailability.UNAVAILABLE }
        compose.onNodeWithText("Unavailable").assertIsDisplayed()
        compose.onNodeWithTag("player-audio-boost-switch").performClick().assertIsOff()
        compose.runOnIdle { enabled = true; availability = AudioBoostAvailability.ACTIVE }
        compose.onNodeWithTag("player-audio-boost-switch").assertIsOn()
    }

    private fun captureMenu(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val context = instrumentation.targetContext
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
