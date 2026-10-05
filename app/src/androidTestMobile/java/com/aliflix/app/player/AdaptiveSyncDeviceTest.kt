@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.AliflixApplication
import com.aliflix.app.downloads.OfflineDownloads
import com.aliflix.app.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.*

/** Opt-in attached-phone acceptance. Real recorded speech, real AudioTrack and
 * production Media3/WebRTC/ONNX; no emulator, PCM generator or detector substitute.
 * Build the external fixture with tools/audio-sync/phone_fixture.py first.
 */
class AdaptiveSyncDeviceTest {
    @Test fun streamingCollectsEvidenceAfterOneTapAndCancelNeverCommits() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("physicalSync") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = requireNotNull(context.getExternalFilesDir(null))
        val truth = parseTimedTextSubtitleCues(nativeSubtitlesVtt(File(root, "audio-sync-validation.json").readText(), 0.0))
        val target = truth.map { it.copy(startSeconds = it.startSeconds + 47, endSeconds = it.endSeconds + 47) }
        grantNativeFixtureNetworkPermission()
        NativeBackgroundPlaybackTest.FixtureServer(File(root, "audio-sync-validation.wav").readBytes()).use { server ->
            val selection = PlaybackSelection(Media(2147482985, MediaType.MOVIE, "Streaming speech acceptance"))
            val request = NativePlaybackRequest(server.url, "audio/wav", "https://fixture.aliflix.test/", "Aliflix test", "",
                selection.media.title, 0, true, nativeSubtitlesVtt(subtitleCuesJson(target), 0.0),
                selectionJson = selection.nativeJson(), subtitleLanguage = "ar")
            val payload = File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
            try {
                ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java).putExtra("requestFile", payload.name), nativePhoneLaunchOptions()).use { player ->
                    await("Streaming decoder ready") { var ready = false; player.onActivity { ready = it.playbackUiState.audioSyncAvailable && it.playbackController?.isPlaying == true }; ready }
                    player.onActivity { it.resetSubtitleSync(); it.syncWithAudio() }
                    await("Collecting begins without tapping during dialogue") { var collecting = false; player.onActivity { collecting = it.playbackUiState.audioSyncState == "Collecting Evidence" }; collecting }
                    player.onActivity {
                        it.syncWithAudio() // Active action is Cancel.
                        assertNull(it.playbackUiState.audioSyncState)
                        assertFalse(it.playbackUiState.audioSyncApplied)
                        it.playbackController!!.setPlaybackSpeed(4f)
                    }
                    Thread.sleep(200)
                    val began = android.os.SystemClock.elapsedRealtime()
                    player.onActivity { it.syncWithAudio() }
                    await("One tap must accumulate and independently verify future scenes", 120_000) {
                        var state: String? = null; player.onActivity { state = it.playbackUiState.audioSyncState }
                        if (state == "Unable to Verify") fail("Streaming verification failed: ${NativePlaybackService.speechDiagnostics()}")
                        state == "Synced"
                    }
                    player.onActivity {
                        val original = requireNotNull(NativePlaybackService.originalSubtitles())
                        val key = subtitleCorrectionKey(requireNotNull(NativePlaybackService.activeRequest), selection.key, original,
                            NativePlaybackService.selectedAudioFingerprint())
                        val correction = requireNotNull(SubtitleCorrectionStore(context).get(key))
                        assertEquals(-47.0, correction.offset, .6)
                        assertEquals(1.0, correction.rate, .0001)
                        assertTrue(it.playbackController!!.isPlaying)
                        File(root, "streaming-sync-device.txt").writeText("oneTap=true,cancelNoCommit=true,playbackSpeed=4,offset=${correction.offset},confidence=${correction.confidence},elapsedMs=${android.os.SystemClock.elapsedRealtime()-began}\n${NativePlaybackService.speechDiagnostics()}\n")
                        it.resetSubtitleSync()
                    }
                }
            } finally { payload.delete(); context.stopService(Intent(context, NativePlaybackService::class.java)) }
        }
    }

    @Test fun downloadedHumanSpeechCorrectsOffsetDriftAndEditsWithoutReloadingPlayback() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("physicalSync") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = requireNotNull(context.getExternalFilesDir(null))
        val truth = parseTimedTextSubtitleCues(nativeSubtitlesVtt(File(root, "audio-sync-validation.json").readText(), 0.0))
        val bytes = File(root, "audio-sync-validation.wav").readBytes()
        grantNativeFixtureNetworkPermission()
        val settings = (context.applicationContext as AliflixApplication).playerSettingsStore
        val manual = settings.settings.value.subtitleDelayTenths
        settings.updateSubtitleDelayTenths(7)
        val report = File(root, "adaptive-sync-device.txt").apply { writeText("Android ${android.os.Build.VERSION.RELEASE} ${android.os.Build.MODEL}\n") }
        try {
            for (mode in listOf("offset", "drift", "piecewise", "ambiguous")) {
                val rate = if (mode == "drift") 25.0 / 24.0 else 1.0
                val target = truth.map { cue ->
                    val extra = if (mode == "piecewise" && cue.startSeconds >= 120) 60.0
                        else if (mode == "ambiguous" && cue.startSeconds >= 160) 18.0 else 0.0
                    cue.copy(startSeconds = cue.startSeconds / rate + 47 + extra,
                        endSeconds = cue.endSeconds / rate + 47 + extra)
                }
                val server = NativeBackgroundPlaybackTest.FixtureServer(bytes)
                val selection = PlaybackSelection(Media(2147482986, MediaType.MOVIE, "Speech acceptance $mode"))
                val id = "movie:2147482986"
                lateinit var store: OfflineDownloads
                instrumentation.runOnMainSync { store = OfflineDownloads.get(context) }
                val request = NativePlaybackRequest(server.url, "audio/wav", "https://fixture.aliflix.test/", "Aliflix test", "",
                    selection.media.title, 0, true, nativeSubtitlesVtt(subtitleCuesJson(target), 0.0),
                    selectionJson = selection.nativeJson(), subtitleLanguage = "ar", offlineDownloadId = id)
                val metadata = JSONObject().put("playback", request.toJson()).put("quality", "Speech fixture").put("estimate", bytes.size)
                val download = DownloadRequest.Builder(id, android.net.Uri.parse(server.url)).setMimeType("audio/wav")
                    .setData(metadata.toString().toByteArray()).build()
                var scenario: ActivityScenario<NativePlayerActivity>? = null
                var payload: File? = null
                try {
                    ActivityScenario.launch<com.aliflix.app.MainActivity>(Intent(context, com.aliflix.app.MainActivity::class.java).putExtra("openDownloads", true)).use {
                        runBlocking { withContext(Dispatchers.Main) { store.enqueue(listOf(download)) } }
                        await("Complete local speech download") { store.entries.value.any { it.id == id && it.download.state == Download.STATE_COMPLETED } }
                    }
                    server.close()
                    payload = File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
                    scenario = ActivityScenario.launch(Intent(context, NativePlayerActivity::class.java).putExtra("requestFile", payload.name), nativePhoneLaunchOptions())
                    val player = scenario
                    await("Decoded selected audio and sync availability") { var ready = false; player.onActivity { ready = it.playbackUiState.audioSyncAvailable && it.playbackController?.isPlaying == true }; ready }
                    var buffering = 0; var discontinuities = 0
                    val listener = object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_BUFFERING) buffering++ }
                        override fun onPositionDiscontinuity(old: Player.PositionInfo, next: Player.PositionInfo, reason: Int) { discontinuities++ }
                    }
                    var startPosition = 0L
                    val began = android.os.SystemClock.elapsedRealtime()
                    player.onActivity {
                        it.resetSubtitleSync()
                        startPosition = it.playbackController!!.currentPosition
                        it.playbackController!!.addListener(listener)
                        it.syncWithAudio()
                    }
                    await("$mode sync must verify", 165_000) {
                        var state: String? = null; player.onActivity { state = it.playbackUiState.audioSyncState }
                        if (state == "Unable to Verify" && mode != "ambiguous") fail("$mode unable to verify; ${NativePlaybackService.speechDiagnostics()}")
                        state == if (mode == "ambiguous") "Unable to Verify" else "Synced"
                    }
                    player.onActivity {
                        val original = requireNotNull(NativePlaybackService.originalSubtitles())
                        val key = subtitleCorrectionKey(requireNotNull(NativePlaybackService.activeRequest), selection.key, original,
                            NativePlaybackService.selectedAudioFingerprint())
                        val found = SubtitleCorrectionStore(context).get(key)
                        if (mode == "ambiguous") {
                            assertNull("Ambiguous boundaries must never be cached", found)
                            assertFalse(it.playbackUiState.audioSyncApplied)
                            report.appendText("ambiguous: rejected,originalTimesPreserved=true,elapsedMs=${android.os.SystemClock.elapsedRealtime()-began}\n")
                            it.playbackController!!.removeListener(listener)
                            return@onActivity
                        }
                        val correction = requireNotNull(found)
                        assertEquals(mode, correction.model)
                        assertEquals(rate, correction.rate, .0005)
                        assertEquals(-47.0 * rate, correction.offset, .5)
                        val corrected = correction.apply(target)
                        for (i in corrected.indices) assertEquals("$mode cue $i", truth[i].startSeconds, corrected[i].startSeconds, .6)
                        assertTrue(it.playbackController!!.currentPosition > startPosition + 500)
                        assertEquals("Sync cannot rebuffer local playback", 0, buffering)
                        assertEquals("Sync cannot seek/reload local playback", 0, discontinuities)
                        assertEquals(7, settings.settings.value.subtitleDelayTenths)
                        report.appendText("$mode: model=${correction.model},offset=${correction.offset},rate=${correction.rate},regions=${correction.regions.size},confidence=${correction.confidence},elapsedMs=${android.os.SystemClock.elapsedRealtime()-began}\n${NativePlaybackService.speechDiagnostics()}\n")
                        it.resetSubtitleSync()
                        assertNull(SubtitleCorrectionStore(context).get(key))
                        assertEquals(original, NativePlaybackService.originalSubtitles())
                        assertEquals(7, settings.settings.value.subtitleDelayTenths)
                        assertEquals(0, discontinuities)
                        it.playbackController!!.removeListener(listener)
                    }
                } finally {
                    scenario?.close(); context.stopService(Intent(context, NativePlaybackService::class.java))
                    payload?.delete(); server.close()
                    instrumentation.runOnMainSync { store.manager.removeDownload(id) }
                    await("Remove acceptance fixture only") { store.entries.value.none { it.id == id } }
                }
            }
        } finally { settings.updateSubtitleDelayTenths(manual) }
    }

    private fun await(description: String, timeout: Long = 30_000, condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(100) }
        fail(description)
    }
}
