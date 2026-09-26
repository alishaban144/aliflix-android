package com.aliflix.app.player

import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceFallbackTest {
    @Test fun everySourceFallsBackToBothOthersWithoutChangingEpisodeIdentity() {
        val generalProviders = listOf(PlaybackProviderId.CINEJOY, PlaybackProviderId.RAMOFLIX, PlaybackProviderId.DORABY, PlaybackProviderId.MOVIEPIRE)
        for (provider in PlaybackProviderId.entries) {
            val preferences = PlaybackPreferences(dorabyBaseUrl = "https://doraby.example", moviepireBaseUrl = "https://moviepire.example")
            val episode = PlaybackSelection(Media(1396, MediaType.TV, "Breaking Bad"), 2, 3, "Bit by a Dead Bee",
                source = PlaybackSource(provider, "https://selected.example"))
            val choices = playbackSourceFallbacks(episode, preferences)
            assertEquals(episode, choices.first())
            // The selected source stays first, and every other general mirror is offered exactly once.
            assertEquals(
                (listOf(provider) + generalProviders.filter { it != provider }).toSet(),
                choices.map { it.source.provider }.toSet(),
            )
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
    @Test fun cinejoyGetsALongerStartupBudgetThanOtherSources() {
        // CineJoy must boot its own bundle and answer its own API before it exposes a manifest.
        assertTrue(resolveBudgetMillis(PlaybackProviderId.CINEJOY) > resolveBudgetMillis(PlaybackProviderId.RAMOFLIX))
        assertTrue(resolveBudgetMillis(PlaybackProviderId.CINEJOY) > resolveBudgetMillis(PlaybackProviderId.MOVIEPIRE))
        // Every other provider keeps the established window.
        assertEquals(10_000, resolveBudgetMillis(PlaybackProviderId.RAMOFLIX))
        assertEquals(10_000, resolveBudgetMillis(PlaybackProviderId.DORABY))
        assertEquals(10_000, resolveBudgetMillis(PlaybackProviderId.MOVIEPIRE))
        assertEquals(10_000, resolveBudgetMillis(PlaybackProviderId.MIRURO))
        assertEquals(10_000, resolveBudgetMillis(PlaybackProviderId.ANIKURO))
    }
    @Test fun japaneseAnimeRacesBothAnimeNativeSourcesBeforeGeneralMirrors() {
        val anime = Media(21, MediaType.TV, "One Piece", genres = listOf("Animation"), originalLanguage = "ja")
        assertTrue(PlaybackProviderId.MIRURO.isAvailableFor(anime))
        assertTrue(PlaybackProviderId.ANIKURO.isAvailableFor(anime))
        assertFalse(PlaybackProviderId.ANIKURO.isAvailableFor(Media(550, MediaType.MOVIE, "Fight Club", originalLanguage = "en")))
        val selection = PlaybackSelection(anime, 1, 243, "Episode 243", source = PlaybackSource(PlaybackProviderId.ANIKURO))
        val choices = playbackSourceFallbacks(selection, PlaybackPreferences())
        assertEquals(
            listOf(
                PlaybackProviderId.ANIKURO,
                PlaybackProviderId.CINEJOY,
                PlaybackProviderId.MIRURO,
                PlaybackProviderId.RAMOFLIX,
                PlaybackProviderId.DORABY,
                PlaybackProviderId.MOVIEPIRE,
            ),
            choices.map { it.source.provider },
        )
        assertEquals("https://anikuro.to/", choices.first().source.buildEntryUrl(anime))
        choices.forEach {
            assertEquals(anime, it.media)
            assertEquals("tv:21:s1:e243", playbackProgressKey(it))
        }
    }
}
