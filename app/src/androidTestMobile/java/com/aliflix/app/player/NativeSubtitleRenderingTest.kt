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

    private fun awaitCaptions(scenario: ActivityScenario<NativePlayerActivity>, expected: String) = await("Visible $expected") {
        var visible = false
        scenario.onActivity { activity ->
            val player = activity.playbackController
            val view = findSubtitles(activity.window.decorView)
            if (diagnosticCount++ % 20 == 0) android.util.Log.i("SubtitleRenderingTest",
                "state=${player?.playbackState} error=${player?.playerError} cues=${player?.currentCues?.cues?.map { it.text }} view=${view?.width}x${view?.height} padding=${view?.paddingBottom} children=${view?.childCount} childHeight=${view?.getChildAt(0)?.height} shown=${view?.isShown}")
            if (player?.playbackState == Player.STATE_READY && player.currentCues.cues.any { it.text.toString().contains(expected) } &&
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
