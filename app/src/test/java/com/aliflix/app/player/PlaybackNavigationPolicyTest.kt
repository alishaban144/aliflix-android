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
}
