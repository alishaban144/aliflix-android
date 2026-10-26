@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.AliflixApplication
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MobileResumeDeviceTest {
    @Test fun watchedPositionSurvivesLeavingAndNormalPlayFromAnotherServerRoute() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNativeFixtureNetworkPermission()
        val selection = PlaybackSelection(Media(2147482991, MediaType.MOVIE, "Resume validation"))
        val server = NativeBackgroundPlaybackTest.FixtureServer(instrumentation.context.assets.open("cast-test.mp4").use { it.readBytes() })
        val request = NativePlaybackRequest(server.url, "video/mp4", "https://fixture.aliflix.test/", "Aliflix test", "",
            selection.media.title, 0, true, selectionJson = selection.nativeJson())
        val file = File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
        val app = context.applicationContext as AliflixApplication
        val routes = PlaybackRouteStore(File(context.noBackupFilesDir, "playback-routes"))
        var position = 0L
        try {
            ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java).putExtra("requestFile", file.name), nativePhoneLaunchOptions()).use { scenario ->
                await { var ready = false; scenario.onActivity { ready = it.playbackUiState.ready }; ready }
                scenario.onActivity { it.playbackController!!.pause(); position = (it.playbackController!!.duration * .85).toLong(); it.playbackController!!.seekTo(position) }
                await { var sought = false; scenario.onActivity { sought = kotlin.math.abs(it.playbackController!!.currentPosition - position) < 1000 }; sought }
                scenario.onActivity { it.pauseAndLeavePlayer() }
            }
            val saved = requireNotNull(app.playbackProgressStore.progressFor(selection))
            assertTrue("The fixture must exercise watched status", saved.completed)
            assertEquals(position / 1000.0, saved.positionSeconds, 1.0)
            routes.save(selection, "Another server", request)
            context.stopService(Intent(context, NativePlaybackService::class.java))
            Thread.sleep(500)
            ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java)
                .putExtra("selection", selection.nativeJson()).putExtra("autoSubtitles", false), nativePhoneLaunchOptions()).use { scenario ->
                await { var ready = false; scenario.onActivity { ready = it.playbackUiState.ready }; ready }
                scenario.onActivity {
                    assertEquals("Normal Play restarted the watched movie", position / 1000.0, it.playbackController!!.currentPosition / 1000.0, 2.0)
                    assertEquals("Another server", it.playbackUiState.server)
                    assertTrue(it.playbackController!!.isPlaying)
                    it.pauseAndLeavePlayer()
                }
            }
        } finally {
            context.stopService(Intent(context, NativePlaybackService::class.java)); server.close(); file.delete()
            routes.invalidateStream(selection); app.playbackProgressStore.removeMedia(selection.media); app.libraryStore.removeRecent(selection.media)
        }
    }
    @Test fun audioOnlyResumeKeepsSavingProgressWithoutVideoFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNativeFixtureNetworkPermission()
        val selection = PlaybackSelection(Media(2147482990, MediaType.MOVIE, "Audio resume validation"))
        val rate = 8000
        val count = rate * 30
        val wave = java.nio.ByteBuffer.allocate(44 + count * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(count * 2)
        repeat(count) { wave.putShort(0) }
        val server = NativeBackgroundPlaybackTest.FixtureServer(wave.array())
        val request = NativePlaybackRequest(server.url, "audio/wav", "https://fixture.aliflix.test/", "Aliflix test", "",
            selection.media.title, 10_000, true, selectionJson = selection.nativeJson())
        val file = File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
        val app = context.applicationContext as AliflixApplication
        try {
            ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java)
                .putExtra("requestFile", file.name), nativePhoneLaunchOptions()).use { scenario ->
                await { var ready = false; scenario.onActivity {
                    ready = it.playbackUiState.ready && it.playbackController!!.isPlaying && NativePlaybackService.canRecordProgress
                }; ready }
                scenario.onActivity {
                    assertEquals(10.0, it.playbackController!!.currentPosition / 1000.0, 2.0)
                    it.pauseAndLeavePlayer()
                }
            }
            assertEquals(10.0, requireNotNull(app.playbackProgressStore.progressFor(selection)).positionSeconds, 2.0)
        } finally {
            context.stopService(Intent(context, NativePlaybackService::class.java)); server.close(); file.delete()
            app.playbackProgressStore.removeMedia(selection.media); app.libraryStore.removeRecent(selection.media)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Resume playback unavailable", condition())
    }
}
