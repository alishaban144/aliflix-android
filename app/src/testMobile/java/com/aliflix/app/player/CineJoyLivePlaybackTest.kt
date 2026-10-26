package com.aliflix.app.player

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Opt-in live loader/extractor check. Never needs an emulator, and does not claim hardware decoding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CineJoyLivePlaybackTest {
    @Test fun liveDarkAudioSwitchingAndSeeking() {
        val url = System.getenv("ALIFLIX_CINEJOY_LIVE_MASTER").orEmpty()
        assumeTrue("Set ALIFLIX_CINEJOY_LIVE_MASTER to run the live network check", url.isNotBlank())
        val request = NativePlaybackRequest(url, "application/x-mpegURL", CineJoyNativeCatalog.REFERER,
            CineJoyNativeCatalog.USER_AGENT, "", "Dark S1E1", 0, true, preferredVideoWidth = 1920, preferredVideoHeight = 1080)
        val http = DefaultHttpDataSource.Factory().setUserAgent(request.userAgent)
            .setConnectTimeoutMs(5_000).setReadTimeoutMs(8_000)
            .setDefaultRequestProperties(mapOf("Referer" to request.referer, "Origin" to "https://cinejoy.pk"))
        val fragments = DataSource.Factory { CineJoyFragmentDataSource(http.createDataSource(), url) }
        val playlists = DataSource.Factory { CineJoyAudioPlaylistDataSource(fragments.createDataSource(), url, fragments) }
        val upstream = StartupStreamCache.factory(RuntimeEnvironment.getApplication(), playlists)
        val factory = DataSource.Factory { CineJoyManifestDataSource(upstream.createDataSource(), request, adaptiveVideo = true) }
        val video = FakeRenderer(C.TRACK_TYPE_VIDEO)
        val audio = FakeRenderer(C.TRACK_TYPE_AUDIO)
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication(),
            androidx.media3.exoplayer.RenderersFactory { _, _, _, _, _ -> arrayOf(video, audio) })
            .setClock(FakeClock(true))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 2_000, 250, 500).build())
            .setMediaSourceFactory(HlsMediaSource.Factory(factory).setLoadErrorHandlingPolicy(CineJoyLoadErrorPolicy()))
            .build()
        val recovery = CineJoyPlayerRecovery(player, Handler(Looper.getMainLooper())) { _, _ -> }
        recovery.reset(true)
        player.addListener(recovery)
        try {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            run(player).ignoringNonFatalErrors().withTimeoutMs(45_000).untilState(Player.STATE_READY)
            recovery.tick(); recovery.tick()
            for (position in listOf(0L, 13_000L, 60_000L, 120_000L)) {
                for (track in 1..4) {
                    val label = "Track $track"
                    val group = player.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO && it.getTrackFormat(0).label == label }
                    assertTrue(recovery.select(group.mediaTrackGroup, 0))
                    player.seekTo(position)
                    run(player).ignoringNonFatalErrors().untilPendingCommandsAreFullyHandled()
                    run(player).ignoringNonFatalErrors().withTimeoutMs(45_000).untilState(Player.STATE_READY)
                    val beforeAudio = audio.sampleBufferReadCount
                    val beforeVideo = video.sampleBufferReadCount
                    androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play(player).ignoringNonFatalErrors().withTimeoutMs(45_000).untilPositionAtLeast(position + 1_000)
                    player.pause()
                    assertTrue("No audio samples for $label at $position", audio.sampleBufferReadCount > beforeAudio)
                    assertTrue("No video samples for $label at $position", video.sampleBufferReadCount > beforeVideo)
                    assertNull(player.playerError)
                    recovery.tick(); recovery.tick()
                    println("LIVE PASS: $label at ${position}ms; audio=${audio.sampleBufferReadCount - beforeAudio}, video=${video.sampleBufferReadCount - beforeVideo}")
                }
            }
        } catch (error: Exception) {
            generateSequence<Throwable>(error) { it.cause }.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let {
                println("LIVE HTTP FAILURE: ${it.responseCode} ${it.dataSpec.uri} position=${it.dataSpec.position} length=${it.dataSpec.length} body=${String(it.responseBody)}")
            }
            throw error
        } finally { recovery.reset(false); player.release() }
    }
}
