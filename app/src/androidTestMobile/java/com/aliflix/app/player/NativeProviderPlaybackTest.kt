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
        val autoSubtitles = InstrumentationRegistry.getArguments().getString("liveSubtitles") == "true"
        val selections = if (InstrumentationRegistry.getArguments().getString("liveTitle") == "got") listOf(
            PlaybackSelection(Media(1399, MediaType.TV, "Game of Thrones"), 1, 1, "Winter Is Coming",
                source = PlaybackSource(PlaybackProviderId.MOVIEPIRE, "https://moviepire.ru"))
        ) else listOf(
            PlaybackSelection(Media(550, MediaType.MOVIE, "Fight Club", year = "1999"), source = PlaybackSource(PlaybackProviderId.MOVIEPIRE, "https://moviepire.ru")),
            PlaybackSelection(Media(1396, MediaType.TV, "Breaking Bad"), 1, 1, "Pilot", source = PlaybackSource(PlaybackProviderId.MOVIEPIRE, "https://moviepire.ru")),
        )
        val provider = InstrumentationRegistry.getArguments().getString("liveProvider")
        val cases = if (provider == "ramoflix") (selections + selections.last().copy(seasonNumber = 2, episodeNumber = 3, episodeTitle = "Bit by a Dead Bee"))
            .map { it.copy(source = PlaybackSource.ramoflix()) } else selections
        cases.forEachIndexed { index, selection ->
            context.stopService(Intent(context, NativePlaybackService::class.java))
            Thread.sleep(500)
            val scenario = ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java)
                .putExtra("selection", selection.nativeJson()).putExtra("autoSubtitles", autoSubtitles).putExtra("subtitleLanguage", "EN"))
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            try {
                var last = ""
                var peakResolvers = 0
                var state = NativePlayerUi()
                val deadline = android.os.SystemClock.elapsedRealtime() + 300_000
                var stableSince = 0L
                var checkedStream: String? = null
                var positionAtSeek = 0L
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { state = it.playbackUiState; peakResolvers = maxOf(peakResolvers, it.resolverViewCount) }
                    val stage = state.stage ?: state.error ?: if (state.ready) "Ready" else "Connecting"
                    if (last != stage) {
                        File(output, "provider-$index.txt").appendText("$stage\n")
                        android.util.Log.i("AliflixProviderTest", "$index $stage")
                        last = stage
                    }
                    if (state.error != null) break
                    if (state.ready && state.stage == null) {
                        if (autoSubtitles) {
                            assertFalse("Subtitle loading must finish before playback", state.subtitleLoading)
                            assertTrue("Automatic captions must be ready before playback: ${state.subtitleError}",
                                state.activeSubtitleTrack != null || NativePlaybackService.embeddedSubtitlesActive)
                        }
                        val stream = NativePlaybackService.activeStreamUrl
                        if (checkedStream != stream) {
                            checkedStream = stream; stableSince = android.os.SystemClock.elapsedRealtime()
                            scenario.onActivity { it.playbackController?.seekTo(180_000); positionAtSeek = 180_000 }
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
                    assertEquals("Every resolver must be destroyed after the winning stream is prepared", 0, it.resolverViewCount)
                    assertNull("Native decoder error after seeking", it.playbackController?.playerError)
                    assertTrue("Playback must continue after seeking", it.playbackController?.let { p -> p.isPlaying && p.currentPosition > 185_000 } == true)
                    assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, it.resources.configuration.orientation)
                }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(output, "provider-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
                assertNull("Provider error: ${state.error}", state.error)
                assertTrue("Timed out at ${state.stage}", state.ready && state.stage == null)
                assertNotNull("A decoded frame is required", NativePlaybackService.renderedStreamUrl)
                assertTrue("A selected audio track is required", NativePlaybackService.hasSelectedAudio)
                if (autoSubtitles) {
                    assertTrue("At least two servers must have been tested concurrently", peakResolvers >= 2)
                    val track = state.activeSubtitleTrack
                    File(output, "startup-$index.txt").writeText("peakResolvers=$peakResolvers\nsubtitleCount=${state.subtitleTracks.size}\nembedded=${NativePlaybackService.embeddedSubtitlesActive}\nlanguage=${track?.languageCode}\nrelease=${track?.releaseName}\n")
                    val cues = parseTimedTextSubtitleCues(NativePlaybackService.activeRequest?.subtitlesVtt.orEmpty())
                    cues.firstOrNull { it.startSeconds > 10 && it.endSeconds - it.startSeconds > 1 }?.let { cue ->
                        scenario.onActivity { it.playbackController?.seekTo(((cue.startSeconds + 0.3) * 1000).toLong()); it.playbackController?.pause() }
                        val captionDeadline = android.os.SystemClock.elapsedRealtime() + 10_000
                        var shown = false
                        while (!shown && android.os.SystemClock.elapsedRealtime() < captionDeadline) {
                            scenario.onActivity { shown = it.playbackController?.currentCues?.cues?.any { c -> c.text.toString() == cue.text } == true }
                            Thread.sleep(100)
                        }
                        assertTrue("The automatically selected caption must render at its native cue time", shown)
                        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                            File(output, "automatic-caption-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                        }
                    }
                }
                Thread.sleep(3000)
            } finally { scenario.close(); context.stopService(Intent(context, NativePlaybackService::class.java)) }
        }
    }
}
