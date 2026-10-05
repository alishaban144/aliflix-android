@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.AliflixApplication
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Real film, original published captions, normal-speed selected decoded audio.
 * No injected VAD decisions, artificial speech annotations or generated audio.
 * Opt-in because it streams the public Blender film to the attached phone.
 */
class RealFilmQuickSyncDeviceTest {
    @Test fun realFilmFinishesWithinTwentySecondsIncludingSilenceAndBufferedAudio() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("physicalQuickSync") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(context.getExternalFilesDir(null))
        val truth = parseTimedTextSubtitleCues(File(root, "TOS-en.srt").readText())
        assertTrue(truth.size >= 70)
        val target = truth.map { it.copy(startSeconds = it.startSeconds + 7.25, endSeconds = it.endSeconds + 7.25) }
        val selection = PlaybackSelection(Media(2147482988, MediaType.MOVIE, "Tears of Steel — real film sync validation"))
        val request = NativePlaybackRequest(
            "https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov", "video/quicktime",
            "https://mango.blender.org/", "Aliflix", "", selection.media.title, 0, true,
            nativeSubtitlesVtt(subtitleCuesJson(target), 0.0), selectionJson = selection.nativeJson(), subtitleLanguage = "en",
        )
        val payload = File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
        val report = File(root, "quick-sync-real-film-device.txt")
        try {
            ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java)
                .putExtra("requestFile", payload.name), nativePhoneLaunchOptions()).use { scenario ->
                await("Real film playback ready", 60_000) { var ready = false; scenario.onActivity {
                    ready = it.playbackUiState.audioSyncAvailable && it.playbackController?.isPlaying == true
                }; ready }
                var began = 0L
                scenario.onActivity { it.resetSubtitleSync(); began = android.os.SystemClock.elapsedRealtime(); it.syncWithAudio() }
                await("Cold silence must stop within twenty seconds", 21_000) { var done = false; scenario.onActivity {
                    done = it.playbackUiState.audioSyncState in listOf("Synced", "Unable to Verify")
                }; done }
                scenario.onActivity {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - began
                    assertTrue("Cold UI exceeded deadline: $elapsed", elapsed < 20_000)
                    assertEquals("Unable to Verify", it.playbackUiState.audioSyncState) // Dialogue starts at 23 s.
                    assertFalse(it.playbackUiState.audioSyncApplied)
                    report.writeText("film=Tears of Steel,normalSpeed=true,coldSilence=Unable to Verify,elapsedMs=$elapsed\n")
                }
                // Playback history accumulates automatically, without any extra
                // sync taps or replay/download. The next action uses that history.
                await("Real dialogue history arrives", 100_000) { var heard = false; scenario.onActivity {
                    heard = (it.playbackController?.currentPosition ?: 0) >= 85_000
                }; heard }
                var discontinuities = 0; var buffers = 0
                val listener = object : Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { discontinuities++ }
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_BUFFERING) buffers++ }
                }
                scenario.onActivity { it.playbackController!!.addListener(listener); began = android.os.SystemClock.elapsedRealtime(); it.syncWithAudio() }
                await("Actual film subtitles must synchronize within twenty seconds", 21_000) { var done = false; scenario.onActivity {
                    done = it.playbackUiState.audioSyncState in listOf("Synced", "Unable to Verify")
                }; done }
                scenario.onActivity {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - began
                    assertEquals("Sync failed: ${NativePlaybackService.speechDiagnostics()}", "Synced", it.playbackUiState.audioSyncState)
                    assertTrue("Actual film sync exceeded deadline: $elapsed", elapsed < 20_000)
                    val original = requireNotNull(NativePlaybackService.originalSubtitles())
                    val key = subtitleCorrectionKey(requireNotNull(NativePlaybackService.activeRequest), selection.key, original,
                        NativePlaybackService.selectedAudioFingerprint())
                    val correction = requireNotNull(SubtitleCorrectionStore(context).get(key))
                    assertEquals(-7.25, correction.offset, .65)
                    assertEquals(1.0, correction.rate, .0001)
                    assertTrue(it.playbackController!!.isPlaying)
                    assertEquals(0, discontinuities)
                    assertEquals(0, buffers)
                    report.appendText("realSubtitles=Synced,offset=${correction.offset},rate=${correction.rate},score=${correction.confidence},elapsedMs=$elapsed,discontinuities=$discontinuities,buffers=$buffers\n${NativePlaybackService.speechDiagnostics()}\n")
                    it.resetSubtitleSync()
                    assertEquals(original, NativePlaybackService.originalSubtitles())
                    assertFalse(it.playbackUiState.audioSyncApplied)
                    it.playbackController!!.removeListener(listener)
                }
            }
        } finally {
            val observed = NativePlaybackService.quickSpeechEvidence()
            File(root, "quick-sync-real-film-evidence.json").writeText(org.json.JSONObject()
                .put("windows", org.json.JSONArray().apply { observed.forEach { window ->
                    put(org.json.JSONObject().put("start", window.start).put("bits", window.speech.joinToString("") { if (it >= .5) "1" else "0" }))
                } }).put("target", subtitleCuesJson(target)).put("truth", subtitleCuesJson(truth)).toString())
            payload.delete(); context.stopService(Intent(context, NativePlaybackService::class.java))
            val app = context.applicationContext as AliflixApplication
            app.playbackProgressStore.removeMedia(selection.media); app.libraryStore.removeRecent(selection.media)
        }
    }
    private fun await(description: String, timeout: Long, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("$description; ${NativePlaybackService.speechDiagnostics()}")
    }
}
