@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.AliflixApplication
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real video/subtitle geometry, safe areas and credits dismissal, without external providers. */
class NativeUpNextDeviceTest {
    @Test fun floatingActionsStayBelowPortraitCaptionsAndAboveLandscapeControls() = checkFloatingActions(true)
    @Test fun everyEpisodeOffersNextWithoutAnOutroMarkerAndAtTheEnd() = checkFloatingActions(false)

    private fun checkFloatingActions(withMarker: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNativeFixtureNetworkPermission()
        val settings = (context.applicationContext as AliflixApplication).playerSettingsStore
        val previous = settings.settings.value
        settings.updatePlayNextEpisode(false)
        settings.updateSubtitleFontSize(28f)
        val selection = PlaybackSelection(Media(2147482986, MediaType.TV, "Up Next validation", imdbId = "tt999999999"),
            1, 1, "First episode", availableEpisodes = listOf(Episode(1, 1, "First episode"), Episode(1, 2, "The next chapter")))
        val marker = File(context.cacheDir, "introdb-v1/tt999999999-1-1.json").apply {
            parentFile!!.mkdirs()
            writeText(if (withMarker) """{"imdb_id":"tt999999999","season":1,"episode":1,"outro":{"start_ms":1000,"end_ms":9000}}"""
                else """{"imdb_id":"tt999999999","season":1,"episode":1,"outro":null}""")
        }
        val server = NativeBackgroundPlaybackTest.FixtureServer(instrumentation.context.assets.open("cast-test.mp4").use { it.readBytes() })
        val request = NativePlaybackRequest(server.url, "video/mp4", "https://fixture.aliflix.test/", "Aliflix test", "", selection.media.title,
            3000, true, "WEBVTT\n\n00:00:00.000 --> 00:00:20.000\nCredits remain visible\n", selectionJson = selection.nativeJson())
        val payload = File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
        val output = File(context.getExternalFilesDir(null), "up-next-validation").apply { mkdirs() }
        fun diagnostics(scenario: ActivityScenario<NativePlayerActivity>, phase: String) {
            scenario.onActivity {
                val state = "${it.playbackUiState}\nposition=${it.playbackController?.currentPosition},duration=${it.playbackController?.duration}\n"
                File(output, "state-$phase.txt").writeText(state)
                android.util.Log.i("UpNextGeometryTest", "$phase:$state")
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, "screen-$phase.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            fun tree(node: AccessibilityNodeInfo?): String = if (node == null) "none" else "${node.text}|${node.contentDescription};" + (0 until node.childCount).joinToString("") { tree(node.getChild(it)) }
            val rendered = tree(instrumentation.uiAutomation.rootInActiveWindow)
            File(output, "tree-$phase.txt").writeText(rendered)
            android.util.Log.i("UpNextGeometryTest", "$phase:$rendered")
        }
        try {
            ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java).putExtra("requestFile", payload.name), nativePhoneLaunchOptions()).use { scenario ->
                await { var ready = false; scenario.onActivity { ready = it.playbackUiState.ready && (!withMarker || it.playbackUiState.segments.isNotEmpty()) }; ready }
                // Readiness and marker loading can precede the initial seek on
                // a slower decoder. Freeze explicitly inside the real outro.
                scenario.onActivity { it.playbackController!!.pause(); it.playbackController!!.seekTo(
                    if (withMarker) 3000 else it.playbackController!!.duration - 500) }
                await { var inside = false; scenario.onActivity {
                    val position = it.playbackController!!.currentPosition
                    inside = it.playbackController!!.playbackState == androidx.media3.common.Player.STATE_READY &&
                        (if (withMarker) it.playbackUiState.segments.any { segment -> segment.kind == IntroSegmentKind.OUTRO && position in segment.startMs until segment.endMs }
                        else position >= requireNotNull(upNextWindowStart(it.playbackController!!.duration, emptyList())))
                }; inside }
                for ((name, orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    scenario.onActivity { it.requestedOrientation = orientation }
                    try { await {
                        // A fresh Android image places first-fullscreen education above the
                        // app. Acknowledge the system prompt just as a person would.
                        val emulator = android.os.Build.HARDWARE in listOf("ranchu", "goldfish")
                        val prompt = node("Got it") ?: if (emulator && node("Pixel Launcher isn't responding") != null)
                            node("Close app") else null
                        prompt?.let {
                            var action = it
                            while (!action.isClickable && action.parent != null) action = action.parent
                            assertTrue("Emulator system overlay could not be dismissed",
                                action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        }
                        node("Watch credits") != null && node("Next episode") != null
                    } }
                    catch (error: AssertionError) { diagnostics(scenario, name); throw error }
                    Thread.sleep(500)
                    val bounds = android.graphics.Rect().also { node("Watch credits")!!.getBoundsInScreen(it) }
                    scenario.onActivity {
                        assertTrue(bounds.bottom < it.window.decorView.height)
                        if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                            assertTrue("Floating actions overlap video/captions", bounds.top > maxOf(it.playbackUiState.videoBottomPx, it.playbackUiState.captionBottomPx))
                            assertTrue("Portrait actions remain attached to screen bottom", bounds.bottom < it.window.decorView.height - 100)
                        }
                    }
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                    }
                }
                if (!withMarker) {
                    scenario.onActivity { it.playbackController!!.seekTo(it.playbackController!!.duration); it.playbackController!!.play() }
                    await { var ended = false; scenario.onActivity { ended = it.playbackController!!.playbackState == androidx.media3.common.Player.STATE_ENDED }; ended }
                    await { node("Next episode") != null }
                }
                var action = requireNotNull(node("Watch credits"))
                while (!action.isClickable && action.parent != null) action = action.parent
                assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { node("Watch credits") == null }
                scenario.onActivity { assertEquals(selection.key, it.playbackUiState.playbackSelection?.key) }
            }
        } finally {
            settings.updatePlayNextEpisode(previous.playNextEpisode); settings.updateSubtitleFontSize(previous.subtitleFontSizeSp)
            context.stopService(Intent(context, NativePlaybackService::class.java)); server.close(); payload.delete(); marker.delete()
            val app = context.applicationContext as AliflixApplication
            app.playbackProgressStore.removeMedia(selection.media); app.libraryStore.removeRecent(selection.media)
        }
    }
    private fun node(text: String): AccessibilityNodeInfo? {
        // API 35 can retain the old Compose tree across an orientation change.
        // Query the rendered window after invalidating the automation cache.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.clearCache()
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        return find(automation.rootInActiveWindow)
    }
    private fun await(condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(100)
        assertTrue("Up Next geometry/interaction unavailable", condition())
    }
}
