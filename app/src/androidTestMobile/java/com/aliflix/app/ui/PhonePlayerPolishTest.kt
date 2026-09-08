package com.aliflix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.TvNetworksUiState
import com.aliflix.app.account.AccountState
import com.aliflix.app.model.*
import com.aliflix.app.player.NativePlayerScreen
import com.aliflix.app.player.NativePlayerUi
import com.aliflix.app.recommendation.RecommendationAiModel
import com.aliflix.app.ui.theme.AliflixMobileTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PhonePlayerPolishTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeDissolveKeepsItsFrameAndPlayTargetsTheVisibleTitle() {
        val first = Media(101, MediaType.MOVIE, "First feature", backdropPath = "/fixture-first.jpg")
        val second = Media(102, MediaType.MOVIE, "Second feature", backdropPath = "/fixture-second.jpg")
        var played: Media? = null
        compose.mainClock.autoAdvance = false
        compose.setContent { AliflixMobileTheme {
            HomeFeed(HomeContent(first, listOf(ContentRail("Featured", listOf(first, second)))), TvNetworksUiState(),
                emptyList(), emptyList(), emptyList(), emptyMap(), {}, {}, { played = it }, {},
                rememberLazyListState(), HomeFilter.FOR_YOU, {}, Modifier)
        } }
        compose.mainClock.advanceTimeBy(100)
        val bounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(7550)
        assertEquals(bounds, compose.onRoot().fetchSemanticsNode().boundsInRoot)
        compose.mainClock.advanceTimeBy(1800)
        compose.onAllNodesWithText("Second feature").onFirst().assertIsDisplayed()
        capture("home71.png")
        compose.onNodeWithText("Play").performClick()
        compose.runOnIdle { assertEquals(second, played) }
    }

    @Test fun subtitleAutoDisplayUsesTheSameSwitchInteractionAsAppSettings() {
        var enabled by mutableStateOf(true)
        compose.setContent { AliflixMobileTheme {
            MobileSettingsDialog(AccountState(), {}, PlaybackProviderId.MOVIEPIRE, {}, {}, true, {},
                RecommendationAiModel.entries.first(), {}, SubtitleLanguage.ENGLISH, {}, enabled,
                { enabled = it }, MobileUpdateUiState(), {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Auto display").performScrollTo().assertIsDisplayed()
        capture("settings71.png")
        compose.onNodeWithText("Auto display").performClick()
        compose.runOnIdle { assertFalse(enabled) }
        compose.onNodeWithText("Auto display").performClick()
        compose.runOnIdle { assertTrue(enabled) }
    }

    @Test fun transportPressHasNoRectangularOrCircularIndication() {
        compose.setContent { AliflixMobileTheme {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                NativePlayerScreen(NativePlayerUi(title = "Player controls", ready = true), null)
            }
        } }
        val button = compose.onNodeWithContentDescription("Play")
        val before = button.captureToImage().asAndroidBitmap()
        button.performTouchInput { down(center) }
        val pressed = button.captureToImage().asAndroidBitmap()
        // Compare all pixels, including the formerly tinted rectangle surrounding the icon.
        assertTrue("Holding Play must not draw a ripple or button plate", before.sameAs(pressed))
        button.performTouchInput { up() }
        before.recycle(); pressed.recycle()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.waitForIdle()
        // Capture the complete window stack, including the settings Dialog window.
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
