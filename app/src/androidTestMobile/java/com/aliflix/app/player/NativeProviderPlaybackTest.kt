package com.aliflix.app.player

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in real provider smoke test. CI's deterministic playback tests do not depend on providers. */
class NativeProviderPlaybackTest {
    @Test fun nativeMovieAndEpisodePrepareWithoutTouchingTheProvider() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveProviders") == "true")
        grantNativeFixtureNetworkPermission()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("settings put secure immersive_mode_confirmations confirmed").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
        val output = File(context.getExternalFilesDir(null), "player-validation").apply { mkdirs() }
        val selections = listOf(
            PlaybackSelection(Media(550, MediaType.MOVIE, "Fight Club", year = "1999"), source = PlaybackSource(PlaybackProviderId.MOVIEPIRE, "https://moviepire.ru")),
            PlaybackSelection(Media(1396, MediaType.TV, "Breaking Bad"), 1, 1, "Pilot", source = PlaybackSource(PlaybackProviderId.MOVIEPIRE, "https://moviepire.ru")),
        )
        selections.forEachIndexed { index, selection ->
            context.stopService(Intent(context, NativePlaybackService::class.java))
            Thread.sleep(500)
            val scenario = ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java)
                .putExtra("selection", selection.nativeJson()).putExtra("autoSubtitles", false))
            try {
                var last = ""
                var state = NativePlayerUi()
                val deadline = android.os.SystemClock.elapsedRealtime() + 300_000
                var stableSince = 0L
                var checkedStream: String? = null
                var positionAtSeek = 0L
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { state = it.playbackUiState }
                    val stage = state.stage ?: state.error ?: if (state.ready) "Ready" else "Connecting"
                    if (last != stage) {
                        File(output, "provider-$index.txt").appendText("$stage\n")
                        android.util.Log.i("AliflixProviderTest", "$index $stage")
                        last = stage
                    }
                    if (state.error != null) break
                    if (state.ready && state.stage == null) {
                        val stream = NativePlaybackService.activeStreamUrl
                        if (checkedStream != stream) {
                            checkedStream = stream; stableSince = android.os.SystemClock.elapsedRealtime()
                            scenario.onActivity { it.playbackController?.seekTo(30_000); positionAtSeek = 30_000 }
                        }
                        if (android.os.SystemClock.elapsedRealtime() - stableSince >= 10_000) {
                            var advanced = false
                            scenario.onActivity { advanced = it.playbackController?.let { p -> p.isPlaying && p.currentPosition > positionAtSeek + 5000 && p.playerError == null } == true }
                            if (advanced) break
                        }
                    } else { checkedStream = null; stableSince = 0 }
                    Thread.sleep(500)
                }
                scenario.onActivity {
                    state = it.playbackUiState
                    assertNull("Native decoder error after seeking", it.playbackController?.playerError)
                    assertTrue("Playback must continue after seeking", it.playbackController?.let { p -> p.isPlaying && p.currentPosition > 35_000 } == true)
                    assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, it.resources.configuration.orientation)
                }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(output, "provider-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
                assertNull("Provider error: ${state.error}", state.error)
                assertTrue("Timed out at ${state.stage}", state.ready && state.stage == null)
                assertNotNull("A decoded frame is required", NativePlaybackService.renderedStreamUrl)
                assertTrue("A selected audio track is required", NativePlaybackService.hasSelectedAudio)
                Thread.sleep(3000)
            } finally { scenario.close(); context.stopService(Intent(context, NativePlaybackService::class.java)) }
        }
    }
}
