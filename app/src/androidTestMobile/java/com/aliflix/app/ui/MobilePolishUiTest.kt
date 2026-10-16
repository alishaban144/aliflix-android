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
            val bookmark = compose.onNodeWithTag("ask-result-bookmark",useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val poster = compose.onNodeWithTag("ask-result-poster",useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Bookmark belongs to the card's upper right",bookmark.right > card.right-with(compose.density) { 16.dp.toPx() })
            assertTrue("Bookmark never overlays the poster",bookmark.left >= poster.right)
            assertTrue("Bookmark is anchored at the top",bookmark.top <= card.top+with(compose.density) { 13.dp.toPx() })
            assertTrue(score.left >= card.left && score.right <= card.right+.5f)
            assertTrue(card.height < with(compose.density) { 360.dp.toPx() })
            if (w >= 320.dp && f == 1f) assertTrue(card.height < with(compose.density) { 190.dp.toPx() })
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
        compose.onNodeWithTag("filters").performTouchInput {
            swipe(start=Offset(centerX,height-4f),end=Offset(centerX,4f),durationMillis=350)
        }
        compose.runOnIdle { assertTrue("A drag starting on the pinned filters scrolls Home",
            state.firstVisibleItemIndex>4 || state.firstVisibleItemScrollOffset>20) }
    }

    @Test fun launchHandsOffToHomeSkeletonWithoutWaitingForNetwork() {
        val motion = android.animation.ValueAnimator.areAnimatorsEnabled()
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
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertEquals(if (motion) 0 else 1,completed); assertFalse(ready) }
        compose.mainClock.advanceTimeBy(1600)
        compose.runOnIdle { assertEquals(1,completed); assertFalse(ready); ready = true }
        compose.onNodeWithText("Ready Home").assertIsDisplayed()
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
                playbackSelection = PlaybackSelection(Media(1,MediaType.MOVIE,"Fixture"),source = PlaybackSource(MobilePlaybackProvider.FLIXER)),
                audioSyncAvailable = true,audioSyncApplied = true,audioSyncState = status),null)
            }
        } }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Audio & subtitles").performClick()
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

    @Test fun failedAudioMatchOffersAnExplicitRetryWithoutReasonText() {
        var attempts = 0
        compose.setContent { AliflixMobileTheme {
            NativePlayerScreen(NativePlayerUi(title = "Fixture", ready = true,
                playbackSelection = PlaybackSelection(Media(1, MediaType.MOVIE, "Fixture")),
                audioSyncAvailable = true, audioSyncState = "Couldn't match this dialogue"), null,
                onSyncWithAudio = { attempts++ })
        } }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Audio & subtitles").performClick()
        compose.onNodeWithText("Couldn't match this dialogue").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, attempts) }
        capture("sync-retry.png")
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
    @Test fun libraryTabsAndLongGenresStayReadableOnNarrowScreensAtLargeFonts() {
        var selected by mutableIntStateOf(0)
        var scale by mutableFloatStateOf(1f)
        compose.setContent { AliflixMobileTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) {
                Column(Modifier.width(240.dp).background(Color(0xFF10111B)).testTag("library-fixture")) {
                    MobileLibraryTabs(listOf("My List" to 30,"Favorites" to 7,"History" to 30,"Downloads" to 2),selected,{selected=it})
                    MobileLibraryGenre("Science Fiction & Extremely Long International Fantasy",30,true,{})
                }
            }
        } }
        for (font in listOf(1f,1.5f,2f)) {
            compose.runOnIdle { scale = font }
            compose.onNodeWithTag("library-tab-3").assertIsDisplayed().performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(3,selected) }
            compose.onNodeWithTag("library-tab-0").assertIsDisplayed().performClick()
            capture("library-navigation-font$font.png")
        }
    }

    @Test fun liquidToggleHasNoDetachedDotInEitherDirection() {
        var checked by mutableStateOf(false)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        compose.setContent { AliflixMobileTheme {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides direction) {
                AliflixLiquidToggle(checked,{checked=it},Modifier.background(Color.Black).testTag("clean-toggle"))
            }
        } }
        for (rtl in listOf(LayoutDirection.Ltr,LayoutDirection.Rtl)) for (value in listOf(false,true)) {
            compose.runOnIdle { direction = rtl; checked = value }
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("clean-toggle").captureToImage().toPixelMap()
            val top = with(compose.density) { 7.dp.toPx() }.toInt()
            val aboveTrack = (0 until top).sumOf { y -> (0 until pixels.width).count { x -> pixels[x,y].red > .4f } }
            assertEquals("No detached dot above the switch", 0, aboveTrack)
        }
    }

    @Test fun launchAnimationDoesNotRecomposeHomeForEveryFrame() {
        val motion = android.animation.ValueAnimator.areAnimatorsEnabled()
        var compositions = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { AliflixMobileTheme {
            AliflixLaunchOverlay(true,{}) { SideEffect { compositions++ }; Text("Home composition fixture") }
        } }
        compose.mainClock.advanceTimeBy(32)
        val initial = compositions
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle { assertEquals("Home warms once before brand animation",1,compositions) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(1,compositions) }
        repeat(60) { compose.mainClock.advanceTimeBy(16) }
        compose.runOnIdle {
            assertEquals(1,initial)
            assertEquals(1,compositions)
        }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(1,compositions) }
    }

    @Test fun unframedPlayerActionsFitNarrowWidthsAndKeepTheirTargets() {
        var width by mutableStateOf(240.dp)
        var calls = 0
        var scale by mutableFloatStateOf(1f)
        compose.setContent { AliflixMobileTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) {
            Column(Modifier.width(width).background(Color(0xFF0B0911)).testTag("player-header-fixture")) {
                com.aliflix.app.player.MobilePlayerTopBar("A very long title for an episode on a narrow phone",true,
                    {calls++},{calls++},{calls++},{calls++},{calls++},detail = "Season 12 · Episode 24",onQuality = {calls++})
            }
        } } }
        for (w in listOf(240.dp,320.dp,700.dp)) for (f in listOf(1f,2f)) {
            compose.runOnIdle { width = w; scale = f }
            val root = compose.onNodeWithTag("player-header-fixture").fetchSemanticsNode().boundsInRoot
            for (label in listOf("Back","Episodes","Subtitles","Quality","Rotate","More")) {
                val button = compose.onNodeWithContentDescription(label).assertIsDisplayed()
                val bounds = button.fetchSemanticsNode().boundsInRoot
                assertTrue("$label fits at $w",bounds.left >= root.left-.5f && bounds.right <= root.right+.5f)
                button.performClick()
            }
            capture("player-header-${w.value.toInt()}-font$f.png")
        }
        compose.runOnIdle { assertEquals(36,calls) }
    }

    @Test fun playPauseMorphKeepsOneAccessibleActionAndDrawsIntermediateGeometry() {
        var playing by mutableStateOf(false)
        var calls = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { AliflixMobileTheme {
            Box(Modifier.width(240.dp).background(Color(0xFF0B0911)).testTag("morph-fixture")) {
                com.aliflix.app.player.MobilePlayerCenterControls(playing,{playing=!playing;calls++},{},{})
            }
        } }
        compose.mainClock.advanceTimeBy(32)
        val before = compose.onNodeWithTag("morph-fixture").captureToImage().toPixelMap()
        compose.onNodeWithContentDescription("Play").performClick()
        compose.mainClock.advanceTimeBy(96)
        capture("playback-morph-intermediate.png")
        compose.onAllNodesWithContentDescription("Pause").assertCountEquals(1)
        compose.mainClock.advanceTimeBy(1000)
        val after = compose.onNodeWithTag("morph-fixture").captureToImage().toPixelMap()
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width) if (before[x,y] != after[x,y]) changed++
        assertTrue(changed > 20)
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.runOnIdle { assertEquals(2,calls); assertFalse(playing) }
        capture("playback-morph-play.png")
    }

    private fun capture(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"mobile-polish").apply { mkdirs() }
        compose.waitForIdle()
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory,name).outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        screenshot.recycle()
    }

}
