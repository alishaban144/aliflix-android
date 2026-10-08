@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.player

import android.content.Intent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.AliflixApplication
import com.aliflix.app.model.*
import com.aliflix.app.downloads.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Expected timing stays in the test. The app receives only incorrectly timed captions. */
class TerminatorSyncDeviceTest {
    @Test fun terminator1984IndependentlyCorrectsBothSignsAcrossDifferentDialogueScenes() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTerminatorSync") == "true")
        grantNativeFixtureNetworkPermission()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = PlaybackSelection(Media(218, MediaType.MOVIE, "The Terminator", year = "1984", imdbId = "tt0088247", originalLanguage = "en"), source = PlaybackSource(PlaybackProviderId.CINEJOY))
        val tracks = FlixerSubtitleRepository.tracks(original).filter { it.languageCode.equals("en", true) }.sortedBy { it.hearingImpaired }
        if (InstrumentationRegistry.getArguments().getString("probeSubtitlesOnly") == "true") {
            val root = File(context.getExternalFilesDir(null), "terminator-subtitles-private").apply { mkdirs() }
            tracks.forEachIndexed { index, track ->
                withTimeoutOrNull(8_000) {
                    SubdlSubtitleRepository().download(track, original).getOrNull()?.let {
                        File(root, "$index.json").writeText(subtitleCuesJson(it))
                    }
                }
            }
            File(root, "count.txt").writeText("English tracks=${tracks.size}")
            return@runBlocking
        }
        // This independently checked NTSC edition matches this stream. The PAL
        // edition returned first by the catalogue has a different clock. Expected
        // timing remains test-only; the app receives only the shifted captions.
        val truth = tracks.firstNotNullOfOrNull { track -> SubdlSubtitleRepository().download(track, original).getOrNull()?.takeIf { cues ->
            cues.size > 500 && cues.any { "graphic equalisers" in it.text.lowercase() && it.startSeconds in 1218.0..1220.0 }
        } }?.map { it.copy(startSeconds = it.startSeconds + 6.5, endSeconds = it.endSeconds + 6.5) }
            ?: error("The Terminator's original English captions are unavailable")
        val prepared = ActivityScenario.launch(com.aliflix.app.MainActivity::class.java).use { scenario ->
            lateinit var activity: androidx.activity.ComponentActivity
            lateinit var host: FrameLayout
            scenario.onActivity { activity = it; host = FrameLayout(it); it.findViewById<android.view.ViewGroup>(android.R.id.content).addView(host, android.view.ViewGroup.LayoutParams(1, 1)) }
            withContext(Dispatchers.Main) {
                var server = ""
                val request = NativeStreamResolver(activity, (activity.application as AliflixApplication).playbackProgressStore, host).use {
                    it.resolve(original, 0, emptySet(), forceLowQuality = true, onServer = { value -> server = value })
                }
                request.copy(subtitleLanguage = "en") to server
            }
        }
        val source = prepared.first
        val root = requireNotNull(context.getExternalFilesDir(null))
        File(root, "terminator-source-private.json").writeText(source.toJson())
        val report = File(root, "terminator-sync-device.txt").apply { writeText("title=The Terminator (1984),provider=${original.source.identity.name},server=${prepared.second},captionCount=${truth.size}\n") }
        // Distinct, dense conversations in the first, middle and later parts of the film.
        val dialogue = truth.filter(AdaptiveSubtitleSynchronizer::dialogue)
        val windows = dialogue.windowed(10, 5).filter { it.last().endSeconds - it.first().startSeconds in 22.0..35.0 && it.first().startSeconds > 90 }
        assertTrue("Insufficient distinct dialogue scenes", windows.size >= 3)
        val scenes = listOf(windows[windows.size / 6], windows[windows.size / 2], windows[windows.size * 5 / 6])
        val offsetSign = if (InstrumentationRegistry.getArguments().getString("offsetSign") == "-1") -1 else 1
        for ((index, shiftedBy) in listOf(6.75, -9.25, 14.5).map { it * offsetSign }.withIndex()) {
            val onlyScene = InstrumentationRegistry.getArguments().getString("sceneIndex")?.toIntOrNull()
            if (onlyScene != null && index != onlyScene) continue
            val scene = scenes[index]
            val start = (scene.last().endSeconds - 38.0).coerceAtLeast(0.0)
            val target = truth.map { it.copy(startSeconds = it.startSeconds + shiftedBy, endSeconds = it.endSeconds + shiftedBy) }.filter { it.startSeconds >= 0 }
            val selection = original.copy(media = original.media.copy(id = 2147482970 + index))
            val request = source.copy(selectionJson = selection.nativeJson(), positionMs = (start * 1000).toLong(), playing = true,
                subtitlesVtt = nativeSubtitlesVtt(subtitleCuesJson(target), 0.0), subtitleLanguage = "en", preferEmbeddedSubtitles = false)
            val payload = File(context.cacheDir, "native-request-${java.util.UUID.randomUUID()}.json").apply { writeText(request.toJson()) }
            val settings = (context.applicationContext as AliflixApplication).playerSettingsStore
            val previousDelay = settings.settings.value.subtitleDelayTenths
            val requestedDelay = InstrumentationRegistry.getArguments().getString("manualDelayTenths")?.toIntOrNull()
            try {
                if (requestedDelay != null) settings.updateSubtitleDelayTenths(requestedDelay)
                ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java).putExtra("requestFile", payload.name), nativePhoneLaunchOptions()).use { scenario ->
                    val frames = java.util.Collections.synchronizedList(mutableListOf<Pair<Double, ShortArray>>())
                    await("The Terminator audio service starts", 20_000) { var ready = false; scenario.onActivity {
                        ready = it.playbackUiState.audioSyncAvailable
                        if (ready) NativePlaybackService.debugObserveSpeech { time, samples -> frames.add(time to samples) }
                    }; ready }
                    await("The Terminator conversation must decode", 90_000) { var ready = false; scenario.onActivity { activity ->
                        activity.playbackUiState.error?.let { fail(it) }
                        ready = activity.playbackUiState.audioSyncAvailable && (activity.playbackController?.currentPosition ?: 0) / 1000.0 >= start + 34
                    }; ready }
                    var began = 0L
                    var heardAtTap = emptyList<HeardWord>()
                    scenario.onActivity {
                        it.resetSubtitleSync()
                        heardAtTap = NativePlaybackService.recentDialogueWords(it.playbackController!!.currentPosition / 1000.0)
                        began = android.os.SystemClock.elapsedRealtime(); it.syncWithAudio()
                    }
                    await("Terminator synchronization must finish within two seconds", 2_100) { var done = false; scenario.onActivity { done = it.playbackUiState.audioSyncState in listOf("Synced", "Not enough dialogue yet", "Couldn't match this dialogue") }; done }
                    scenario.onActivity { activity ->
                        val elapsed = android.os.SystemClock.elapsedRealtime() - began
                        report.appendText("attempt=$index,start=$start,position=${activity.playbackController?.currentPosition},injected=$shiftedBy,state=${activity.playbackUiState.audioSyncState},elapsedMs=$elapsed,diagnostics=${NativePlaybackService.speechDiagnostics()}\n")
                        val output = org.json.JSONObject().put("truth", subtitleCuesJson(truth)).put("target", subtitleCuesJson(target))
                            .put("windows", org.json.JSONArray().apply { NativePlaybackService.quickSpeechEvidence().forEach { window ->
                                put(org.json.JSONObject().put("start", window.start).put("bits", window.speech.joinToString("") { if (it >= .5) "1" else "0" }))
                            } })
                        File(root, "terminator-scene-$index.json").writeText(output.toString())
                        val heard = heardAtTap
                        File(root, "terminator-words-$index-private.json").writeText(org.json.JSONArray().apply { heard.forEach {
                            put(org.json.JSONObject().put("text", it.text).put("start", it.start).put("end", it.end))
                        } }.toString())
                        NativePlaybackService.debugObserveSpeech(null)
                        val audio = synchronized(frames) { frames.toList() }.filter { it.first < activity.playbackController!!.currentPosition / 1000.0 }
                        if (audio.isNotEmpty()) {
                            val data = java.nio.ByteBuffer.allocate(audio.size * 320 + 44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            data.put("RIFF".toByteArray()).putInt(audio.size * 320 + 36).put("WAVEfmt ".toByteArray()).putInt(16)
                                .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                                .put("data".toByteArray()).putInt(audio.size * 320)
                            audio.forEach { (_, values) -> values.forEach(data::putShort) }
                            File(root, "terminator-decoded-$index-private.wav").writeBytes(data.array())
                            File(root, "terminator-decoded-$index-clock.json").writeText(org.json.JSONObject()
                                .put("start", audio.first().first).put("end", audio.last().first)
                                .put("language", NativePlaybackService.selectedAudioLanguage())
                                .put("fingerprint", NativePlaybackService.selectedAudioFingerprint()).toString())
                        }
                        if (InstrumentationRegistry.getArguments().getString("captureOnly") == "true") return@onActivity
                        assertEquals("${activity.playbackUiState.audioSyncState}; ${NativePlaybackService.speechDiagnostics()}", "Synced", activity.playbackUiState.audioSyncState)
                        assertTrue("Terminator sync exceeded deadline: $elapsed", elapsed < 2000)
                        val raw = requireNotNull(NativePlaybackService.originalSubtitles())
                        val key = subtitleCorrectionKey(requireNotNull(NativePlaybackService.activeRequest), selection.key, raw, NativePlaybackService.selectedAudioFingerprint())
                        val correction = requireNotNull(SubtitleCorrectionStore(context).get(key))
                        val manual = settings.settings.value.subtitleDelaySeconds
                        assertEquals(-shiftedBy - manual, correction.offset, .6)
                        assertEquals(1.0, correction.rate, .0001)
                        val rendered = parseTimedTextSubtitleCues(NativePlaybackService.activeRequest!!.subtitlesVtt)
                        val rawCues = parseTimedTextSubtitleCues(nativeSubtitlesVtt(raw, 0.0))
                        val expected = correction.apply(rawCues, manual)
                        assertEquals(expected.size, rendered.size)
                        rendered.zip(expected).forEach { (actual, checked) ->
                            assertEquals(checked.startSeconds, actual.startSeconds, .002)
                            assertEquals(checked.endSeconds, actual.endSeconds, .002)
                        }
                        assertTrue(activity.playbackController!!.isPlaying)
                        report.appendText("scene=$index,start=$start,injected=$shiftedBy,manual=$manual,applied=${correction.offset},score=${correction.confidence},elapsedMs=$elapsed\n")
                        activity.resetSubtitleSync()
                        assertFalse(activity.playbackUiState.audioSyncApplied)
                        assertEquals(requestedDelay ?: previousDelay, settings.settings.value.subtitleDelayTenths)
                    }
                }
            } finally {
                settings.updateSubtitleDelayTenths(previousDelay)
                payload.delete(); context.stopService(Intent(context, NativePlaybackService::class.java))
                val app = context.applicationContext as AliflixApplication
                app.playbackProgressStore.removeMedia(selection.media); app.libraryStore.removeRecent(selection.media)
            }
        }
    }
    private fun await(description: String, timeout: Long, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("$description; ${NativePlaybackService.speechDiagnostics()}")
    }
}
