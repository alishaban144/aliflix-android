@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live details preparation and its exact foreground handoff. */
class DetailPreloadDeviceTest {
    @Test fun undonePreparesSilentlyAndPlayReusesThePendingOrReadyRequest() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveProviders") == "true")
        grantNativeFixtureNetworkPermission()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val media = Media(86340, MediaType.TV, "Undone", year = "2019", imdbId = "tt8101850")
        val episodes = listOf(Episode(1, 1, "The Crash"), Episode(1, 2, "The Hospital"))
        var selected: PlaybackSelection? = null
        val before = NativePlaybackService.activeRequestId
        val began = android.os.SystemClock.elapsedRealtime()
        ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java), nativePhoneLaunchOptions()).use { details ->
            details.onActivity { it.setContent { DetailPlaybackPreload(media, episodes.first(), episodes, true) } }
            val deadline = began + 15_000
            while (selected == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                details.onActivity { selected = DetailPreloadStore.current?.selection }
                Thread.sleep(100)
            }
            assertNotNull("Details must start preparation", selected)
            Thread.sleep(1_000) // Claim ongoing work rather than wait for it to finish.
            details.onActivity {
                assertEquals("Preloading cannot start the native player", before, NativePlaybackService.activeRequestId)
                claimDetailPreload(requireNotNull(selected))
                assertNotNull("Play must transfer the same preparation", DetailPreloadStore.claimed)
                it.setContent { } // Details leaves composition during foreground handoff.
            }
            val tap = android.os.SystemClock.elapsedRealtime()
            ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java)
                .putExtra("selection", requireNotNull(selected).nativeJson()).putExtra("playTapElapsedMs", tap)
                .putExtra("autoSubtitles", false), nativePhoneLaunchOptions()).use { player ->
                var ready = false
                var lastState = ""
                val playDeadline = tap + 35_000
                while (!ready && android.os.SystemClock.elapsedRealtime() < playDeadline) {
                    player.onActivity {
                        val status = "stage=${it.playbackUiState.stage},${NativePlaybackService.playbackEvidence()}"
                        if (status != lastState) { android.util.Log.i("AliflixStartupTest", status); lastState = status }
                        assertNull("Undone preparation failed", it.playbackUiState.error)
                        if (it.playbackUiState.stage != null) assertFalse("Audio behind loading", it.playbackController?.isPlaying == true)
                        ready = it.playbackUiState.ready && it.playbackUiState.stage == null && it.playbackController?.isPlaying == true
                    }
                    Thread.sleep(100)
                }
                assertTrue("Undone did not start within its bounded search", ready)
                assertEquals(NativePlaybackService.activeStreamUrl, NativePlaybackService.renderedStreamUrl)
                assertNull(DetailPreloadStore.claimed)
                android.util.Log.i("AliflixStartupTest", "undone detailsMs=${tap - began},playToVideoMs=${android.os.SystemClock.elapsedRealtime() - tap}")
                player.onActivity { it.playbackController!!.seekTo(180_000) }
                val seekDeadline = android.os.SystemClock.elapsedRealtime() + 15_000
                var resumed = false
                while (!resumed && android.os.SystemClock.elapsedRealtime() < seekDeadline) {
                    player.onActivity {
                        assertNull(it.playbackController!!.playerError)
                        resumed = it.playbackController!!.isPlaying && it.playbackController!!.currentPosition >= 180_500
                    }
                    Thread.sleep(100)
                }
                player.onActivity {
                    assertTrue("Playback must resume after the distant seek", resumed)
                    assertNull(it.playbackController!!.playerError)
                    assertEquals(0, it.resolverViewCount)
                }
            }
        }
        context.stopService(Intent(context, NativePlaybackService::class.java))
    }
}
