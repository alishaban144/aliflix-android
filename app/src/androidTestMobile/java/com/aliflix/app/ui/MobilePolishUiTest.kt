package com.aliflix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import com.aliflix.app.model.*
import com.aliflix.app.recommendation.RecommendationCandidate
import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.discover.*
import com.aliflix.app.ui.theme.AliflixMobileTheme
import com.aliflix.app.ui.launch.AliflixLaunchOverlay
import com.aliflix.app.player.NativePlayerScreen
import com.aliflix.app.player.NativePlayerUi
import com.aliflix.app.downloads.DownloadSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MobilePolishUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longGenresNeverCompressScoreOrOverflowAtNarrowWidthsAndLargeFonts() {
        var width by mutableStateOf(320.dp)
        var scale by mutableFloatStateOf(1f)
        var editorial by mutableStateOf(true)
        var bookmarks = 0; var details = 0
        val item = RecommendationCandidate(Media(1,MediaType.MOVIE,
            "A Very Long Movie Title With Many Words And No Useful Breaking Point",
            year = "2026", runtime = "180 minutes", rating = 8.4,
            genres = listOf("Science Fiction And Extremely Long Supernatural Adventure", "International Documentary And Historical Drama")),
            matchScore = 97.8)
        compose.setContent { AliflixMobileTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) {
                Box(Modifier.width(width).background(Color.Black)) {
                    MobileAskResultCard(item,{details++},false,{bookmarks++},editorial)
                }
            }
        } }
        for (w in listOf(240.dp,280.dp,320.dp,400.dp)) for (f in listOf(1f,1.5f,2f)) {
            compose.runOnIdle { width = w; scale = f }
            val card = compose.onNodeWithTag("ask-result-${item.media.key}").fetchSemanticsNode().boundsInRoot
            val score = compose.onNodeWithTag("ask-result-score",useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(score.left >= card.left && score.right <= card.right+.5f)
            assertTrue(card.height < with(compose.density) { 360.dp.toPx() })
            if (w == 320.dp && f == 1f || w == 240.dp && f == 2f) capture("card-${w.value.toInt()}-font$f.png")
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("AI 97.8",useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1,layouts.single().lineCount)
            compose.onAllNodesWithTag("ask-result-genre",useUnmergedTree = true).fetchSemanticsNodes().forEach { genre ->
                assertTrue(genre.boundsInRoot.right <= card.right+.5f)
            }
        }
        compose.onNodeWithTag("ask-result-bookmark",useUnmergedTree = true).performClick()
        compose.onNodeWithTag("ask-result-details",useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(1,bookmarks); assertEquals(1,details); editorial = false }
        compose.onNodeWithText("TMDB 8.4",useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun liquidToggleFollowsHoistedStateAndDisabledControlsDoNotToggle() {
        var checked by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        compose.setContent { AliflixMobileTheme {
            AliflixLiquidToggle(checked,{checked=it},Modifier.testTag("liquid"),enabled)
        } }
        compose.onNodeWithTag("liquid").assertIsOff()
        capture("toggle-off.png")
        compose.onNodeWithTag("liquid").performClick().assertIsOn()
        capture("toggle-on.png")
        compose.runOnIdle { checked = false }
        compose.onNodeWithTag("liquid").assertIsOff()
        val bounds = compose.onNodeWithTag("liquid").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height >= with(compose.density) { 48.dp.toPx() })
        compose.runOnIdle { enabled = false }
        compose.onNodeWithTag("liquid").assertIsNotEnabled().performClick().assertIsOff()
    }

    @Test fun observedDiscoverDragStillScrollsTheCatalogue() {
        lateinit var state: LazyListState
        lateinit var tracker: DiscoverTouchTracker
        compose.setContent {
            state = rememberLazyListState(); tracker = remember { DiscoverTouchTracker() }
            LazyColumn(state = state, modifier = Modifier.size(320.dp,400.dp).observeDiscoverTouches(tracker).testTag("discover-observer")) {
                items(30) { Text("Item $it",Modifier.height(70.dp)) }
            }
        }
        compose.onNodeWithTag("discover-observer").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(state.firstVisibleItemIndex > 0); assertNotNull(tracker.point) }
    }

    @Test fun measuredHomeBarMovesContinuouslyAndKeepsOneBarAndStableScrollOffsets() {
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            MobilePinnedFilterLayout(state,{60f},
                bar = { _ -> Box(Modifier.fillMaxWidth().height(68.dp).testTag("filters")) },
                feed = { barHeight -> LazyColumn(state = state,modifier = Modifier.fillMaxSize().background(Color.Blue)) {
                    item { Box(Modifier.fillMaxWidth().height(300.dp)) }
                    item { Spacer(Modifier.height(barHeight)) }
                    items(20) { Text("Rail $it",Modifier.height(100.dp)) }
                } },modifier = Modifier.size(320.dp,600.dp).testTag("pinned-layout"))
        }
        assertEquals(Color.Blue.toArgb(),compose.onNodeWithTag("pinned-layout").captureToImage().toPixelMap()[20,20].toArgb())
        var lastY = Float.POSITIVE_INFINITY
        val stop = with(compose.density) { 300.dp.toPx() }.toInt()+90
        for (scroll in 0..stop step 30) {
            compose.runOnIdle { runBlocking { state.scrollToItem(0,scroll) } }
            compose.onAllNodesWithTag("filters").assertCountEquals(1)
            val y = compose.onNodeWithTag("filters").fetchSemanticsNode().boundsInRoot.top
            assertTrue(y >= 59.5f)
            assertTrue(y <= lastY+.5f)
            if (lastY.isFinite()) assertTrue(lastY-y <= 30.5f)
            lastY = y
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(4,20) } }
        compose.onNodeWithTag("filters").assertTopPositionInRootIsEqualTo(with(compose.density) { 60f.toDp() })
        compose.runOnIdle { assertEquals(4,state.firstVisibleItemIndex); assertEquals(20,state.firstVisibleItemScrollOffset) }
        assertEquals(com.aliflix.app.ui.theme.AliflixBackgroundBase.toArgb(),
            compose.onNodeWithTag("pinned-layout").captureToImage().toPixelMap()[20,20].toArgb())
        capture("home-pinned.png")
    }

    @Test fun launchWaitsForHomeAndCompletesOnlyAfterItsTimedExit() {
        var ready by mutableStateOf(false)
        var completed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { AliflixMobileTheme {
            AliflixLaunchOverlay(ready,{completed++}) { Text("Ready Home") }
        } }
        compose.mainClock.advanceTimeBy(320)
        capture("launch-logo-first.png")
        compose.mainClock.advanceTimeBy(300)
        capture("launch-title-reveal.png")
        compose.mainClock.advanceTimeBy(2600)
        compose.runOnIdle { assertEquals(0,completed); ready = true }
        compose.mainClock.advanceTimeBy(32)
        compose.mainClock.advanceTimeBy(250)
        compose.runOnIdle { assertEquals(0,completed) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(1,completed) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(1,completed) }
    }

    @Test fun nativeReferenceAnimationsRenderTheirIntermediateFramesOnDevice() {
        var checked by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { AliflixMobileTheme {
            val tracker = remember { DiscoverTouchTracker() }
            Column(Modifier.width(320.dp).height(400.dp).background(Color(0xFF0B0911))
                .observeDiscoverTouches(tracker).padding(24.dp).testTag("motion-showcase"),
                verticalArrangement = Arrangement.spacedBy(30.dp)) {
                DiscoverLiveOrb(tracker,Modifier.size(76.dp))
                AliflixLumaSpin(Modifier.size(65.dp))
                AliflixLiquidToggle(checked,{checked=it},Modifier.testTag("showcase-toggle"))
            }
        } }
        compose.mainClock.advanceTimeBy(32)
        capture("native-motion-start.png")
        compose.onNodeWithTag("showcase-toggle").performClick()
        compose.mainClock.advanceTimeBy(160)
        capture("liquid-morph.png")
        compose.mainClock.advanceTimeBy(352)
        capture("native-motion-half-second.png")
        compose.onNodeWithTag("motion-showcase").performTouchInput {
            down(Offset(width-10f,height-10f)); moveTo(Offset(width-15f,height-15f))
        }
        compose.mainClock.advanceTimeBy(500)
        capture("orb-tracks-touch.png")
        compose.onNodeWithTag("motion-showcase").performTouchInput { up() }
    }

    @Test fun betaBadgeRemainsVisibleDuringSyncAndDoesNotChangeTheButtonHeight() {
        var status by mutableStateOf<String?>(null)
        var fontScale by mutableFloatStateOf(1f)
        compose.setContent { AliflixMobileTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale)) {
            NativePlayerScreen(NativePlayerUi(title = "Fixture",ready = true,
                audioSyncAvailable = true,audioSyncApplied = true,audioSyncState = status),null)
            }
        } }
        compose.onNodeWithContentDescription("Audio & Subtitles").performClick()
        for (scale in listOf(1f,2f)) {
            compose.runOnIdle { fontScale = scale; status = null }
            compose.onNodeWithText("BETA",useUnmergedTree = true).assertIsDisplayed()
            val idle = compose.onNodeWithText("Sync with Audio").fetchSemanticsNode().boundsInRoot.height
            capture("sync-beta-idle-$scale.png")
            compose.runOnIdle { status = "Syncing…" }
            compose.onNodeWithText("BETA",useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Cancel").assertIsDisplayed()
            val processing = compose.onNodeWithText("Syncing…").fetchSemanticsNode().boundsInRoot.height
            assertEquals(idle,processing,.5f)
            capture("sync-beta-processing-$scale.png")
        }
    }

    @Test fun downloadTierMenuMigratesAndPersistsWithoutChangingStreamingQuality() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("offline-downloads",android.content.Context.MODE_PRIVATE)
        preferences.edit().remove("qualityTier").putInt("quality",720).commit()
        compose.setContent { AliflixMobileTheme { Box(Modifier.width(320.dp)) { DownloadSettings() } } }
        compose.onNodeWithText("Medium ▾").performClick()
        listOf("Smaller file","Balanced","Best available").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1,layouts.single().lineCount)
        }
        capture("download-quality-menu.png")
        compose.onNodeWithText("High").performClick()
        compose.onNodeWithText("High ▾").assertIsDisplayed()
        compose.runOnIdle { assertEquals("HIGH",preferences.getString("qualityTier",null)) }
    }
    private fun capture(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"mobile-polish").apply { mkdirs() }
        compose.waitForIdle()
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory,name).outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        screenshot.recycle()
    }

}
