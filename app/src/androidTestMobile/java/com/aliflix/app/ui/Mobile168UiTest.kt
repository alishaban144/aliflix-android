package com.aliflix.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.account.AccountState
import com.aliflix.app.DetailUiState
import com.aliflix.app.model.*
import com.aliflix.app.recommendation.RecommendationAiModel
import com.aliflix.app.ui.theme.AliflixMobileTheme
import com.aliflix.app.player.MobileEpisodesOverlay
import org.junit.*
import org.junit.Assert.*
import java.io.File

class Mobile168UiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun compactDetailsKeepEveryActionReachableAtLargeFont() {
        var played=false
        val normalFont=InstrumentationRegistry.getArguments().getString("normalFont")=="true"
        compose.setContent { AliflixMobileTheme {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,if(normalFont) 1f else 1.7f)) {
                Box((if(normalFont) Modifier.fillMaxWidth() else Modifier.width(320.dp)).fillMaxHeight()) {
                    DetailScreen(DetailUiState(item=Media(42,MediaType.MOVIE,"The Midnight Feature")),false,false,
                        emptyMap(),null,{}, {played=true}, {_,_->},{},{},{},{},PlaybackProviderId.MOVIEPIRE,{})
                }
            }
        } }
        for(label in listOf("Like","Add to My List")) {
            val node=compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode()
            val min=with(compose.density){48.dp.toPx()}
            assertTrue(node.boundsInRoot.width>=min-.5f);assertTrue(node.boundsInRoot.height>=min-.5f)
        }
        compose.onNodeWithContentDescription("Watch Together").assertDoesNotExist()
        compose.onNodeWithText("Play",substring=true).performClick()
        compose.runOnIdle { assertTrue(played) }
        capture(if(normalFont) "details-actions-normal-font.png" else "details-actions-large-font.png")
    }
    @Test fun streamingSourcesAreTheLastSettingsSectionAndReadOnly() {
        var selections=0
        compose.setContent { AliflixMobileTheme {
            MobileSettingsDialog(AccountState(),{},PlaybackProviderId.MOVIEPIRE,{selections++},{},true,{},
                RecommendationAiModel.entries.first(),{},SubtitleLanguage.ENGLISH,{},true,{},MobileUpdateUiState(),{},{},{},{},{})
        } }
        compose.onNodeWithText("STREAMING SOURCES").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Filmo").assertDoesNotExist()
        capture("streaming-sources-collapsed.png")
        compose.onNodeWithText("Streaming sources").performScrollTo().performClick()
        for(name in listOf("Flixer","Serienstream","Filmo","AniWorld","CineJoy","Miruro","AniKuro")) {
            compose.onNodeWithText(name).performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        }
        compose.runOnIdle { assertEquals(0,selections) }
        capture("streaming-sources-readonly.png")
    }
    @Test fun episodeBrowserKeepsCurrentEpisodeAndVerifiedRatingWhileBrowsingAnotherSeason() {
        var requested=0;var played:Episode?=null
        val current=Episode(1,1,"Secrets",imdbRating=8.5,imdbRatingState=RatingSourceState.VERIFIED)
        compose.setContent { AliflixMobileTheme {
            MobileEpisodesOverlay(true,false,listOf(current,Episode(2,1,"Beginnings")),1,1,
                {played=it},{},seasonOptions=listOf(Season(1,"Season 1",1),Season(2,"Season 2",1),Season(3,"Season 3",1)),
                onSeasonSelected={requested=it})
        } }
        compose.onNodeWithText("Secrets").assertIsDisplayed()
        compose.onNodeWithContentDescription("IMDb 8.5").assertIsDisplayed()
        capture("episodes-season-one.png")
        compose.onNodeWithText("S2").performClick()
        compose.onNodeWithText("Beginnings").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2,requested);assertNull(played) }
        capture("episodes-season-two.png")
        compose.onNodeWithText("Beginnings").performClick()
        compose.runOnIdle { assertEquals(2,played!!.seasonNumber) }
    }
    private fun capture(name:String) {
        val instrument=InstrumentationRegistry.getInstrumentation();val image=instrument.uiAutomation.takeScreenshot()
        File(instrument.targetContext.getExternalFilesDir(null),name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };image.recycle()
    }
}
