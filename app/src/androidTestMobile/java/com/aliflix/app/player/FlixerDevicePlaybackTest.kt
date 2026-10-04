package com.aliflix.app.player

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.aliflix.app.downloads.downloadRequest
import java.io.File

/** Real provider acceptance: a decoded native frame, audible track, progress, and original captions. */
class FlixerDevicePlaybackTest {
    @Test fun moviesAndDarkPlayWithOriginalEnglishAndArabicCaptions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveFlixer") == "true")
        grantNativeFixtureNetworkPermission()
        val context = instrumentation.targetContext
        val output = File(context.getExternalFilesDir(null), "flixer-validation").apply { mkdirs() }
        val dark = PlaybackSelection(Media(70523, MediaType.TV, "Dark", year = "2017"), 1, 1, "Secrets", source = PlaybackSource(MobilePlaybackProvider.FLIXER))
        val movie = PlaybackSelection(Media(550, MediaType.MOVIE, "Fight Club", year = "1999"), source = PlaybackSource(MobilePlaybackProvider.FLIXER))
        val cases = if (args.getString("flixerCase") == "dark") listOf(dark to "AR") else listOf(movie to "EN", dark to "EN", dark to "AR")
        cases.forEachIndexed { index, (selection, language) ->
            context.stopService(Intent(context, NativePlaybackService::class.java))
            Thread.sleep(700)
            context.getSharedPreferences("native-subtitle-choice", 0).edit().clear().commit()
            val started = SystemClock.elapsedRealtime()
            val scenario = ActivityScenario.launch<NativePlayerActivity>(Intent(context, NativePlayerActivity::class.java)
                .putExtra("selection", selection.nativeJson()).putExtra("autoSubtitles", true).putExtra("subtitleLanguage", language))
            try {
                var state = NativePlayerUi()
                var last = ""
                val deadline = started + 120_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { state = it.playbackUiState }
                    val summary = "${state.stage},ready=${state.ready},error=${state.error},captions=${state.activeSubtitleTrack?.id}"
                    if (summary != last) { File(output, "case-$index.txt").appendText("${SystemClock.elapsedRealtime()-started}: $summary\n"); last = summary }
                    if (state.error != null || state.ready && state.stage == null && state.activeSubtitleTrack?.id?.startsWith("flixer:") == true && !state.subtitleLoading) break
                    Thread.sleep(250)
                }
                assertNull("Playback error", state.error)
                assertTrue("Startup timed out: ${state.stage}", state.ready && state.stage == null)
                val active = requireNotNull(NativePlaybackService.activeRequest)
                assertEquals("Flixer must actually win", MobilePlaybackProvider.FLIXER, nativeSelection(active.selectionJson).source.identity)
                assertTrue("Original Flixer captions required", state.activeSubtitleTrack?.id?.startsWith("flixer:") == true)
                assertEquals(language, state.activeSubtitleTrack?.languageCode)
                assertNotNull("Decoded frame required", NativePlaybackService.renderedStreamUrl)
                assertTrue("Audio track required", NativePlaybackService.hasSelectedAudio)
                File(output, "case-$index.txt").appendText("startupMs=${SystemClock.elapsedRealtime()-started}\n${active.url}\n")
                val cues = parseTimedTextSubtitleCues(active.subtitlesVtt)
                assertTrue("Original caption file must be loaded", cues.isNotEmpty())
                assertFalse("Subtitle promotion removed", cues.any { it.text.contains("hoofoot.ru", true) })
                val cue = cues.first { it.startSeconds > 10 && it.endSeconds-it.startSeconds > 1 &&
                    (language != "AR" || it.text.any { c -> c in '\u0600'..'\u06ff' }) }
                scenario.onActivity { it.playbackController!!.seekTo(((cue.startSeconds + .4) * 1000).toLong()); it.playbackController!!.pause() }
                var visible = false
                val captionDeadline = SystemClock.elapsedRealtime() + 15_000
                while (!visible && SystemClock.elapsedRealtime() < captionDeadline) {
                    scenario.onActivity { visible = NativePlaybackService.currentCaptionCues().any { c -> !c.text.isNullOrBlank() } }
                    Thread.sleep(200)
                }
                assertTrue("Original caption must render", visible)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(output,"caption-$index.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                }
                val prepared = kotlinx.coroutines.runBlocking {
                    com.aliflix.app.downloads.inspectDownload(selection, active, language)
                }
                assertTrue("Downloadable qualities required", prepared.qualities.isNotEmpty())
                val download = kotlinx.coroutines.runBlocking { prepared.downloadRequest(prepared.qualities.first(), language, true) }
                val metadata = org.json.JSONObject(String(download.data, Charsets.UTF_8)).getString("playback").let { org.json.JSONObject(it) }
                assertTrue("Original offline captions required", metadata.optString("subtitlesVtt").isNotBlank())
                File(output, "case-$index.txt").appendText("downloadQualities=${prepared.qualities.map { it.label }},audio=${prepared.audioTracks.map { it.label }}\n")
                scenario.onActivity { it.playbackController!!.seekTo(180_000); it.playbackController!!.play() }
                Thread.sleep(15_000)
                scenario.onActivity {
                    assertNull(it.playbackController!!.playerError)
                    assertTrue("Playback must advance after seeking", it.playbackController!!.isPlaying && it.playbackController!!.currentPosition > 185_000)
                    assertEquals("Resolver cleanup", 0, it.resolverViewCount)
                }
            } finally { scenario.close(); context.stopService(Intent(context, NativePlaybackService::class.java)) }
        }
    }
}
