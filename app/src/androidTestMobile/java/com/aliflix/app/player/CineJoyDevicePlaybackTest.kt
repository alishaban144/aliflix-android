package com.aliflix.app.player

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Physical-device acceptance test for the exact CineJoy audio-selection UI path. */
class CineJoyDevicePlaybackTest {
    @Test fun darkS1E1PlaysEveryAudioTrackAcrossSeeks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("liveCineJoy") == "true")
        grantNativeFixtureNetworkPermission()
        val context = instrumentation.targetContext
        val season = arguments.getString("liveSeason")?.toIntOrNull() ?: 1
        val episode = arguments.getString("liveEpisode")?.toIntOrNull() ?: 1
        val output = File(context.getExternalFilesDir(null), "cinejoy-device-validation/s${season}e${episode}").apply { mkdirs() }
        output.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        if (arguments.getString("clearStreamCache") == "true") {
            require(context.packageName.endsWith(".deviceprobe"))
            File(context.cacheDir, "playback-streams").deleteRecursively()
        }
        val selection = PlaybackSelection(
            media = Media(70523, MediaType.TV, "Dark", year = "2017", imdbId = "tt5753856"),
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = if (season == 1 && episode == 1) "Secrets" else "Episode $episode",
            source = PlaybackSource(PlaybackProviderId.CINEJOY),
        )
        context.stopService(Intent(context, NativePlaybackService::class.java))
        Thread.sleep(500)
        val scenario = ActivityScenario.launch<NativePlayerActivity>(
            Intent(context, NativePlayerActivity::class.java).putExtra("selection", selection.nativeJson()),
        )
        val startedAt = SystemClock.elapsedRealtime()
        try {
            var state = NativePlayerUi()
            waitFor("Dark S${season}E${episode} startup", 180_000) {
                scenario.onActivity { state = it.playbackUiState }
                state.error == null && state.ready && state.stage == null &&
                    NativePlaybackService.renderedStreamUrl != null && audioLabels(scenario).size >= 4
            }
            assertNull("CineJoy startup error: ${state.error}", state.error)
            val labels = audioLabels(scenario)
            assertEquals("Dark must expose every website audio rendition", 4, labels.size)
            val initialStream = NativePlaybackService.activeStreamUrl
            var videoTracks = ""
            scenario.onActivity { activity ->
                videoTracks = activity.playbackController!!.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    .joinToString("\n") { group -> "adaptive=${group.isAdaptiveSupported}: " + (0 until group.length).joinToString { index ->
                        val f = group.getTrackFormat(index)
                        "${f.width}x${f.height}/${f.bitrate}/${f.codecs}/selected=${group.isTrackSelected(index)}/supported=${group.isTrackSupported(index)}"
                    } }
            }
            output.resolve("startup.txt").writeText("readyMs=${SystemClock.elapsedRealtime() - startedAt}\nstream=$initialStream\n$videoTracks\n")

            val audioButton = waitForNode(instrumentation, contentDescription = "Audio & Subtitles")
            assertTrue("Audio & Subtitles button must be clickable", clickNode(audioButton))
            labels.forEach { label -> waitForNode(instrumentation, text = label) }

            val customSeeks = arguments.getString("liveSeekPositions")?.split(',')?.map { it.toLong() }
            val seekRounds = if (customSeeks != null) customSeeks.map { List(labels.size) { _ -> it } }
                else if (arguments.getString("deepSeeks") == "true")
                listOf(13_000L, 245_000L, 600_000L, 1_200_000L, 2_600_000L, 37_000L).map { List(labels.size) { _ -> it } }
                else listOf(listOf(5_000L, 13_000L, 37_000L, 73_000L))
            seekRounds.forEachIndexed { round, seeks -> labels.forEachIndexed { index, label ->
                val before = NativePlaybackService.decodedAudioEvidence
                val switchStartedAt = SystemClock.elapsedRealtime()
                assertTrue("Audio row '$label' must be clickable", clickNode(waitForNode(instrumentation, text = label)))
                scenario.onActivity { activity ->
                    val player = requireNotNull(activity.playbackController)
                    player.seekTo(seeks[index])
                    player.play()
                }
                waitFor("audio '$label' after seek to ${seeks[index]} ms", 90_000) {
                    var selected = false
                    var healthy = false
                    scenario.onActivity { activity ->
                        val player = activity.playbackController
                        selected = player?.currentTracks?.groups.orEmpty()
                            .filter { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                            .any { group -> (0 until group.length).any { track ->
                                group.isTrackSelected(track) && formatAudioTrackLabel(
                                    group.getTrackFormat(track).language,
                                    group.getTrackFormat(track).label,
                                ) == label
                            } }
                        healthy = activity.playbackUiState.error == null && player?.let { it.playerError == null && it.playbackState == Player.STATE_READY &&
                            it.isPlaying && it.currentPosition >= seeks[index] + 3_000 } == true
                    }
                    val after = NativePlaybackService.decodedAudioEvidence
                    val decoded = before != null && after != null &&
                        (after.first > before.first && after.second >= 100 ||
                            after.first == before.first && after.second >= before.second + 100)
                    selected && healthy && decoded
                }
                scenario.onActivity { activity ->
                    val player = requireNotNull(activity.playbackController)
                    assertNull("Media3 failed on '$label'", player.playerError)
                    assertTrue("Playback did not continue on '$label'", player.isPlaying)
                }
                assertEquals("Audio switches must retain the same CineJoy stream", initialStream, NativePlaybackService.activeStreamUrl)
                output.resolve("round-${round + 1}-track-${index + 1}.txt").writeText(
                    "label=$label\nseekMs=${seeks[index]}\npositionAdvanced=true\ndecodedPcm=true\nelapsedMs=${SystemClock.elapsedRealtime() - switchStartedAt}\n",
                )
            } }
            val soakSeconds = arguments.getString("soakSeconds")?.toIntOrNull() ?: 0
            if (soakSeconds > 0) {
                assertTrue(clickNode(waitForNode(instrumentation, text = "Track 3")))
                scenario.onActivity { it.playbackController!!.seekTo(170_000); it.playbackController!!.play() }
                waitFor("sustained playback warmup", 90_000) {
                    var healthy = false
                    scenario.onActivity { activity ->
                        val player = activity.playbackController!!
                        healthy = player.isPlaying && player.playbackState == Player.STATE_READY && player.currentPosition >= 174_000
                    }
                    healthy
                }
                var startPosition = 0L
                scenario.onActivity { startPosition = it.playbackController!!.currentPosition }
                val soakStart = SystemClock.elapsedRealtime()
                var previousTime = soakStart
                var wasBuffering = false
                var bufferingMs = 0L
                var position = startPosition
                val sizes = mutableSetOf<String>()
                val samples = mutableListOf<String>()
                while (SystemClock.elapsedRealtime() - soakStart < soakSeconds * 1000L) {
                    val now = SystemClock.elapsedRealtime()
                    if (wasBuffering) bufferingMs += now - previousTime
                    previousTime = now
                    scenario.onActivity { activity ->
                        val player = activity.playbackController!!
                        assertNull("Sustained playback source error", player.playerError)
                        assertNull("Sustained Playback Problem", activity.playbackUiState.error)
                        wasBuffering = player.playbackState == Player.STATE_BUFFERING
                        position = player.currentPosition
                        sizes += "${player.videoSize.width}x${player.videoSize.height}"
                        if (samples.size <= (now - soakStart) / 5_000)
                            samples += "elapsed=${now - soakStart},position=$position,bufferMs=${player.totalBufferedDuration},state=${player.playbackState},bandwidth=${androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(context).bitrateEstimate},size=${player.videoSize.width}x${player.videoSize.height}"
                    }
                    assertEquals(initialStream, NativePlaybackService.activeStreamUrl)
                    Thread.sleep(250)
                }
                val elapsed = SystemClock.elapsedRealtime() - soakStart
                output.resolve("soak-samples.txt").writeText(samples.joinToString("\n"))
                output.resolve("soak.txt").writeText("elapsedMs=$elapsed\nadvancedMs=${position - startPosition}\nbufferingMs=$bufferingMs\nvideoSizes=$sizes\n")
                assertTrue("Excessive buffering: $bufferingMs ms in $elapsed ms; video=$sizes", bufferingMs <= 3_000)
                assertTrue("Playback stalled during soak: ${position - startPosition} ms in $elapsed ms", position - startPosition >= elapsed - 3_500)
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                output.resolve("dark-s1e1-all-audio.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        } catch (error: Throwable) {
            var playerFailure: Throwable? = null
            var nativeEvidence = ""
            scenario.onActivity { playerFailure = it.playbackController?.playerError; nativeEvidence = NativePlaybackService.playbackEvidence() }
            val nativeFailure = NativePlaybackService.playbackFailure
            output.resolve("failure.txt").writeText(error.stackTraceToString() + "\nController:\n" +
                playerFailure?.stackTraceToString().orEmpty() + "\nNative:\n" + nativeFailure?.stackTraceToString().orEmpty() + "\n$nativeEvidence")
            throw error
        } finally {
            scenario.close()
            context.stopService(Intent(context, NativePlaybackService::class.java))
        }
    }

    private fun audioLabels(scenario: ActivityScenario<NativePlayerActivity>): List<String> {
        var labels = emptyList<String>()
        scenario.onActivity { activity ->
            labels = activity.playbackController?.currentTracks?.groups.orEmpty()
                .filter { it.type == C.TRACK_TYPE_AUDIO }
                .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it, true) }.map { index ->
                    val format = group.getTrackFormat(index)
                    formatAudioTrackLabel(format.language, format.label)
                } }.distinct()
        }
        return labels
    }

    private fun waitFor(label: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    private fun waitForNode(
        instrumentation: android.app.Instrumentation,
        text: String? = null,
        contentDescription: String? = null,
    ): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitFor(text ?: contentDescription.orEmpty(), 15_000) {
            result = findNode(instrumentation.uiAutomation.rootInActiveWindow) { node ->
                (text == null || node.text?.toString() == text) &&
                    (contentDescription == null || node.contentDescription?.toString() == contentDescription)
            }
            result != null
        }
        return requireNotNull(result)
    }

    private fun findNode(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        root ?: return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) findNode(root.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }
}
