@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Normal provider resolution and automatic captions. No timing answers or
 * replacement subtitle editions enter the player. PCM stays in private evidence
 * for a separate, independently timed check after this test finishes. */
class AutomaticTerminatorSyncDeviceTest {
    @Test fun normalAutomaticEnglishCaptionsRecoverAndSynchronizeAcrossScenes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAutomaticTerminator") == "true")
        grantNativeFixtureNetworkPermission()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selection = PlaybackSelection(Media(218, MediaType.MOVIE, "The Terminator", year = "1984",
            imdbId = "tt0088247", originalLanguage = "en"))
        val output = File(context.getExternalFilesDir(null), "automatic-terminator-private").apply { mkdirs() }
        File(output, "selection.json").writeText(selection.nativeJson())
        val report = JSONObject()
        ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java)
            .putExtra("selection", selection.nativeJson()).putExtra("autoSubtitles", true)
            .putExtra("subtitleLanguage", "en"), nativePhoneLaunchOptions()).use { scenario ->
            await("Normal playback and automatic captions", 120_000) {
                var ready = false
                scenario.onActivity { activity ->
                    activity.playbackUiState.error?.let { fail(it) }
                    ready = activity.playbackUiState.ready && !activity.playbackUiState.subtitleLoading &&
                        NativePlaybackService.originalSubtitles() != null && activity.playbackUiState.audioSyncAvailable
                }
                ready
            }
            val raw = requireNotNull(NativePlaybackService.originalSubtitles())
            File(output, "original-cues.json").writeText(raw)
            val frames = java.util.Collections.synchronizedList(mutableListOf<Pair<Double, ShortArray>>())
            NativePlaybackService.debugObserveSpeech { time, pcm -> frames.add(time to pcm) }
            try {
                val scenes = JSONArray()
                for ((index, start) in listOf(1275.619, 3196.058).withIndex()) {
                    scenario.onActivity { it.playbackController!!.seekTo((start * 1000).toLong()); it.playbackController!!.play() }
                    await("Played dialogue scene $index", 90_000) {
                        var played = false
                        scenario.onActivity { played = it.playbackController!!.currentPosition / 1000.0 >= start + 48 }
                        played
                    }
                    var position = 0.0
                    scenario.onActivity { position = it.playbackController!!.currentPosition / 1000.0 }
                    val audio = synchronized(frames) { frames.toList() }.filter { it.first >= start && it.first + .02 <= position }
                    writeWave(File(output, "scene-$index.wav"), audio)
                    File(output, "scene-$index-clock.json").writeText(JSONObject()
                        .put("start", audio.first().first).put("end", audio.last().first + .02).toString())
                    scenes.put(JSONObject().put("position", position).put("diagnostics", NativePlaybackService.speechDiagnostics()))
                }
                var began = 0L
                scenario.onActivity { it.resetSubtitleSync(); began = SystemClock.elapsedRealtime(); it.syncWithAudio() }
                await("Two-second sync completion", 2_100) {
                    var done = false
                    scenario.onActivity { done = it.playbackUiState.audioSyncState != "Syncing…" }
                    done
                }
                scenario.onActivity { activity ->
                    val elapsed = SystemClock.elapsedRealtime() - began
                    val state = activity.playbackUiState.audioSyncState
                    val request = requireNotNull(NativePlaybackService.activeRequest)
                    val key = subtitleCorrectionKey(request, requireNotNull(activity.playbackUiState.playbackSelection).key,
                        raw, NativePlaybackService.selectedAudioFingerprint())
                    val correction = SubtitleCorrectionStore(context).get(key)
                    report.put("state", state).put("elapsedMs", elapsed).put("scenes", scenes)
                        .put("correction", correction?.let { JSONObject().put("offset", it.offset).put("rate", it.rate) })
                        .put("source", nativeSelection(request.selectionJson).source.identity.name)
                        .put("manualDelay", (context.applicationContext as com.aliflix.app.AliflixApplication).playerSettingsStore.settings.value.subtitleDelaySeconds)
                    File(output, "receipt.json").writeText(report.toString())
                    assertEquals("Automatic caption route: $report", "Synced", state)
                    assertNotNull(correction)
                    assertTrue("Deadline: $elapsed", elapsed < 2_000)
                    assertEquals("Original automatic caption edition changed", raw, NativePlaybackService.originalSubtitles())
                    val manual = (context.applicationContext as com.aliflix.app.AliflixApplication).playerSettingsStore.settings.value.subtitleDelaySeconds
                    val originals = parseTimedTextSubtitleCues(nativeSubtitlesVtt(raw, 0.0))
                    val rendered = parseTimedTextSubtitleCues(NativePlaybackService.activeRequest!!.subtitlesVtt)
                    val expected = correction!!.apply(originals, manual)
                    assertEquals(expected.size, rendered.size)
                    rendered.zip(expected).forEach { (actual, checked) ->
                        assertEquals(checked.startSeconds, actual.startSeconds, .002)
                        assertEquals(checked.endSeconds, actual.endSeconds, .002)
                    }
                    assertTrue(activity.playbackController!!.isPlaying)
                }
            } finally { NativePlaybackService.debugObserveSpeech(null) }
        }
    }
    private fun writeWave(file: File, frames: List<Pair<Double, ShortArray>>) {
        assertTrue("No decoded played PCM", frames.isNotEmpty())
        assertTrue("PCM contains a seek gap", frames.zipWithNext().all { kotlin.math.abs(it.second.first - it.first.first - .02) < .003 })
        val data = ByteBuffer.allocate(frames.size * 320 + 44).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(frames.size * 320 + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(frames.size * 320)
        frames.forEach { (_, values) -> values.forEach(data::putShort) }
        file.writeBytes(data.array())
    }
    private fun await(message: String, timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("$message; ${NativePlaybackService.speechDiagnostics()}")
    }
}
