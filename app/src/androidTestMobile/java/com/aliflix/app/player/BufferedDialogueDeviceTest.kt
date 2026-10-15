@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BufferedDialogueDeviceTest {
    @Test fun silentDecoderReadsFutureFramesWhileVisiblePlaybackStaysPaused() {
        grantNativeFixtureNetworkPermission()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        NativeBackgroundPlaybackTest.FixtureServer(instrumentation.context.assets.open("cast-test.mp4").use { it.readBytes() }).use { server ->
            val selection = PlaybackSelection(Media(2147482911, MediaType.MOVIE, "Buffered dialogue validation"))
            val request = NativePlaybackRequest(server.url, "video/mp4", "https://aliflix.app/", "Aliflix", "", selection.media.title,
                0, true, "", selectionJson = selection.nativeJson())
            val payload = File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
            ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java)
                .putExtra("requestFile", payload.name), nativePhoneLaunchOptions()).use { scenario ->
                val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
                var ready = false
                while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { ready = it.playbackUiState.ready && NativePlaybackService.selectedAudioFingerprint().isNotBlank() }
                    if (!ready) Thread.sleep(50)
                }
                assertTrue("Visible player ready", ready)
                var audio = ""; var position = 0.0
                scenario.onActivity {
                    it.playbackController!!.pause()
                    position = it.playbackController!!.currentPosition / 1000.0
                    audio = NativePlaybackService.selectedAudioFingerprint()
                }
                var evidence: BufferedDialogueEvidence? = null
                runBlocking { BufferedDialogueAnalysis.collect(context, request, audio, position) { evidence = it } }
                val observed = requireNotNull(evidence) { "No independent decoded audio" }
                val decodedThrough = observed.pcm.start + observed.pcm.frames.size * .02
                assertTrue("Scanner must read future media: $decodedThrough > $position", decodedThrough > position + 10)
                scenario.onActivity {
                    assertFalse(it.playbackController!!.isPlaying)
                    assertEquals(position, it.playbackController!!.currentPosition / 1000.0, .1)
                    assertEquals(audio, NativePlaybackService.selectedAudioFingerprint())
                    assertEquals(request.url, NativePlaybackService.activeRequest?.url)
                }
                var wrongTrackCallbacks = 0
                runBlocking { BufferedDialogueAnalysis.collect(context, request, "wrong-audio-identity", position) { wrongTrackCallbacks++ } }
                assertEquals("A different audio track cannot contribute evidence", 0, wrongTrackCallbacks)
                File(context.getExternalFilesDir(null), "buffered-dialogue-device.txt").writeText(
                    "PASS paused=$position decodedThrough=$decodedThrough frames=${observed.pcm.frames.size} wrongTrackCallbacks=$wrongTrackCallbacks")
            }
        }
    }
}
