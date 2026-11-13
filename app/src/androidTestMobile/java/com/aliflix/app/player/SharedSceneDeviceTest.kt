package com.aliflix.app.player

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.AliflixApplication
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SharedSceneDeviceTest {
    @Test fun episodeLinkPreservesRangeAndRejectsInvalidLinks() {
        val episode = PlaybackSelection(Media(1396, MediaType.TV, "A & B #1"), 2, 3)
        val range = SharedSceneRange(19_125, 23_625)
        val link = SharedSceneLink.from(episode, range)
        val parsed = requireNotNull(SharedSceneLink.parse(link.uri()))
        assertEquals(episode.media.title, parsed.media.title)
        assertEquals(1396, parsed.media.id)
        assertEquals(2, parsed.season)
        assertEquals(3, parsed.episode)
        assertEquals(range, parsed.range)
        assertNull(SharedSceneLink.parse(Uri.parse(link.uri().toString().replace("/23625", "/19000"))))
        assertNull(SharedSceneLink.parse(link.uri().buildUpon().authority("example.com").build()))
        assertEquals(3_723_450L, parseSceneTime("1:02:03.450"))
        assertNull(parseSceneTime("1:60"))
        assertEquals(SharedSceneRange(60_000, 90_000), defaultSceneRange(89_000, 90_000))
    }

    @Test fun scenePlaybackStopsAtEndAndLeavesWatchProgressAlone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNativeFixtureNetworkPermission()
        val selection = PlaybackSelection(Media(2147482987, MediaType.MOVIE, "Scene validation"))
        val app = context.applicationContext as AliflixApplication
        val priorProgress = app.playbackProgressStore.progressFor(selection)
        val range = SharedSceneRange(10_000, 12_400)
        NativeBackgroundPlaybackTest.FixtureServer(instrumentation.context.assets.open("cast-test.mp4").use { it.readBytes() }).use { server ->
            val request = NativePlaybackRequest(server.url, "video/mp4", "https://fixture.aliflix.test/", "Aliflix test", "",
                selection.media.title, range.startMs, true, selectionJson = selection.nativeJson())
            try {
                val routes = PlaybackRouteStore(File(context.noBackupFilesDir, "playback-routes"))
                routes.save(selection, "Fixture", request)
                val link = SharedSceneLink.from(selection, range).uri()
                val intent = Intent(Intent.ACTION_VIEW, link, context, SharedSceneActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                fun onPlayer(action: (NativePlayerActivity) -> Unit) {
                    instrumentation.runOnMainSync {
                        androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                            .filterIsInstance<NativePlayerActivity>().firstOrNull()?.let(action)
                    }
                }
                try {
                    var observedStart = -1L
                    var playbackStatus = ""
                    await(diagnostic = { playbackStatus }) {
                        onPlayer { activity ->
                            playbackStatus = "stage=${activity.playbackUiState.stage} error=${activity.playbackUiState.error}"
                            activity.playbackController?.takeIf { it.isPlaying }?.let { observedStart = it.currentPosition }
                        }
                        observedStart >= 0
                    }
                    assertTrue("Started at $observedStart", observedStart in range.startMs..range.startMs + 800)
                    var observedEnd = -1L
                    await(diagnostic = { playbackStatus }) {
                        onPlayer { activity ->
                            activity.playbackController?.let { player ->
                                playbackStatus = "scene=${NativePlaybackService.activeScene} position=${player.currentPosition} " +
                                    "ready=${player.playWhenReady} state=${player.playbackState}"
                                if (!player.playWhenReady) observedEnd = player.currentPosition
                            }
                        }
                        observedEnd >= 0
                    }
                    assertTrue("Stopped at $observedEnd", observedEnd in (range.endMs - 200)..(range.endMs + 250))
                    assertEquals(priorProgress, app.playbackProgressStore.progressFor(selection))
                } finally {
                    onPlayer { it.pauseAndLeavePlayer() }
                    routes.invalidateStream(selection)
                }
            } finally {
                context.stopService(Intent(context, NativePlaybackService::class.java))
            }
        }
    }

    @Test fun decoderReturnsAnActualVideoFrame() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNativeFixtureNetworkPermission()
        NativeBackgroundPlaybackTest.FixtureServer(instrumentation.context.assets.open("cast-test.mp4").use { it.readBytes() }).use { server ->
            val request = NativePlaybackRequest(server.url, "video/mp4", "https://fixture.aliflix.test/", "Aliflix test", "",
                "Seek fixture", 0, false)
            withContext(Dispatchers.Main) {
                val previews = PlayerSeekThumbnails(context, request)
                val decoder = launch { previews.run() }
                try {
                    previews.request(20_000)
                    withTimeout(8_000) {
                        while (previews.frame(20_000) == null) delay(30)
                    }
                    val frame = requireNotNull(previews.frame(20_000))
                    assertEquals(144, frame.height)
                    assertTrue(frame.width > frame.height)
                    assertNull(previews.frame(60_000))
                } finally {
                    decoder.cancelAndJoin()
                }
            }
        }
    }

    private fun await(diagnostic: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            assertTrue("Playback timed out. ${diagnostic()}", SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(40)
        }
    }
}
