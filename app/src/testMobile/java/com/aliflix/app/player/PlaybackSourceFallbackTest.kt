package com.aliflix.app.player

import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceFallbackTest {
    @Test fun everySourceFallsBackToBothOthersWithoutChangingEpisodeIdentity() {
        for (provider in PlaybackProviderId.entries) {
            val preferences = PlaybackPreferences(dorabyBaseUrl = "https://doraby.example", moviepireBaseUrl = "https://moviepire.example")
            val episode = PlaybackSelection(Media(1396, MediaType.TV, "Breaking Bad"), 2, 3, "Bit by a Dead Bee",
                source = PlaybackSource(provider, "https://selected.example"))
            val choices = playbackSourceFallbacks(episode, preferences)
            assertEquals(episode, choices.first())
            assertEquals(3, choices.map { it.source.provider }.distinct().size)
            choices.forEach {
                assertEquals(2, it.seasonNumber); assertEquals(3, it.episodeNumber)
                assertEquals(episode.media, it.media)
                assertEquals(playbackProgressKey(episode), playbackProgressKey(it))
                assertEquals("tv:1396:s2:e3", playbackProgressKey(it))
            }
            choices.drop(1).filter { it.source.provider == PlaybackProviderId.DORABY }.forEach { assertEquals("https://doraby.example", it.source.baseUrl) }
        }
    }
    @Test fun moviesShareProgressAcrossSourcesAndMirrors() {
        val movie = PlaybackSelection(Media(550, MediaType.MOVIE, "Fight Club"), source = PlaybackSource.doraby())
        assertEquals(setOf("movie:550"), playbackSourceFallbacks(movie, PlaybackPreferences()).map(::playbackProgressKey).toSet())
    }
}
