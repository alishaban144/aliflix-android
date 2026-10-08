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
    @Test fun floatingActionsStayBelowPortraitCaptionsAndAboveLandscapeControls() {
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
            writeText("""{"imdb_id":"tt999999999","season":1,"episode":1,"outro":{"start_ms":1000,"end_ms":9000}}""")
        }
        val server = NativeBackgroundPlaybackTest.FixtureServer(instrumentation.context.assets.open("cast-test.mp4").use { it.readBytes() })
        val request = NativePlaybackRequest(server.url, "video/mp4", "https://fixture.aliflix.test/", "Aliflix test", "", selection.media.title,
            3000, true, "WEBVTT\n\n00:00:00.000 --> 00:00:20.000\nCredits remain visible\n", selectionJson = selection.nativeJson())
        val payload = File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
        val output = File(context.getExternalFilesDir(null), "up-next-validation").apply { mkdirs() }
        fun diagnostics(scenario: ActivityScenario<NativePlayerActivity>, phase: String) {
            scenario.onActivity {
                File(output, "state-$phase.txt").writeText("${it.playbackUiState}\nposition=${it.playbackController?.currentPosition},duration=${it.playbackController?.duration}\n")
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(output, "screen-$phase.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            fun tree(node: AccessibilityNodeInfo?): String = if (node == null) "none" else "${node.text}|${node.contentDescription};" + (0 until node.childCount).joinToString("") { tree(node.getChild(it)) }
            File(output, "tree-$phase.txt").writeText(tree(instrumentation.uiAutomation.rootInActiveWindow))
        }
        try {
            ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java).putExtra("requestFile", payload.name), nativePhoneLaunchOptions()).use { scenario ->
                await { var ready = false; scenario.onActivity { ready = it.playbackUiState.ready && it.playbackUiState.segments.isNotEmpty(); if (ready) it.playbackController!!.pause() }; ready }
                for ((name, orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    scenario.onActivity { it.requestedOrientation = orientation }
                    try { await { node("Watch credits") != null && node("Next episode") != null } }
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
