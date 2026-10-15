package com.aliflix.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.ui.launch.AliflixLaunchOverlay
import com.aliflix.app.ui.theme.AliflixMobileTheme
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class MobileRefinementUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun subtitleShortcutOnlyShowsTracksAndSearchWhileMoreRetainsAdvancedSettings() {
        var loading by mutableStateOf(false)
        var tracks by mutableStateOf(listOf(track("a","Release A")))
        var active by mutableStateOf<SubtitleTrack?>(null)
        var searches = 0
        var disabled = 0
        compose.setContent { AliflixMobileTheme {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                NativePlayerScreen(NativePlayerUi(title = "Subtitle picker",ready = true,
                    playbackSelection = PlaybackSelection(Media(1,MediaType.MOVIE,"Subtitle picker"),source = PlaybackSource(MobilePlaybackProvider.FLIXER)),
                    subtitleTracks = tracks,activeSubtitleTrack = active,subtitleLoading = loading,
                    audioSyncAvailable = true),null,
                    onSubtitleSearch = { searches++; loading = true },
                    onSubtitle = { active = it }, onSubtitleDisable = { disabled++; active = null })
            }
        } }
        compose.onNodeWithContentDescription("Subtitles").performClick()
        compose.onNodeWithText("More Subtitles").assertIsDisplayed()
        for (text in listOf("AUDIO","SUBTITLE SYNC","Sync with Audio","Time Offset","SUBTITLE SIZE","BACKGROUND OPACITY"))
            compose.onNodeWithText(text).assertDoesNotExist()
        compose.onNodeWithText("Search").performClick()
        compose.runOnIdle { assertEquals(1,searches); assertTrue(loading); loading = false; tracks = listOf(track("b","Release B")) }
        compose.onNodeWithText("Release B").performClick()
        compose.runOnIdle { assertEquals("b",active?.id) }
        compose.onNodeWithText("Off").performClick()
        compose.runOnIdle { assertEquals(1,disabled); assertNull(active) }
        capture("subtitle-only-picker.png")
        compose.onNodeWithContentDescription("Close player options").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Audio & subtitles").performClick()
        compose.onNodeWithText("AUDIO").assertIsDisplayed()
        compose.onNodeWithText("SUBTITLE SYNC").assertIsDisplayed()
        compose.onNodeWithText("Time Offset").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SUBTITLE SIZE").performScrollTo().assertIsDisplayed()
        capture("advanced-settings-through-more.png")
    }

    @Test fun launchBrandFramesAndBriefStoriesLeadDirectlyIntoHome() {
        assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        compose.mainClock.autoAdvance = false
        var complete = 0
        var homeCompositions = 0
        compose.setContent { AliflixMobileTheme {
            AliflixLaunchOverlay(true,{complete++}) { SideEffect { homeCompositions++ }; Text("Ready Home") }
        } }
        for ((delta,name) in listOf(120L to "launch-120ms",180L to "launch-300ms",180L to "launch-480ms",
            320L to "launch-movies-800ms",400L to "launch-series-1200ms",500L to "launch-stories-1700ms",120L to "launch-stories-1820ms")) {
            compose.mainClock.advanceTimeBy(delta)
            capture("$name.png")
        }
        compose.runOnIdle { assertEquals(0,complete); assertEquals(1,homeCompositions) }
        compose.mainClock.advanceTimeBy(1040)
        compose.runOnIdle { assertEquals(1,complete); assertEquals(1,homeCompositions) }
        compose.onNodeWithText("Ready Home").assertIsDisplayed()
        capture("launch-home-ready.png")
    }

    @Test fun sharedRevealShowsEveryControlTogetherAndSettlesWithoutStagger() {
        assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        compose.mainClock.autoAdvance = false
        var visible by mutableStateOf(false)
        compose.setContent { AliflixMobileTheme {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                MobilePlayerControlsVisibility(visible,Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        MobilePlayerTopBar("Player",false,{},{},{},{},{},onQuality = {},modifier = Modifier.align(Alignment.TopCenter).testTag("reveal-header"))
                        MobilePlayerCenterControls(false,{},{},{},modifier = Modifier.align(Alignment.Center).testTag("reveal-transport"))
                        MobilePlayerTimeline(30_000,90_000,60_000,{}, {},modifier = Modifier.align(Alignment.BottomCenter).testTag("reveal-timeline"))
                    }
                }
            }
        } }
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeBy(64)
        val tags = listOf("reveal-header","reveal-transport","reveal-timeline")
        fun light(tag: String): Double {
            val pixels = compose.onNodeWithTag(tag).assertIsDisplayed().captureToImage().toPixelMap()
            var sum = 0.0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) sum += pixels[x,y].red
            return sum
        }
        val initial = tags.associateWith { light(it).also { value -> assertTrue("$it appears within 64ms without a child delay",value > 0.0) } }
        capture("player-reveal-64ms.png")
        compose.mainClock.advanceTimeBy(32)
        capture("player-reveal-96ms.png")
        compose.mainClock.advanceTimeBy(144)
        val settled = tags.associateWith { light(it) }
        tags.forEach { assertTrue("$it shares the same completion window",settled.getValue(it) >= initial.getValue(it)) }
        capture("player-reveal-240ms.png")
        compose.mainClock.advanceTimeBy(240)
        tags.forEach { assertEquals("$it has settled by 240ms including frame scheduling",settled.getValue(it),light(it),.01) }
    }

    @Test fun playbackSurfaceHasNoHardCircularOutline() {
        compose.setContent { AliflixMobileTheme {
            Box(Modifier.fillMaxSize().background(Color.Black)) { MobilePlayerCenterControls(false,{},{},{}) }
        } }
        val pixels = compose.onNodeWithContentDescription("Play").captureToImage().toPixelMap()
        val edge = with(compose.density) { 18.dp.toPx() }.toInt()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (x < edge || x >= pixels.width-edge || y < edge || y >= pixels.height-edge)
                assertEquals("No outlined ring surrounds playback",Color.Black.toArgb(),pixels[x,y].toArgb())
        }
    }

    @Test fun playbackGlyphHasSoftCornersInPlayAndPause() {
        compose.mainClock.autoAdvance = false
        var playing by mutableStateOf(true)
        compose.setContent { AliflixMobileTheme {
            Box(Modifier.fillMaxSize().background(Color.Black),contentAlignment = Alignment.Center) {
                MobileMorphPlaybackIcon(playing,Modifier.size(192.dp).testTag("soft-playback-glyph"))
            }
        } }
        fun sample(x: Float,y: Float): Float {
            val pixels = compose.onNodeWithTag("soft-playback-glyph").captureToImage().toPixelMap()
            return pixels[(pixels.width*x/24f).toInt(),(pixels.height*y/24f).toInt()].red
        }
        assertTrue("Pause retains its fine solid bars",sample(7f,12f) > .9f)
        assertTrue("Pause corners are curved rather than square",sample(6.1f,5.1f) < .1f)
        capture("playback-glyph-pause-soft.png")
        compose.runOnIdle { playing = false }
        compose.mainClock.advanceTimeBy(1000)
        assertTrue("Play retains its solid interior",sample(10f,12f) > .9f)
        assertTrue("The play halves meet without a seam",sample(13f,12f) > .9f)
        assertTrue("Play upper corner is softened",sample(8.06f,5.12f) < .1f)
        assertTrue("Play tip is rounded",sample(17.9f,12f) < .1f)
        capture("playback-glyph-play-soft.png")
    }

    private fun track(id: String,release: String) = SubtitleTrack(id,"en","English",release,"$id.srt",false,"srt",null,id)

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = File(instrumentation.targetContext.filesDir,"mobile-refinement").apply { mkdirs() }
        File(folder,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
