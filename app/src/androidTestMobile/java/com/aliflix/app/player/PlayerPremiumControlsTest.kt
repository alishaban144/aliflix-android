package com.aliflix.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.aliflix.app.ui.theme.AliflixMobileTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerPremiumControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun accessibleTimelineSeeksAndRejectsUnknownDuration() {
        var duration by mutableLongStateOf(100_000)
        var sought = -1L
        compose.setContent { AliflixMobileTheme {
            MobilePlayerTimeline(25_000, duration, 60_000, { sought = it }, {})
        } }
        compose.onNodeWithContentDescription("Playback position")
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.8f)) }
        compose.runOnIdle { assertEquals(80_000, sought); duration = 0 }
        compose.onNodeWithContentDescription("Playback position")
            .performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(.2f)) }
        compose.runOnIdle { assertEquals(80_000, sought) }
    }

    @Test fun audioSheetKeepsItsCloseActionAtLargeFontInRtl() {
        compose.setContent { AliflixMobileTheme {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    NativePlayerScreen(NativePlayerUi(title = "The Terminator", detail = "1984", ready = true), null)
                }
            }
        } }
        compose.onNodeWithContentDescription("Audio & Subtitles").performClick()
        compose.onNodeWithContentDescription("Close player options").assertIsDisplayed()
        compose.onNodeWithText("Large").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Close player options").performClick()
        compose.onNodeWithContentDescription("Close player options").assertDoesNotExist()
    }
}
