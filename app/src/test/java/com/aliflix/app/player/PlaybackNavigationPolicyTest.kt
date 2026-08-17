package com.aliflix.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNavigationPolicyTest {
    @Test
    fun allowsApprovedPlaybackHosts() {
        listOf(
            "https://ramoflix.net/inception/",
            "https://moviepire.ru/watch/1275779",
            "https://moviepire.ru/watch/66732?s=2&e=3",
            "https://doraby.com/movie/1083381",
            "https://67movies.nl/watch/movie/27205",
            "https://player.vidlove.cc/embed/tv/66732/2/3",
            "https://www.345movie.nl/watch/movie/27205",
            "https://345movie.nl/home",
            "https://456movie.nl/movie/watch/27205",
            "https://player.456movie.nl/tv/watch/66732",
            "https://67movies.nl/watch/movie/27205",
            "https://www.67movie.nl/watch",
            "https://player.videasy.to/tv/66732/2/3",
            "https://vidlink.pro/tv/66732/2/3",
            "https://111movies.net/tv/66732/2/3",
            "https://nontongo.win/embed/tv/66732/2/3",
            "https://soap2night.cc/embed/movie/tt33764258",
            "https://cloudorchestranova.com/rcp/player-token",
        ).forEach { url ->
            assertTrue(url, PlaybackNavigationPolicy.isAllowedTopLevel(url))
        }
    }

    @Test
    fun allowsEditedRamoflixHostButNotSpoofedHost() {
        val customHosts = setOf("ramo.example")

        assertTrue(
            PlaybackNavigationPolicy.isAllowedTopLevel(
                url = "https://mirror.ramo.example/watch/movie/27205",
                customHosts = customHosts,
            ),
        )
        assertFalse(
            PlaybackNavigationPolicy.isAllowedTopLevel(
                url = "https://ramo.example.evil.test/watch/movie/27205",
                customHosts = customHosts,
            ),
        )
    }

    @Test
    fun removedProviderHostsAreBlocked() {
        listOf(
            "https://www.cineby.at/watch",
            "https://shuttletv.su/watch",
            "https://vidbox.vc/watch",
            "https://popcornmovies.io/watch",
            "https://toustream.xyz/watch",
            "https://bcine.ru/movie/1083381",
        ).forEach { url ->
            assertFalse(url, PlaybackNavigationPolicy.isAllowedTopLevel(url))
        }
    }

    @Test
    fun blocksExternalAdAndSpoofedHosts() {
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("https://ads.example/watch"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("https://345movie.nl.example/watch"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("https://not345movie.nl/watch"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("https://456movie.nl.example/watch"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("https://not456movie.nl/watch"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("https://player.videasy.to.example/ad"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("https://vidlink.pro.example/ad"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("https://111movies.net.example/ad"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("https://nontongo.win.example/ad"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("https://67movies.nl.evil.test/watch"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("https://player.vidlove.cc.evil.test/embed"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("http://ramoflix.net/watch"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("http://67movies.nl/watch"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("http://player.vidlove.cc/embed"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("http://345movie.nl/watch"))
        assertFalse(PlaybackNavigationPolicy.isAllowedTopLevel("intent://ad-app"))
    }

    @Test
    fun blocksKnownAdResourcesWithoutBlockingPlaybackCdnHosts() {
        listOf(
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://static.doubleclick.net/instream/ad_status.js",
            "https://delivery.realsrv.com/banner.js",
            "https://subdomain.onclickperformance.com/click",
            "https://cdn.mgid.com/native-ad.js",
            "https://static.richads.com/banner.js",
            "https://delivery.admaven.com/popunder",
            "https://balkersestian.com/grVFgoEI1NXMyA4vz/97239",
            "https://crowdsynonym.com/1a/ee/cd/invoke.js",
            "https://push-sdk.com/f/sdk.js?z=2387233",
            "https://mploejuiashsatea.com?dVydX=1229258",
            "https://dcbbwymp1bhlf.cloudfront.net/?wbbcd=1216333",
            "https://tb.blowersdialer.com/rhu6smAV3cCg77jT/129065",
            "https://profitablebutton.com/cjDW9S6/b.2I5Ml/SQW/Qy9V",
            "https://acscdn.com/script/aclib.js",
            "https://ut.hebamicmopeds.com/rWJVcEgMFI5toVCz/141438",
            "https://s10.histats.com/js15_as.js",
            "https://sbx-2dl.pages.dev/fake-security-warning",
        ).forEach { url ->
            assertTrue(url, PlaybackNavigationPolicy.isBlockedAdResource(url))
        }

        listOf(
            "https://moviepire.ru/watch/1396",
            "https://cloudorchestranova.com/rcp/player-token",
            "https://player.vidlove.cc/embed/tv/1396/1/1",
        ).forEach { url ->
            assertFalse(url, PlaybackNavigationPolicy.isBlockedAdResource(url))
        }
    }

    @Test
    fun blocksMoviepireAdInjectionWithoutBlockingPlayerMedia() {
        listOf(
            "https://vidrock.ru/sbx.js",
            "https://ads.unknown-network.example/banner.js",
            "https://cdn.unknown-network.example/popunder/index.js",
            "https://cdn.unknown-network.example/push-sdk/client.js",
        ).forEach { url ->
            assertTrue(url, PlaybackNavigationPolicy.isBlockedMoviepireResource(url))
        }

        listOf(
            "https://moviepire.ru/assets/index-pU0E7sB1.js",
            "https://vidrock.ru/assets/index-Bz6x-FJQ.js",
            "https://vidrock.ru/movie/1275779",
            "https://s.vdrk.site/csub.html?id=1275779",
            "https://cdn.example/video/master.m3u8",
            "https://cdn.example/video/segment-001.ts",
            "https://www.gstatic.com/cv/js/sender/v1/cast_sender.js?loadCastFramework=1",
        ).forEach { url ->
            assertFalse(url, PlaybackNavigationPolicy.isBlockedMoviepireResource(url))
        }
    }

    @Test
    fun blocksUnsafeMoviepireChildFrameDestinations() {
        listOf(
            "https://mploejuiashsatea.com?dndqf=1216944",
            "https://sbx-2dl.pages.dev#https%3A%2F%2Fvidrock.ru",
            "intent://fake-security-app",
            "market://details?id=fake.security.app",
            "javascript:location='https://ads.example'",
            "data:text/html,fake-warning",
            "not a url",
        ).forEach { url ->
            assertTrue(
                url,
                PlaybackNavigationPolicy.isBlockedMoviepireSubframeNavigation(url),
            )
        }

        listOf(
            "about:blank",
            "blob:https://vidrock.ru/8dc75f85-2576-42c9-a04f-28c985428cd7",
            "https://vidrock.ru/movie/1275779",
            "https://player.videasy.net/movie/1275779",
            "https://video.moviepire.co/embed/movie/1275779",
        ).forEach { url ->
            assertFalse(
                url,
                PlaybackNavigationPolicy.isBlockedMoviepireSubframeNavigation(url),
            )
        }
    }
}
