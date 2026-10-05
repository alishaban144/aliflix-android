@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class JapaneseAnimeDeviceTest {
    @Test fun freshJapaneseSeriesRaceDedicatedSourcesAndDecodeAfterSeeking() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAnime") == "true")
        grantNativeFixtureNetworkPermission()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val report = File(context.getExternalFilesDir(null), "anime-startup-device.txt").apply { writeText("") }
        val titles = listOf(
            PlaybackSelection(Media(21, MediaType.TV, "One Piece", year = "1999", genres = listOf("Animation"), originalLanguage = "ja"),
                1, 1, source = PlaybackSource(MobilePlaybackProvider.FLIXER)),
            PlaybackSelection(Media(1429, MediaType.TV, "Attack on Titan", year = "2013", genres = listOf("Animation"), originalLanguage = "ja"),
                1, 1, source = PlaybackSource(PlaybackProviderId.MIRURO)),
        )
        for (selection in titles) {
            context.stopService(Intent(context, NativePlaybackService::class.java))
            // Exercise catalogue fetching, rather than reusing this test's
            // previous direct stream. Provider/server history remains intact.
            PlaybackRouteStore(File(context.noBackupFilesDir, "playback-routes")).invalidateStream(selection)
            val began = android.os.SystemClock.elapsedRealtime()
            val scenario = ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java)
                .putExtra("selection", selection.nativeJson()).putExtra("autoSubtitles", false), nativePhoneLaunchOptions())
            try {
                await("${selection.media.title} dedicated-source first frame", 120_000) {
                    var ready = false
                    scenario.onActivity {
                        it.playbackUiState.error?.let { error -> fail(error) }
                        ready = it.playbackController?.isPlaying == true && NativePlaybackService.renderedStreamUrl != null
                    }
                    ready
                }
                var source = ""; var startup = 0L
                scenario.onActivity {
                    val actual = nativeSelection(requireNotNull(NativePlaybackService.activeRequest).selectionJson)
                    assertTrue("Anime must start on a dedicated source: ${actual.source.identity}", actual.source.identity.isAnimeNative)
                    assertEquals(com.aliflix.app.data.playbackProgressKey(selection), com.aliflix.app.data.playbackProgressKey(actual))
                    assertTrue(NativePlaybackService.hasSelectedAudio)
                    assertEquals(0, it.resolverViewCount)
                    source = actual.source.identity.name
                    startup = android.os.SystemClock.elapsedRealtime() - began
                    it.playbackController!!.seekTo(180_000)
                }
                await("$source playback after distant seek", 30_000) {
                    var playing = false; scenario.onActivity {
                        playing = it.playbackController!!.isPlaying && it.playbackController!!.currentPosition > 182_000
                    }; playing
                }
                scenario.onActivity { assertNull(it.playbackController!!.playerError) }
                report.appendText("${selection.media.title} S1E1: source=$source,firstFrameMs=$startup,forwardSeek=passed\n")
            } finally { scenario.close(); context.stopService(Intent(context, NativePlaybackService::class.java)) }
        }
    }
    private fun await(description: String, timeout: Long, condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(200) }
        fail(description)
    }
}
