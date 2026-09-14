package com.aliflix.app.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.ui.SubtitleView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.AliflixApplication
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeSubtitleRenderingTest {
    @Test fun firstFrameAndLateCaptionsNeverReprepareTheStream() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNativeFixtureNetworkPermission()
        val server = NativeBackgroundPlaybackTest.FixtureServer(instrumentation.context.assets.open("cast-test.mp4").use { it.readBytes() })
        val request = NativePlaybackRequest(server.url, "video/mp4", "https://fixture.aliflix.test/", "Aliflix test", "", "Startup without subtitles", 0, true, "")
        val payload = File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
        val scenario = ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java).putExtra("requestFile", payload.name), nativePhoneLaunchOptions())
        var buffering = 0
        var discontinuities = 0
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_BUFFERING) buffering++ }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, next: Player.PositionInfo, reason: Int) { discontinuities++ }
        }
        try {
            await("First frame with no external subtitles") { NativePlaybackService.renderedStreamUrl == server.url }
            scenario.onActivity {
                assertTrue(it.playbackController!!.playWhenReady)
                it.playbackController!!.addListener(listener)
                NativePlaybackService.updateSubtitles("WEBVTT\n\n00:00:00.000 --> 00:01:20.000\nLATE NONBLOCKING CAPTIONS\n\n", "en", "English")
            }
            awaitCaptions(scenario, "LATE NONBLOCKING CAPTIONS")
            scenario.onActivity {
                assertTrue(it.playbackController!!.playWhenReady)
                assertEquals("Caption attachment must not buffer", 0, buffering)
                assertEquals("Caption attachment must not seek/reload", 0, discontinuities)
                it.playbackController!!.removeListener(listener)
                it.playbackController!!.pause()
                it.playbackController!!.seekTo(1000)
            }
            await("Ready to verify real double-tap seek") { var ready = false; scenario.onActivity { ready = it.playbackController?.playbackState == Player.STATE_READY && it.playbackUiState.ready }; ready }
            fun doubleTap(ratio: Float) {
                repeat(2) {
                    val down = android.os.SystemClock.uptimeMillis()
                    scenario.onActivity { activity ->
                        val root = activity.window.decorView
                        val event = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, root.width * ratio, root.height * 0.4f, 0)
                        activity.dispatchTouchEvent(event); event.recycle()
                    }
                    Thread.sleep(30)
                    scenario.onActivity { activity ->
                        val root = activity.window.decorView
                        val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, root.width * ratio, root.height * 0.4f, 0)
                        activity.dispatchTouchEvent(event); event.recycle()
                    }
                    Thread.sleep(70)
                }
            }
            var expected = 0L
            scenario.onActivity { expected = minOf(16_000, it.playbackController!!.duration) }
            doubleTap(0.9f)
            await("Double-tap changes the real Media3 position") { var reached = false; scenario.onActivity { reached = kotlin.math.abs(it.playbackController!!.currentPosition - expected) < 300 }; reached }
            expected = (expected - 15_000).coerceAtLeast(0)
            doubleTap(0.1f)
            await("Double-tap rewinds the real Media3 position") { var reached = false; scenario.onActivity { reached = kotlin.math.abs(it.playbackController!!.currentPosition - expected) < 300 }; reached }
        } finally { scenario.close(); context.stopService(Intent(context, NativePlaybackService::class.java)); server.close(); payload.delete() }
    }

    @Test fun captionsAppearAtStartupAndAfterLateDownloadWithoutPositionControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNativeFixtureNetworkPermission()
        val settings = (context.applicationContext as AliflixApplication).playerSettingsStore
        val previous = settings.settings.value.subtitleVerticalOffsetDp
        settings.updateSubtitleVerticalOffsetDp(0)
        val server = NativeBackgroundPlaybackTest.FixtureServer(instrumentation.context.assets.open("cast-test.mp4").use { it.readBytes() })
        val vtt = "WEBVTT\n\n00:00:00.000 --> 00:01:20.000\nAUTOMATIC CAPTIONS\n\n"
        val request = NativePlaybackRequest(server.url, "video/mp4",
            "https://fixture.aliflix.test/", "Aliflix test", "", "Caption verification", 3000, true, vtt)
        val payload = File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
        val scenario = ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java)
            .putExtra("requestFile", payload.name), nativePhoneLaunchOptions())
        try {
            awaitCaptions(scenario, "AUTOMATIC CAPTIONS")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitCaptions(scenario, "AUTOMATIC CAPTIONS")
            scenario.onActivity { NativePlaybackService.updateSubtitles("") }
            await("Captions disabled") { var cleared = false; scenario.onActivity { cleared = it.playbackController?.currentCues?.cues.isNullOrEmpty() }; cleared }
            scenario.onActivity { it.playbackController!!.pause() }
            await("Paused before late subtitle attachment") { var paused = false; scenario.onActivity { paused = it.playbackController?.playWhenReady == false }; paused }
            var pausedAt = 0L
            scenario.onActivity {
                pausedAt = it.playbackController!!.currentPosition
                NativePlaybackService.updateSubtitles(vtt.replace("AUTOMATIC CAPTIONS", "LATE CAPTIONS"), "en", "English")
            }
            awaitCaptions(scenario, "LATE CAPTIONS")
            scenario.onActivity {
                assertFalse("Loading subtitles must not unpause video", it.playbackController!!.playWhenReady)
                assertTrue("Loading subtitles must preserve position", kotlin.math.abs(it.playbackController!!.currentPosition - pausedAt) < 500)
            }
            scenario.recreate()
            awaitCaptions(scenario, "LATE CAPTIONS")
            assertEquals("No position-control interaction is required", 0, settings.settings.value.subtitleVerticalOffsetDp)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "player71-landscape.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            // Hold the actual caption, then move it by a fractional-pixel amount: no settings sheet.
            var x = 0f
            var y = 0f
            var density = 1f
            scenario.onActivity {
                val bounds = requireNotNull(it.captionBounds())
                x = bounds.centerX(); y = bounds.centerY()
                density = it.resources.displayMetrics.density
            }
            val down = android.os.SystemClock.uptimeMillis()
            fun touch(action: Int, atY: Float) = scenario.onActivity {
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, atY, 0)
                it.dispatchTouchEvent(event); event.recycle()
            }
            touch(android.view.MotionEvent.ACTION_DOWN, y)
            Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 150)
            touch(android.view.MotionEvent.ACTION_MOVE, y - 7.5f * density)
            touch(android.view.MotionEvent.ACTION_UP, y - 7.5f * density)
            await("Fine caption drag saved") { settings.settings.value.subtitleVerticalOffsetDp == 7 }
            awaitCaptions(scenario, "LATE CAPTIONS")
            scenario.recreate()
            awaitCaptions(scenario, "LATE CAPTIONS")
            assertEquals("Dragged position survives recreation", 7, settings.settings.value.subtitleVerticalOffsetDp)
            scenario.onActivity { it.playbackController!!.play() }
            await("Playing before Back") { var playing = false; scenario.onActivity { playing = it.playbackController?.isPlaying == true }; playing }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            Thread.sleep(500)
            instrumentation.runOnMainSync { assertFalse("Back must pause the service", NativePlaybackService.isPlaybackRequested) }
        } finally {
            scenario.close()
            context.stopService(Intent(context, NativePlaybackService::class.java))
            settings.updateSubtitleVerticalOffsetDp(previous)
            server.close(); payload.delete()
        }
    }

    @Test fun automaticSubtitlesPreferTheStreamsOwnTimelineOverDownloadedRelease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNativeFixtureNetworkPermission()
        val server = NativeBackgroundPlaybackTest.FixtureServer(instrumentation.context.assets.open("cast-test-embedded.mkv").use { it.readBytes() })
        val request = NativePlaybackRequest(server.url, "video/x-matroska", "https://fixture.aliflix.test/",
            "Aliflix test", "", "Embedded caption verification", 3000, true,
            "WEBVTT\n\n00:00:00.000 --> 00:01:20.000\nWRONG RELEASE TIMING\n\n",
            preferEmbeddedSubtitles = true)
        val payload = File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
        val scenario = ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java)
            .putExtra("requestFile", payload.name), nativePhoneLaunchOptions())
        try {
            awaitCaptions(scenario, "EMBEDDED MATCHED CAPTIONS")
            scenario.onActivity {
                assertTrue(NativePlaybackService.embeddedSubtitlesActive)
                NativePlaybackService.updateSubtitles(request.subtitlesVtt, automatic = true)
            }
            awaitCaptions(scenario, "EMBEDDED MATCHED CAPTIONS")
            scenario.onActivity { NativePlaybackService.updateSubtitles(request.subtitlesVtt) }
            awaitCaptions(scenario, "WRONG RELEASE TIMING")
        } finally {
            scenario.close()
            context.stopService(Intent(context, NativePlaybackService::class.java))
            server.close(); payload.delete()
        }
    }

    @Test fun opacityChangesRenderedPixelsAndSubtitleSettingsSurviveRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNativeFixtureNetworkPermission()
        val store = (context.applicationContext as AliflixApplication).playerSettingsStore
        val previous = store.settings.value
        val server = NativeBackgroundPlaybackTest.FixtureServer(instrumentation.context.assets.open("cast-test.mp4").use { it.readBytes() })
        store.updateSubtitleBackgroundOpacity(0f)
        store.updateSubtitleFontSize(22f)
        store.updateSubtitleVerticalOffsetDp(24)
        store.updateSubtitleDelayTenths(7)
        val request = NativePlaybackRequest(server.url, "video/mp4", "https://fixture.aliflix.test/", "Test", "", "Opacity", 3000, true,
            "WEBVTT\n\n00:00:00.000 --> 00:01:20.000\nOPACITY PERSISTENCE\n\n")
        val payload = File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
        val scenario = ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java).putExtra("requestFile", payload.name), nativePhoneLaunchOptions())
        fun darkPixels(): Int {
            var result = 0
            scenario.onActivity { activity ->
                val view = requireNotNull(findSubtitles(activity.window.decorView))
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                    val color = bitmap.getPixel(x, y)
                    if (android.graphics.Color.alpha(color) > 200 && android.graphics.Color.red(color) < 30) result++
                }
                bitmap.recycle()
            }
            return result
        }
        try {
            awaitCaptions(scenario, "OPACITY PERSISTENCE")
            val transparent = darkPixels()
            store.updateSubtitleBackgroundOpacity(1f)
            scenario.recreate()
            awaitCaptions(scenario, "OPACITY PERSISTENCE")
            assertTrue("Opaque caption background must add dark pixels", darkPixels() > transparent + 100)
            val restored = PlayerSettingsStore(context).settings.value
            assertEquals(1f, restored.subtitleBackgroundOpacity, 0f)
            assertEquals(22f, restored.subtitleFontSizeSp, 0f)
            assertEquals(24, restored.subtitleVerticalOffsetDp)
            assertEquals(7, restored.subtitleDelayTenths)
        } finally {
            scenario.close(); context.stopService(Intent(context, NativePlaybackService::class.java)); server.close(); payload.delete()
            store.updateSubtitleBackgroundOpacity(previous.subtitleBackgroundOpacity)
            store.updateSubtitleFontSize(previous.subtitleFontSizeSp)
            store.updateSubtitleVerticalOffsetDp(previous.subtitleVerticalOffsetDp)
            store.updateSubtitleDelayTenths(previous.subtitleDelayTenths)
        }
    }

    @Test fun startupRaceBuffersPlayableMediaAndCancelsSlowerContender() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNativeFixtureNetworkPermission()
        val server = NativeBackgroundPlaybackTest.FixtureServer(instrumentation.context.assets.open("cast-test.mp4").use { it.readBytes() })
        val request = NativePlaybackRequest(server.url, "video/mp4", "https://fixture.aliflix.test/", "Test", "", "Race", 0, false)
        var loserClosed = false
        try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Main) {
                val winner = firstSuccessful(listOf(suspend {
                    try { kotlinx.coroutines.delay(30_000); request.copy(title = "Slow") }
                    finally { loserClosed = true }
                }, suspend {
                    StartupStreamCache.awaitPlayable(context, request)
                    request
                }))
                assertEquals("Race", winner.title)
                assertTrue("Losing work must close before handing off", loserClosed)
            }
        } finally { server.close() }
    }

    private fun awaitCaptions(scenario: ActivityScenario<NativePlayerActivity>, expected: String) = await("Visible $expected") {
        var visible = false
        scenario.onActivity { activity ->
            val player = activity.playbackController
            val view = findSubtitles(activity.window.decorView)
            if (diagnosticCount++ % 20 == 0) android.util.Log.i("SubtitleRenderingTest",
                "state=${player?.playbackState} error=${player?.playerError} cues=${player?.currentCues?.cues?.map { it.text }} view=${view?.width}x${view?.height} padding=${view?.paddingBottom} children=${view?.childCount} childHeight=${view?.getChildAt(0)?.height} shown=${view?.isShown}")
            if (player?.playbackState == Player.STATE_READY && NativePlaybackService.currentCaptionCues().any { it.text.toString().contains(expected) } &&
                view != null && view.isShown && view.width > 0 && view.height > 0) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                var pixels = 0
                for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                    val color = bitmap.getPixel(x, y)
                    if (android.graphics.Color.alpha(color) > 100 && android.graphics.Color.red(color) > 180 && android.graphics.Color.green(color) > 180) pixels++
                }
                bitmap.recycle()
                if (diagnosticCount % 20 == 1) android.util.Log.i("SubtitleRenderingTest", "captionPixels=$pixels")
                visible = pixels > 20
            }
        }
        visible
    }

    private fun findSubtitles(view: View): SubtitleView? = if (view is SubtitleView) view else
        (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { findSubtitles(group.getChildAt(it)) } }

    private var diagnosticCount = 0

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 25_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail(description)
    }
}
