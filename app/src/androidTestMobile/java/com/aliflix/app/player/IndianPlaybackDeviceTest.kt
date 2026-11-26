@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aliflix.app.player

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.AliflixApplication
import com.aliflix.app.MainActivity
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.MobilePlaybackProvider
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class IndianPlaybackDeviceTest {
    @Test fun ranaVikramaStartsInMedia3WithoutManualServerSelection() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveIndianPlayback") == "true")
        grantNativeFixtureNetworkPermission()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = Intent(context, NativePlaybackService::class.java)
        context.stopService(service)
        val media = Media(691633, MediaType.MOVIE, "Rana Vikrama", year = "2015", originalLanguage = "kn")
        val selection = PlaybackSelection(media, source = PlaybackSource(MobilePlaybackProvider.HIMOVIES))
        val intent = Intent(context, NativePlayerActivity::class.java).putExtra("selection", selection.nativeJson())
        val start = SystemClock.elapsedRealtime()
        try {
            ActivityScenario.launch<NativePlayerActivity>(intent, nativePhoneLaunchOptions()).use { scenario ->
                var route = "Auto"
                repeat(350) {
                    var started = false
                    scenario.onActivity { player ->
                        assertFalse("Provider frames belong in the hidden resolver", hasVisibleWebView(player.window.decorView))
                        val native = player.playbackController
                        started = native?.isPlaying == true && native.videoSize.width > 0 && native.currentPosition >= 1200L
                        if (started) {
                            route = player.playbackUiState.server
                            assertEquals(media.id, nativeSelection(requireNotNull(NativePlaybackService.activeRequest).selectionJson).media.id)
                        }
                    }
                    if (started) {
                        android.util.Log.i("IndianPlaybackCheck", "Rana Vikrama: Media3 via $route in ${SystemClock.elapsedRealtime() - start} ms")
                        return
                    }
                    SystemClock.sleep(120)
                }
                assertTrue("Rana Vikrama did not play in Media3", false)
            }
        } finally { context.stopService(service) }
    }

    private fun hasVisibleWebView(view: View): Boolean = view.isShown && view.alpha > 0f &&
        (view is WebView || view is ViewGroup && view.children.any(::hasVisibleWebView))

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

    private fun verify(media: Media, provider: MobilePlaybackProvider, season: Int? = null, episode: Int? = null) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveIndianPlayback") == "true")
        grantNativeFixtureNetworkPermission()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = Intent(context, NativePlaybackService::class.java)
        context.stopService(service)
        val selection = PlaybackSelection(media, season, episode, source = PlaybackSource(provider))
        val start = SystemClock.elapsedRealtime()
        var resolvedServer = ""
        try {
            val request = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var owner: MainActivity? = null
                scenario.onActivity { owner = it }
                runBlocking { withContext(Dispatchers.Main) {
                    val activity = requireNotNull(owner)
                    val parent = activity.findViewById<ViewGroup>(android.R.id.content)
                    val host = FrameLayout(activity).apply { alpha = 0f }
                    parent.addView(host, ViewGroup.LayoutParams(-1, -1))
                    try {
                        NativeStreamResolver(activity, (activity.application as AliflixApplication).playbackProgressStore, host).use { resolver ->
                            withTimeout(38_000) {
                                resolver.resolve(selection, 0L, emptySet(), onServers = {
                                    android.util.Log.i("IndianPlaybackCheck", "$provider servers ${it.joinToString()}")
                                }) { resolvedServer = it }
                            }
                        }
                    } finally { parent.removeView(host) }
                } }
            }
            val intent = Intent(context, NativePlayerActivity::class.java)
                .putExtra("request", request.copy(positionMs = 0L, playing = true).toJson())
                .putExtra("autoSubtitles", false)
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
                        }
                    }
                    if (playing) {
                        android.util.Log.i("IndianPlaybackCheck", "${media.title}: ${provider.displayName} $resolvedServer in ${SystemClock.elapsedRealtime() - start} ms")
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
