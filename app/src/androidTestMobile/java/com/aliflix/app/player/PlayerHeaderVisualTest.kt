package com.aliflix.app.player

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.Episode
import com.aliflix.app.ui.theme.AliflixMobileTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Full physical-device player renders, with decoded video and the production overlay. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerHeaderVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fullPlayerPortraitAndLandscapeKeepActionsClearOfIdentityAndTransport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val video = File(instrumentation.targetContext.cacheDir,"player-visual.mp4")
        instrumentation.context.assets.open("cast-test.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        lateinit var engine: ExoPlayer
        compose.runOnUiThread {
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window,false)
            WindowCompat.getInsetsController(compose.activity.window,compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
            engine = ExoPlayer.Builder(compose.activity).build().apply {
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(video)))
                prepare()
            }
        }
        var ready = false
        compose.waitUntil(20_000) {
            compose.runOnUiThread { ready = engine.playbackState == Player.STATE_READY }
            ready
        }
        compose.runOnUiThread { engine.seekTo(2_000) }
        var title by mutableStateOf("The Girl Next Door")
        var episodic by mutableStateOf(false)
        var landscape by mutableStateOf(false)
        var fontScale by mutableFloatStateOf(1f)
        try {
            compose.setContent { AliflixMobileTheme {
                landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale)) {
                Box(Modifier.fillMaxSize().background(Color.Black).testTag("full-player")) {
                    AndroidView(factory = { context -> PlayerView(context).apply { player = engine; useController = false } },
                        modifier = Modifier.fillMaxSize())
                    NativePlayerScreen(NativePlayerUi(title = title,
                        detail = if (episodic) "S12 E24 · The Long Road Home" else "2004",
                        ready = true, episodes = if (episodic) listOf(Episode(seasonNumber = 12,number = 24,title = "The Long Road Home")) else emptyList()),engine)
                }
            } } }
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                compose.runOnUiThread { compose.activity.requestedOrientation = orientation }
                compose.waitUntil(10_000) { landscape == (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) }
                for (series in listOf(false,true)) for (font in listOf(1f,2f)) {
                    compose.runOnIdle {
                        fontScale = font
                        episodic = series
                        title = if (series) "A Very Long Series Title Across the Edge of Tomorrow" else "The Girl Next Door"
                    }
                    compose.waitForIdle()
                    val labels = listOf("Back","Subtitles","Quality","Rotate","More") + if (series) listOf("Episodes") else emptyList()
                    val bounds = labels.map { label ->
                        val node = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode()
                        val target = node.touchBoundsInRoot
                        val minimum = with(compose.density) { 48.dp.toPx() }
                        assertTrue("$label retains 48dp target",target.width >= minimum-.5f && target.height >= minimum-.5f)
                        target
                    }
                    bounds.forEachIndexed { i, a -> bounds.drop(i+1).forEach { b -> assertFalse("Actions must never overlap",a.overlaps(b)) } }
                    if (!landscape) {
                        val more = compose.onNodeWithContentDescription("More").fetchSemanticsNode().boundsInRoot
                        val rotate = compose.onNodeWithContentDescription("Rotate").fetchSemanticsNode().boundsInRoot
                        val back = compose.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot
                        assertEquals("Portrait More stays beside the other actions",rotate.center.y,more.center.y,1f)
                        assertEquals("Portrait actions align with navigation",back.center.y,more.center.y,1f)
                        assertTrue("Portrait actions end at the right edge",more.right > compose.onNodeWithTag("full-player").fetchSemanticsNode().boundsInRoot.right-with(compose.density) { 20.dp.toPx() })
                        val identity = compose.onNodeWithTag("player-title").fetchSemanticsNode().boundsInRoot
                        val detail = compose.onNodeWithTag("player-detail").fetchSemanticsNode().boundsInRoot
                        assertTrue("Identity stays clear of the aligned controls",identity.top >= more.bottom)
                        assertEquals("Title and episode details share an edge",identity.left,detail.left,1f)
                        assertTrue("Long episode details retain the full header width",detail.width <= compose.onNodeWithTag("full-player").fetchSemanticsNode().boundsInRoot.width)
                        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                        compose.onNodeWithTag("player-detail").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        val layout = layouts.single()
                        capture("header-before-readability-check.png")
                        assertFalse("Episode name remains readable: series=$series font=$font size=${layout.size} lines=${layout.lineCount} widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight}",layout.hasVisualOverflow)
                    }
                    val play = compose.onNodeWithContentDescription("Play").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    bounds.forEach { assertFalse("Header must clear transport",it.overlaps(play)) }
                    assertFalse("Title must clear transport",compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.overlaps(play))
                    compose.onNodeWithText(if (series) "S12 E24 · The Long Road Home" else "2004").assertIsDisplayed()
                    capture("full-player-${if (landscape) "landscape" else "portrait"}-${if (series) "series" else "movie"}-font$font.png")
                }
            }
        } finally {
            compose.runOnUiThread { engine.release(); compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            video.delete()
        }
    }

    @Test fun upperActionsHaveNoCardPixelsAndNeverMoveWhenPressed() {
        compose.setContent { AliflixMobileTheme {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                MobilePlayerTopBar("Silo",true,{},{},{},{},{},detail = "S1 E6",onQuality = {})
            }
        } }
        for (label in listOf("Episodes","Subtitles","Quality","Rotate","More")) {
            val node = compose.onNodeWithContentDescription(label)
            val before = node.fetchSemanticsNode().boundsInRoot
            node.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(500)
            assertEquals("No scale or lift on press",before,node.fetchSemanticsNode().boundsInRoot)
            val pixels = node.captureToImage().toPixelMap()
            val inset = with(compose.density) { 10.dp.toPx() }.toInt()
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (x < inset || x >= pixels.width-inset || y < inset || y >= pixels.height-inset)
                    assertEquals("$label has no surrounding card",Color.Black.toArgb(),pixels[x,y].toArgb())
            }
            node.performTouchInput { up() }
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = File(instrumentation.targetContext.filesDir,"mobile-polish").apply { mkdirs() }
        File(folder,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
