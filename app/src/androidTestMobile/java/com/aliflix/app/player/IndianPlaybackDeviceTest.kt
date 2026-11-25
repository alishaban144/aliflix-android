@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aliflix.app.player

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.MobilePlaybackProvider
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class IndianPlaybackDeviceTest {
    @Test fun ranaVikramaStartsOnMegaCloud() = verify(
        Media(691633, MediaType.MOVIE, "Rana Vikrama", year = "2015", originalLanguage = "kn"),
        MobilePlaybackProvider.HIMOVIES, server = "MegaCloud",
    )

    @Test fun panchayatPlaysSecondEpisode() = verify(
        Media(101352, MediaType.TV, "Panchayat", year = "2020", originalLanguage = "hi"),
        MobilePlaybackProvider.HIMOVIES, season = 1, episode = 2,
    )

    @Test fun kantaraPlaysFromCinego() = verify(
        Media(858485, MediaType.MOVIE, "Kantara", year = "2022", originalLanguage = "kn"),
        MobilePlaybackProvider.CINEGO,
    )

    @Test fun familyManPlaysThirdEpisodeOfSeasonTwo() = verify(
        Media(93397, MediaType.TV, "The Family Man", year = "2019", originalLanguage = "hi"),
        MobilePlaybackProvider.CINEGO, season = 2, episode = 3,
    )

    private fun verify(media: Media, provider: MobilePlaybackProvider, season: Int? = null, episode: Int? = null, server: String? = null) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveIndianPlayback") == "true")
        grantNativeFixtureNetworkPermission()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = Intent(context, NativePlaybackService::class.java)
        context.stopService(service)
        val selection = PlaybackSelection(media, season, episode, source = PlaybackSource(provider))
        val intent = Intent(context, NativePlayerActivity::class.java)
            .putExtra("selection", selection.nativeJson()).putExtra("autoSubtitles", false)
        val start = SystemClock.elapsedRealtime()
        try {
            ActivityScenario.launch<NativePlayerActivity>(intent, nativePhoneLaunchOptions()).use { scenario ->
                var observed = "Preparing playback"
                repeat(350) {
                    var playing = false
                    scenario.onActivity { activity ->
                        observed = activity.playbackUiState.error ?: activity.playbackUiState.server
                        val player = activity.playbackController
                        playing = player?.isPlaying == true && player.videoSize.width > 0 && player.currentPosition >= 1200
                        if (playing) {
                            val resolved = nativeSelection(requireNotNull(NativePlaybackService.activeRequest).selectionJson)
                            assertEquals(provider, resolved.source.identity)
                            assertEquals(season, resolved.seasonNumber)
                            assertEquals(episode, resolved.episodeNumber)
                            if (server != null) assertEquals(server, observed)
                        }
                    }
                    if (playing) {
                        android.util.Log.i("IndianPlaybackCheck", "${media.title}: ${provider.displayName} $observed in ${SystemClock.elapsedRealtime() - start} ms")
                        return
                    }
                    SystemClock.sleep(120)
                }
                assertTrue("${media.title} did not render video: $observed", false)
            }
        } finally {
            context.stopService(service)
        }
    }
}
