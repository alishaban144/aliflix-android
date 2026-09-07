package com.aliflix.app.player

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPlayerCompatibilityTest {
    @Test
    fun moviepireTvBootstrapsThroughSeriesPageWhileMoviesStayDirect() {
        val series = Media(id = 86831, type = MediaType.TV, title = "Love, Death & Robots")
        val episode = PlaybackSelection(
            media = series,
            seasonNumber = 1,
            episodeNumber = 4,
            source = PlaybackSource.moviepire(),
        )
        val movie = episode.copy(media = Media(27205, MediaType.MOVIE, "Inception"))

        val expectedBootstrap = if (!com.aliflix.app.BuildConfig.IS_TV) "https://moviepire.ru/series/86831" else null
        assertEquals(expectedBootstrap, mobileMoviepireEpisodeBootstrapUrl(episode))
        assertEquals(null, mobileMoviepireEpisodeBootstrapUrl(movie))
        assertEquals("https://moviepire.ru/watch/86831?s=1&e=4", episode.entryUrl)
    }

    @Test
    fun exactEpisodeResolverUsesMoviepireOriginalSeasonAndEpisodeControls() {
        val script = moviepireExactEpisodeResolverScript(1405, 1, 8)

        listOf(
            "expectedId = 1405",
            "expectedSeason = 1",
            "expectedEpisode = 8",
            "seasonSelect.dispatchEvent",
            "target.searchParams.get(\"s\")",
            "target.searchParams.get(\"e\")",
            "exactLink.click()",
            "episodes-expanded",
        ).forEach { marker -> assertTrue(marker, script.contains(marker)) }
        assertFalse(script.contains("Dexter", ignoreCase = true))
        assertFalse(script.contains("iframe.src ="))
    }

    @Test
    fun removesWebViewAndAppMarkersFromUserAgent() {
        val userAgent =
            "Mozilla/5.0 (Linux; Android 11; TV Build/RP1A; wv) " +
                "AppleWebKit/537.36 Version/4.0 Chrome/120.0.0.0 Safari/537.36 " +
                "AliflixTV/2.4-tv"

        val compatible = browserCompatibleUserAgent(userAgent)

        assertEquals(
            "Mozilla/5.0 (Linux; Android 11; TV Build/RP1A) " +
                "AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
            compatible,
        )
        assertFalse(compatible.contains("wv"))
        assertFalse(compatible.contains("Aliflix"))
    }

    @Test
    fun moviepireShieldIsScopedToPlaybackDocumentOrigins() {
        val origins = mobileMoviepireShieldOriginRules("custom.moviepire.example")

        assertTrue(origins.contains("https://custom.moviepire.example"))
        assertTrue(origins.contains("https://*.custom.moviepire.example"))
        assertTrue(origins.contains("https://moviepire.ru"))
        assertTrue(origins.contains("https://vidrock.ru"))
        assertTrue(origins.contains("https://*.videasy.net"))
        assertFalse(origins.contains("*"))
        assertFalse(origins.any { it.startsWith("http://") })
    }

    @Test
    fun moviepireShieldRunsBeforeAdsAndKeepsPlayerApisAvailable() {
        val script = mobileMoviepireAdShieldScript()

        listOf(
            "#paldo-ad",
            "balkersestian.com",
            "crowdsynonym.com",
            "mploejuiashsatea.com",
            "acscdn.com",
            "hebamicmopeds.com",
            "adnetworks",
            "Node.prototype.appendChild",
            "EventTarget.prototype.addEventListener",
            "MutationObserver",
            "system warning detected",
        ).forEach { marker -> assertTrue(marker, script.contains(marker)) }

        assertFalse(script.contains("HTMLVideoElement.prototype"))
        assertFalse(script.contains("requestFullscreen ="))
        assertFalse(script.contains("chrome.cast ="))
    }

    @Test
    fun nativeServerDiscoveryUsesOriginalSelectIdentityWithoutHardcodedNames() {
        val script = moviepireServerDiscoveryScript()

        assertFalse(script.contains("Mist", ignoreCase = true))
        assertTrue(script.contains("querySelectorAll(\"select\")"))
        assertTrue(script.contains("aliflixServerSelect"))
        assertTrue(script.contains("aliflixServerKey"))
        assertTrue(script.contains("option.value"))
        assertTrue(script.contains("aliflixWrapperControls"))
        assertTrue(script.contains("a[download]"))
        assertTrue(script.contains("document.querySelectorAll(downloadSelector)"))
        assertTrue(script.contains("target.querySelector(\"iframe,video\")"))
        assertTrue(script.contains("display\", \"none\""))
        assertFalse(script.contains("querySelectorAll(\"button\")"))
    }

    @Test
    fun discoveredServerPayloadKeepsStableOriginalControlKeys() {
        val servers = parseMoviepireServerDiscovery(
            """[{"key":"option:0:azute","label":"Azute","selected":true},""" +
                """{"key":"option:1:other","label":"Other","selected":false}]""",
        )

        assertEquals(
            listOf("option:0:azute", "option:1:other"),
            servers.map(MoviepireServerOption::key),
        )
        assertEquals("Azute", servers.first().label)
        assertTrue(servers.first().selected)
    }

    @Test
    fun playbackBridgeObservesRealVideoAndUsesOriginScopedWebMessaging() {
        val script = mobileMoviepireProgressBridgeScript()

        listOf(
            "document.querySelectorAll(\"video\")",
            "loadedmetadata",
            "post(\"playing\"",
            "timeupdate",
            "pause",
            "seeked",
            "ended",
            "AliflixPlaybackProgress",
            "aliflix-seek",
            "minimumDurationSeconds = 60",
            "videoScore",
            "activeVideo.isConnected",
            "bridge.onmessage",
            "pendingSeekSeconds",
            "video.seekable.length",
            "11000",
            "frame.contentWindow?.postMessage",
            "video.play()",
            "aliflix-playback-control",
            "controlPlayback",
            "video.pause()",
            "documentHidden",
            "aliflix-subtitles",
            "aliflix-subtitles-clear",
            "video.addTextTrack",
            "window.VTTCue",
            "video::cue",
            "subtitleDelaySeconds",
            "subtitleFontPercent",
            "subtitleLanguage",
            "subtitleLabel",
            "applySubtitles(video)",
        ).forEach { marker -> assertTrue(marker, script.contains(marker)) }
        assertFalse(script.contains("contentDocument"))
        assertFalse(script.contains("addJavascriptInterface"))
        assertFalse(script.contains("aliflix-fullscreen"))
        assertFalse(script.contains("requestFullscreen"))
        assertFalse(script.contains("document.hidden ="))
        assertFalse(script.contains("aliflix-player-cast-presentation"))
        assertFalse(script.contains("video {\n            width: 100vw"))
    }

    @Test
    fun serverSwitchAndNormalResumeRestoreAnyValidPositivePosition() {
        assertTrue(playbackSeekEligible(10.0, 2_400.0, switchingServer = true))
        assertTrue(playbackSeekEligible(10.0, 2_400.0, switchingServer = false))
        assertTrue(playbackSeekEligible(25.0, 2_400.0, switchingServer = false))
        assertTrue(playbackSeekEligible(2_400.0, 2_400.0, switchingServer = true))
    }

    @Test
    fun initialServerPositionCannotReplaceResumePointBeforeSeekIsConfirmed() {
        assertTrue(playbackRestoreStillPending(0.0, 1_337.0))
        assertTrue(playbackRestoreStillPending(1_300.0, 1_337.0))
        assertTrue(playbackRestoreStillPending(1_334.0, 1_337.0))
        assertFalse(playbackRestoreStillPending(1_337.0, 1_337.0))
        assertFalse(playbackRestoreStillPending(0.0, null))
    }

}
